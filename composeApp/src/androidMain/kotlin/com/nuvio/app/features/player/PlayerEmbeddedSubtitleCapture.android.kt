package com.nuvio.app.features.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects text cues as Media3 parses embedded subtitle samples during media extraction.
 *
 * Unlike TextOutput live capture, this receives the parser's timed cue samples rather than
 * reconstructing cue boundaries from the time at which a subtitle happened to be rendered.
 * Extraction still follows the media source's loading strategy, so a network stream may be
 * populated progressively as more media data is loaded.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlayerEmbeddedSubtitleCapture {
    private const val TAG = "NuvioEmbeddedSubs"
    private const val MAX_CUES_PER_TRACK = 20_000
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracks = ConcurrentHashMap<String, TrackCues>()
    @Volatile private var selectedTrackKey: String? = null

    private data class TrackCues(
        val lock: Any = Any(),
        val cues: LinkedHashMap<String, LearningSubtitleCue> = linkedMapOf(),
    )

    fun reset() {
        selectedTrackKey = null
        tracks.clear()
    }

    fun hasSelectedTrackCues(): Boolean {
        val key = selectedTrackKey ?: return false
        val entry = tracks[key] ?: return false
        return synchronized(entry.lock) { entry.cues.isNotEmpty() }
    }

    fun selectTrack(format: Format?) {
        val key = format?.let(::trackKey)
        selectedTrackKey = key
        if (key == null) return
        val snapshot = tracks[key]?.let { entry ->
            synchronized(entry.lock) { entry.cues.values.toList() }
        }.orEmpty()
        if (snapshot.isNotEmpty()) {
            mainHandler.post { PlayerLearningSubtitleState.updatePartial(snapshot) }
        }
    }

    fun createParserFactory(): SubtitleParser.Factory {
        val delegate = DefaultSubtitleParserFactory()
        return object : SubtitleParser.Factory {
            override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

            override fun getCueReplacementBehavior(format: Format): Int =
                delegate.getCueReplacementBehavior(format)

            override fun create(format: Format): SubtitleParser {
                val parser = delegate.create(format)
                val key = trackKey(format)
                return object : SubtitleParser {
                    override fun getCueReplacementBehavior(): Int =
                        parser.cueReplacementBehavior

                    override fun parse(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        outputOptions: SubtitleParser.OutputOptions,
                        output: java.util.function.Consumer<CuesWithTiming>,
                    ) {
                        parser.parse(data, offset, length, outputOptions) { parsed ->
                            capture(key, parsed)
                            output.accept(parsed)
                        }
                    }

                    override fun reset() = parser.reset()
                }
            }
        }
    }

    private fun capture(key: String, parsed: CuesWithTiming) {
        if (!PlayerSettingsRepository.uiState.value.englishLearningEnabled) return
        val startUs = parsed.startTimeUs
        val endUs = parsed.endTimeUs
        if (startUs == C.TIME_UNSET || endUs == C.TIME_UNSET || endUs <= startUs) return

        val text = parsed.cues.mapNotNull(Cue::text)
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() } ?: return

        val startMs = startUs / 1_000L
        val endMs = endUs / 1_000L
        if (startMs < 0L || endMs <= startMs) return

        val entry = tracks.getOrPut(key) { TrackCues() }
        val snapshot = synchronized(entry.lock) {
            val cue = LearningSubtitleCue(startMs, endMs, text)
            entry.cues["$startMs|$endMs|$text"] = cue
            while (entry.cues.size > MAX_CUES_PER_TRACK) {
                val firstKey = entry.cues.keys.firstOrNull() ?: break
                entry.cues.remove(firstKey)
            }
            entry.cues.values.sortedBy { it.startTimeMs }
        }
        if (selectedTrackKey == key) {
            mainHandler.post { PlayerLearningSubtitleState.updatePartial(snapshot) }
        }
    }

    private fun trackKey(format: Format): String =
        format.id?.takeIf { it.isNotBlank() }
            ?: listOf(format.sampleMimeType, format.language, format.label).joinToString("|")
}
