package com.nuvio.app.features.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
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
    private val snapshotIntervalMs = 200L
    @Volatile private var snapshotPending = false
    private val publishSelectedSnapshot = Runnable {
        snapshotPending = false
        val generation = selectionGeneration
        val key = selectedTrackKey ?: return@Runnable
        val entry = tracks[key] ?: return@Runnable
        val snapshot = synchronized(entry.lock) { entry.cues.values.toList() }
        if (snapshot.isNotEmpty() && selectionGeneration == generation && selectedTrackKey == key) {
            PlayerLearningSubtitleState.updatePartial(snapshot)
            val maxCueEndMs = snapshot.maxOfOrNull { it.endTimeMs } ?: return@Runnable
            val shouldLogCoverage = lastLoggedCueEndMs == Long.MIN_VALUE ||
                maxCueEndMs >= lastLoggedCueEndMs + 30_000L ||
                snapshot.size >= lastLoggedCueCount + 500
            if (shouldLogCoverage) {
                val playheadMs = playbackPositionMsProvider?.invoke()?.coerceAtLeast(0L)
                val aheadMs = playheadMs?.let { maxCueEndMs - it }
                Log.i(
                    TAG,
                    "coverage cues=${snapshot.size} playheadMs=${playheadMs ?: -1L} " +
                        "maxCueEndMs=$maxCueEndMs aheadMs=${aheadMs ?: -1L}",
                )
                lastLoggedCueCount = snapshot.size
                lastLoggedCueEndMs = maxCueEndMs
            }
        }
    }
    private val tracks = ConcurrentHashMap<String, TrackCues>()
    @Volatile private var activeSourceIdentity: String = ""
    @Volatile private var selectionGeneration: Long = 0L
    @Volatile private var selectedTrackKey: String? = null
    @Volatile private var playbackPositionMsProvider: (() -> Long)? = null
    private var lastLoggedCueCount = 0
    private var lastLoggedCueEndMs = Long.MIN_VALUE

    private data class TrackCues(
        val lock: Any = Any(),
        val cues: LinkedHashMap<String, LearningSubtitleCue> = linkedMapOf(),
    )

    fun configureSource(sourceIdentity: String) {
        if (activeSourceIdentity == sourceIdentity) return
        activeSourceIdentity = sourceIdentity
        selectionGeneration += 1L
        selectedTrackKey = null
        mainHandler.removeCallbacks(publishSelectedSnapshot)
        snapshotPending = false
        tracks.clear()
    }

    fun reset() {
        selectionGeneration += 1L
        selectedTrackKey = null
        playbackPositionMsProvider = null
        lastLoggedCueCount = 0
        lastLoggedCueEndMs = Long.MIN_VALUE
        mainHandler.removeCallbacks(publishSelectedSnapshot)
        snapshotPending = false
        tracks.clear()
    }

    fun hasSelectedTrackCues(): Boolean {
        val key = selectedTrackKey ?: return false
        val entry = tracks[key] ?: return false
        return synchronized(entry.lock) { entry.cues.isNotEmpty() }
    }

    fun selectTrack(format: Format?, playbackPositionMs: (() -> Long)? = null) {
        val generation = ++selectionGeneration
        mainHandler.removeCallbacks(publishSelectedSnapshot)
        snapshotPending = false
        val key = format?.let(::trackKey)
        selectedTrackKey = key
        playbackPositionMsProvider = if (key == null) null else playbackPositionMs
        lastLoggedCueCount = 0
        lastLoggedCueEndMs = Long.MIN_VALUE
        if (key == null) return
        val snapshot = tracks[key]?.let { entry ->
            synchronized(entry.lock) { entry.cues.values.toList() }
        }.orEmpty()
        if (snapshot.isNotEmpty()) {
            mainHandler.post {
                if (selectionGeneration == generation && selectedTrackKey == key) {
                    PlayerLearningSubtitleState.updatePartial(snapshot)
                }
            }
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
                // The playback parser may intentionally emit only cues near the current position.
                // A second stateful parser requests all cues from the same samples for learning.
                // Keeping parser instances separate prevents the full-track scan from changing
                // the cues and timing behavior delivered to ExoPlayer.
                val captureParser = delegate.create(format)
                val key = trackKey(format)
                return object : SubtitleParser {
                    override fun getCueReplacementBehavior(): Int =
                        parser.cueReplacementBehavior

                    override fun parse(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        outputOptions: SubtitleParser.OutputOptions,
                        output: Consumer<CuesWithTiming>,
                    ) {
                        if (PlayerSettingsRepository.uiState.value.englishLearningEnabled) {
                            val captureOptions = if (outputOptions.outputAllCues) {
                                outputOptions
                            } else {
                                SubtitleParser.OutputOptions.allCues()
                            }
                            captureParser.parse(
                                data,
                                offset,
                                length,
                                captureOptions,
                                Consumer { parsed -> capture(key, parsed) },
                            )
                        }
                        parser.parse(data, offset, length, outputOptions, output)
                    }

                    override fun reset() {
                        parser.reset()
                        captureParser.reset()
                    }
                }
            }
        }
    }

    private fun capture(key: String, parsed: CuesWithTiming) {
        if (!PlayerSettingsRepository.uiState.value.englishLearningEnabled) return
        val startUs = parsed.startTimeUs
        val endUs = parsed.endTimeUs
        if (startUs == C.TIME_UNSET || endUs == C.TIME_UNSET || endUs <= startUs) return

        val texts = parsed.cues.mapNotNull(Cue::text)
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (texts.isEmpty()) return

        val startMs = startUs / 1_000L
        val endMs = endUs / 1_000L
        if (startMs < 0L || endMs <= startMs) return

        val entry = tracks.getOrPut(key) { TrackCues() }
        val cueAdded = synchronized(entry.lock) {
            var added = false
            // Media3 can emit several simultaneous Cue objects in one timed group. Keep each
            // object as its own cue: joining them with newlines falsely treats separate cues as
            // one sentence and spreads word highlighting across unrelated text.
            texts.forEach { text ->
                val cue = LearningSubtitleCue(startMs, endMs, text)
                val cueKey = "$startMs|$endMs|$text"
                if (!entry.cues.containsKey(cueKey)) added = true
                entry.cues[cueKey] = cue
            }
            while (entry.cues.size > MAX_CUES_PER_TRACK) {
                val firstKey = entry.cues.keys.firstOrNull() ?: break
                entry.cues.remove(firstKey)
            }
            added
        }
        // Parsing can emit many samples quickly. Coalesce UI snapshots while retaining cues.
        if (cueAdded && selectedTrackKey == key && !snapshotPending) {
            snapshotPending = true
            mainHandler.postDelayed(publishSelectedSnapshot, snapshotIntervalMs)
        }
    }

    private fun trackKey(format: Format): String =
        activeSourceIdentity + "|" + (
            format.id?.takeIf { it.isNotBlank() }
                ?: listOf(format.sampleMimeType, format.language, format.label).joinToString("|")
        )
}
