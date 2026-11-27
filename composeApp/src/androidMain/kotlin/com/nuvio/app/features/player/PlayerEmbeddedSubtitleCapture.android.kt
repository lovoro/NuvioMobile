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
        val snapshot = synchronized(entry.lock) {
            entry.pendingCues.values.toList().also { entry.pendingCues.clear() }
        }
        if (snapshot.isNotEmpty() && selectionGeneration == generation && selectedTrackKey == key) {
            val readyCues = displayableCues(snapshot)
            if (readyCues.isNotEmpty()) PlayerLearningSubtitleState.appendPartialCues(readyCues)
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
    @Volatile private var targetTrackKey: String? = null
    @Volatile private var configuredTargetLanguage: String = ""
    @Volatile private var playbackPositionMsProvider: (() -> Long)? = null
    private var lastLoggedCueCount = 0
    private var lastLoggedCueEndMs = Long.MIN_VALUE

    private data class TrackCues(
        val language: String?,
        val label: String?,
        val lock: Any = Any(),
        val cues: LinkedHashMap<String, LearningSubtitleCue> = linkedMapOf(),
        val pendingCues: LinkedHashMap<String, LearningSubtitleCue> = linkedMapOf(),
    )

    fun configureSource(sourceIdentity: String) {
        if (activeSourceIdentity == sourceIdentity) return
        activeSourceIdentity = sourceIdentity
        selectionGeneration += 1L
        selectedTrackKey = null
        targetTrackKey = null
        configuredTargetLanguage = ""
        mainHandler.removeCallbacks(publishSelectedSnapshot)
        snapshotPending = false
        tracks.clear()
    }

    fun reset() {
        selectionGeneration += 1L
        selectedTrackKey = null
        targetTrackKey = null
        configuredTargetLanguage = ""
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
        val previousKey = selectedTrackKey
        val previousTargetKey = targetTrackKey
        val generation = ++selectionGeneration
        mainHandler.removeCallbacks(publishSelectedSnapshot)
        snapshotPending = false
        val key = format?.let(::trackKey)
        if (key == null || previousKey != key) {
            listOfNotNull(previousKey, previousTargetKey).distinct().forEach { oldKey ->
                tracks[oldKey]?.let { previous ->
                    synchronized(previous.lock) {
                        previous.pendingCues.clear()
                        previous.cues.clear()
                    }
                }
            }
            targetTrackKey = null
            configuredTargetLanguage = ""
        }
        selectedTrackKey = key
        playbackPositionMsProvider = if (key == null) null else playbackPositionMs
        lastLoggedCueCount = 0
        lastLoggedCueEndMs = Long.MIN_VALUE
        if (key == null) return
        val snapshot = tracks[key]?.let { entry ->
            synchronized(entry.lock) {
                entry.pendingCues.clear()
                entry.cues.values.toList()
            }
        }.orEmpty()
        if (snapshot.isNotEmpty()) {
            mainHandler.post {
                if (selectionGeneration == generation && selectedTrackKey == key) {
                    val readyCues = displayableCues(snapshot)
                    if (readyCues.isNotEmpty()) PlayerLearningSubtitleState.updatePartial(readyCues)
                }
            }
        }
    }

    /** Starts target-track discovery without suppressing the already available source subtitles. */
    fun beginTargetTrackLookup(targetLanguage: String) {
        val previousKey = targetTrackKey
        targetTrackKey = null
        previousKey?.let { oldKey ->
            tracks[oldKey]?.let { old ->
                synchronized(old.lock) {
                    old.pendingCues.clear()
                    old.cues.clear()
                }
            }
        }
        configuredTargetLanguage = targetLanguage
    }

    /** Registers only the plugin-configured target track as a non-displayed capture source. */
    fun setTargetTrack(format: Format?, targetLanguage: String) {
        val key = format?.let(::trackKey)
        val previousKey = targetTrackKey
        if (previousKey != key) {
            previousKey?.let { oldKey ->
                tracks[oldKey]?.let { old ->
                    synchronized(old.lock) {
                        old.pendingCues.clear()
                        old.cues.clear()
                    }
                }
            }
        }
        targetTrackKey = key
        configuredTargetLanguage = if (key == null) "" else targetLanguage
        if (key == selectedTrackKey) return

        val sourceKey = selectedTrackKey ?: return
        val sourceEntry = tracks[sourceKey] ?: return
        val sourceSnapshot = synchronized(sourceEntry.lock) {
            sourceEntry.cues.values.toList().also { cues ->
                cues.forEach { cue ->
                    sourceEntry.pendingCues["${cue.startTimeMs}|${cue.endTimeMs}|${cue.text}"] = cue
                }
            }
        }
        if (sourceSnapshot.isNotEmpty()) scheduleSnapshot()
    }

    /**
     * Source subtitles are always displayable. If a target track has actually been selected,
     * attach translations when matching cues are available; never hide the source while waiting.
     */
    private fun displayableCues(sourceCues: List<LearningSubtitleCue>): List<LearningSubtitleCue> =
        translationsFor(sourceCues, configuredTargetLanguage)

    /** Pairs source cues only with the one target track selected from the plugin configuration. */
    fun translationsFor(
        sourceCues: List<LearningSubtitleCue>,
        targetLanguage: String = configuredTargetLanguage,
    ): List<LearningSubtitleCue> {
        if (sourceCues.isEmpty() || targetLanguage.isBlank()) return sourceCues
        val targetKey = targetTrackKey ?: return sourceCues
        val targetEntry = tracks[targetKey] ?: return sourceCues
        val candidates = synchronized(targetEntry.lock) {
            targetEntry.cues.values.sortedBy { it.startTimeMs }
        }
        if (candidates.isEmpty()) return sourceCues
        val wanted = normalizeTrackLanguage(targetLanguage)
        return sourceCues.map { source ->
            if (!source.translationText.isNullOrBlank()) return@map source
            val lowerBound = source.startTimeMs - 1_500L
            val upperBound = source.endTimeMs + 1_500L
            var low = 0
            var high = candidates.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (candidates[middle].startTimeMs < lowerBound) low = middle + 1 else high = middle
            }
            val nearby = buildList {
                var index = low
                while (index < candidates.size && candidates[index].startTimeMs <= upperBound) {
                    add(candidates[index])
                    index++
                }
            }
            val overlapping = nearby.filter { target ->
                minOf(source.endTimeMs, target.endTimeMs) >
                    maxOf(source.startTimeMs, target.startTimeMs)
            }
            val matched = if (overlapping.isNotEmpty()) overlapping else {
                val nearest = nearby.minByOrNull { target ->
                    when {
                        target.endTimeMs < source.startTimeMs -> source.startTimeMs - target.endTimeMs
                        target.startTimeMs > source.endTimeMs -> target.startTimeMs - source.endTimeMs
                        else -> 0L
                    }
                }
                val gap = nearest?.let { target ->
                    when {
                        target.endTimeMs < source.startTimeMs -> source.startTimeMs - target.endTimeMs
                        target.startTimeMs > source.endTimeMs -> target.startTimeMs - source.endTimeMs
                        else -> 0L
                    }
                }
                if (nearest != null && gap != null && gap <= 1_500L) listOf(nearest) else emptyList()
            }
            val joined = matched.map { it.text.trim() }.filter(String::isNotBlank).distinct()
                .joinToString(if (wanted in setOf("zh", "ja", "ko")) "" else " ")
            if (joined.isBlank()) source else source.copy(translationText = joined)
        }
    }

    private fun normalizeTrackLanguage(value: String): String? {
        val tag = value.trim().replace('_', '-').substringBefore('(').trim()
        if (tag.isBlank()) return null
        val primary = tag.substringBefore('-').lowercase()
        if (primary.length == 2) return primary
        if (primary.length == 3) return java.util.Locale.getAvailableLocales().firstOrNull {
            runCatching { it.isO3Language.equals(primary, true) }.getOrDefault(false)
        }?.language?.lowercase() ?: primary
        return java.util.Locale.getAvailableLocales().firstOrNull {
            it.language.isNotBlank() && it.getDisplayLanguage(java.util.Locale.ENGLISH).equals(tag, true)
        }?.language?.lowercase()
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
                tracks.putIfAbsent(key, TrackCues(
                    language = format.language?.takeIf { it.isNotBlank() },
                    label = format.label?.toString()?.takeIf { it.isNotBlank() },
                ))
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
                        if (
                            PlayerSettingsRepository.uiState.value.englishLearningEnabled &&
                            (selectedTrackKey == key || targetTrackKey == key)
                        ) {
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
        val isSourceTrack = selectedTrackKey == key
        val isTargetTrack = targetTrackKey == key
        if (!isSourceTrack && !isTargetTrack) return
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

        val entry = tracks.getOrPut(key) { TrackCues(language = null, label = null) }
        val capturedCues = texts.map { text ->
            LearningSubtitleCue(
                startTimeMs = startMs,
                endTimeMs = endMs,
                text = if (isTargetTrack) {
                    OpenCcSubtitleTextConverter.convert(text, configuredTargetLanguage)
                } else text,
            )
        }
        val cueAdded = synchronized(entry.lock) {
            var added = false
            capturedCues.forEach { cue ->
                val cueKey = "${cue.startTimeMs}|${cue.endTimeMs}|${cue.text}"
                if (!entry.cues.containsKey(cueKey)) {
                    added = true
                    if (isSourceTrack) entry.pendingCues[cueKey] = cue
                }
                entry.cues[cueKey] = cue
            }
            while (entry.cues.size > MAX_CUES_PER_TRACK) {
                val firstKey = entry.cues.keys.firstOrNull() ?: break
                entry.cues.remove(firstKey)
                entry.pendingCues.remove(firstKey)
            }
            added
        }

        if (isTargetTrack) {
            // Target cues stay hidden. Re-publish only source cues in this time neighborhood
            // so a target sample arriving later can complete an already visible source cue.
            val sourceEntry = selectedTrackKey?.let(tracks::get)
            val affected = sourceEntry?.let { source ->
                synchronized(source.lock) {
                    source.cues.values.filter { sourceCue ->
                        sourceCue.endTimeMs >= startMs - 1_500L &&
                            sourceCue.startTimeMs <= endMs + 1_500L
                    }.also { matches ->
                        matches.forEach { cue ->
                            source.pendingCues["${cue.startTimeMs}|${cue.endTimeMs}|${cue.text}"] = cue
                        }
                    }
                }
            }.orEmpty()
            if (affected.isNotEmpty()) scheduleSnapshot()
        } else if (cueAdded) {
            scheduleSnapshot()
        }
    }

    private fun scheduleSnapshot() {
        if (selectedTrackKey == null || snapshotPending) return
        snapshotPending = true
        mainHandler.postDelayed(publishSelectedSnapshot, snapshotIntervalMs)
    }

    private fun trackKey(format: Format): String =
        activeSourceIdentity + "|" + (
            format.id?.takeIf { it.isNotBlank() }
                ?: listOf(format.sampleMimeType, format.language, format.label).joinToString("|")
        )
}
