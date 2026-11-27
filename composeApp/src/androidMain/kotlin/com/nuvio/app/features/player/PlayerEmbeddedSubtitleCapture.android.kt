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
    private val parserEntryLogged = ConcurrentHashMap.newKeySet<String>()
    private val parserSampleLogged = ConcurrentHashMap.newKeySet<String>()
    private val cueCaptureLogged = ConcurrentHashMap.newKeySet<String>()
    private val conversionDecisionLogged = ConcurrentHashMap.newKeySet<String>()
    private val activeLiveSourceCues = LinkedHashMap<String, LearningSubtitleCue>()
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
        var parserCapturedCueCount: Int = 0,
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
        parserEntryLogged.clear()
        parserSampleLogged.clear()
        cueCaptureLogged.clear()
        conversionDecisionLogged.clear()
        activeLiveSourceCues.clear()
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
        parserEntryLogged.clear()
        parserSampleLogged.clear()
        cueCaptureLogged.clear()
        conversionDecisionLogged.clear()
        activeLiveSourceCues.clear()
    }

    fun hasSelectedTrackCues(): Boolean {
        val key = selectedTrackKey ?: return false
        val entry = tracks[key] ?: return false
        // TextOutput fallback cues must not disable themselves. Only a cue parsed from the
        // selected embedded track proves that the timed parser path is actually working.
        return synchronized(entry.lock) { entry.parserCapturedCueCount > 0 }
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
        if (previousKey != key) activeLiveSourceCues.clear()
        if (key != null && format != null) {
            tracks.putIfAbsent(
                key,
                TrackCues(
                    language = format.language?.takeIf { it.isNotBlank() },
                    label = format.label?.toString()?.takeIf { it.isNotBlank() },
                ),
            )
        }
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
            targetEntry.cues.values.sortedBy { it.startTimeMs / 2L + it.endTimeMs / 2L }
        }
        if (candidates.isEmpty()) return sourceCues
        val usedTargets = HashSet<String>()
        return sourceCues.map { source ->
            if (!source.translationText.isNullOrBlank()) return@map source

            // Match by cue midpoint, not maximum overlap. A long inferred live cue can
            // overlap several unrelated target cues; overlap-first matching attaches
            // dialogue from the wrong scene. Each target cue is consumed at most once
            // within this publication batch.
            val sourceCenter = source.startTimeMs / 2L + source.endTimeMs / 2L
            val lowerBound = sourceCenter - 1_500L
            val upperBound = sourceCenter + 1_500L
            var low = 0
            var high = candidates.size
            while (low < high) {
                val middle = (low + high) ushr 1
                val targetCenter = candidates[middle].startTimeMs / 2L +
                    candidates[middle].endTimeMs / 2L
                if (targetCenter < lowerBound) low = middle + 1 else high = middle
            }
            var best: LearningSubtitleCue? = null
            var bestDistance = Long.MAX_VALUE
            var index = low
            while (index < candidates.size) {
                val target = candidates[index]
                val targetCenter = target.startTimeMs / 2L + target.endTimeMs / 2L
                if (targetCenter > upperBound) break
                val targetKey = "${target.startTimeMs}|${target.endTimeMs}|${target.text}"
                if (targetKey !in usedTargets) {
                    val distance = kotlin.math.abs(sourceCenter - targetCenter)
                    if (distance < bestDistance) {
                        best = target
                        bestDistance = distance
                    }
                }
                index++
            }
            val matched = best?.takeIf { bestDistance <= 1_500L }
            if (matched == null) {
                source
            } else {
                usedTargets += "${matched.startTimeMs}|${matched.endTimeMs}|${matched.text}"
                source.copy(translationText = matched.text.trim())
            }
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


    fun createParserFactory(
        playbackParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
        captureParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
    ): SubtitleParser.Factory {
        return object : SubtitleParser.Factory {
            override fun supportsFormat(format: Format): Boolean =
                playbackParserFactory.supportsFormat(format) ||
                    captureParserFactory.supportsFormat(format)

            override fun getCueReplacementBehavior(format: Format): Int =
                if (playbackParserFactory.supportsFormat(format)) {
                    playbackParserFactory.getCueReplacementBehavior(format)
                } else {
                    captureParserFactory.getCueReplacementBehavior(format)
                }

            override fun create(format: Format): SubtitleParser {
                val playbackFactory = if (playbackParserFactory.supportsFormat(format)) {
                    playbackParserFactory
                } else {
                    captureParserFactory
                }
                val captureFactory = if (captureParserFactory.supportsFormat(format)) {
                    captureParserFactory
                } else {
                    playbackFactory
                }
                return wrapParser(
                    format = format,
                    parser = playbackFactory.create(format),
                    captureParser = captureFactory.create(format),
                )
            }
        }
    }

    /**
     * Adds learning capture around the parser used by the active playback pipeline.
     * This also lets the libass pipeline retain its ASS parser while learning capture uses
     * Media3's default parser as an independent, all-cues reader.
     */
    fun wrapParser(
        format: Format,
        parser: SubtitleParser,
        captureParser: SubtitleParser,
    ): SubtitleParser {
        val key = trackKey(format)
        tracks.putIfAbsent(
            key,
            TrackCues(
                language = format.language?.takeIf { it.isNotBlank() },
                label = format.label?.toString()?.takeIf { it.isNotBlank() },
            ),
        )
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
                if (parserSampleLogged.add(key)) {
                    Log.i(
                        TAG,
                        "subtitle parser sample received: track=${format.id} " +
                            "mime=${format.sampleMimeType} language=${format.language} " +
                            "label=${format.label}",
                    )
                }
                val isSourceTrack = selectedTrackKey == key
                val isTargetTrack = targetTrackKey == key
                if (
                    PlayerSettingsRepository.uiState.value.englishLearningEnabled &&
                    (isSourceTrack || isTargetTrack)
                ) {
                    val isTargetOnly = isTargetTrack && !isSourceTrack
                    val captureOptions = if (outputOptions.outputAllCues) {
                        outputOptions
                    } else {
                        SubtitleParser.OutputOptions.allCues()
                    }
                    if (parserEntryLogged.add(key)) {
                        Log.i(
                            TAG,
                            "subtitle parser capture active: track=${format.id} " +
                                "role=${if (isTargetOnly) "target" else "source"} " +
                                "language=${format.language} label=${format.label}",
                        )
                    }
                    captureParser.parse(
                        data,
                        offset,
                        length,
                        captureOptions,
                        Consumer { parsed -> capture(key, parsed) },
                    )
                    // Keep the target track as a capture-only source. Forwarding its cues
                    // would render a second subtitle line over the selected source track.
                    if (isTargetOnly) return
                }
                parser.parse(data, offset, length, outputOptions, output)
            }

            override fun reset() {
                parser.reset()
                captureParser.reset()
            }
        }
    }

    /**
     * Feeds TextOutput-only source cues into the same timeline used by parser-captured target
     * cues. Their end time is inferred from visibility changes, then target-track arrival can
     * re-publish the same source interval with its matched translation.
     */
    fun captureLiveSourceCueGroup(texts: List<String>, positionMs: Long) {
        if (!PlayerSettingsRepository.uiState.value.englishLearningEnabled) return
        val key = selectedTrackKey ?: return
        val timeMs = positionMs.coerceAtLeast(0L)
        val visibleTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val entry = tracks[key] ?: return
        val changed = synchronized(entry.lock) {
            var updated = false
            val visibleSet = visibleTexts.toSet()
            activeLiveSourceCues.toMap().forEach { (text, activeCue) ->
                if (text !in visibleSet) {
                    val activeKey = "${activeCue.startTimeMs}|${activeCue.endTimeMs}|${activeCue.text}"
                    entry.cues.remove(activeKey)
                    entry.pendingCues.remove(activeKey)
                    val completed = activeCue.copy(endTimeMs = timeMs.coerceAtLeast(activeCue.startTimeMs + 1L))
                    val completedKey = "${completed.startTimeMs}|${completed.endTimeMs}|${completed.text}"
                    entry.cues[completedKey] = completed
                    entry.pendingCues[completedKey] = completed
                    activeLiveSourceCues.remove(text)
                    updated = true
                }
            }
            visibleTexts.forEach { text ->
                val previous = activeLiveSourceCues[text]
                val startMs = previous?.startTimeMs ?: timeMs
                val next = LearningSubtitleCue(
                    startTimeMs = startMs,
                    endTimeMs = maxOf(timeMs + 1_500L, startMs + 1_500L),
                    text = text,
                )
                if (previous != next) {
                    if (previous != null) {
                        val previousKey = "${previous.startTimeMs}|${previous.endTimeMs}|${previous.text}"
                        entry.cues.remove(previousKey)
                        entry.pendingCues.remove(previousKey)
                    }
                    val nextKey = "${next.startTimeMs}|${next.endTimeMs}|${next.text}"
                    entry.cues[nextKey] = next
                    entry.pendingCues[nextKey] = next
                    activeLiveSourceCues[text] = next
                    updated = true
                }
            }
            while (entry.cues.size > MAX_CUES_PER_TRACK) {
                val firstKey = entry.cues.keys.firstOrNull() ?: break
                entry.cues.remove(firstKey)
                entry.pendingCues.remove(firstKey)
            }
            updated
        }
        if (changed) {
            val snapshot = synchronized(entry.lock) { entry.cues.values.sortedBy { it.startTimeMs } }
            val ready = displayableCues(snapshot)
            if (ready.isNotEmpty()) PlayerLearningSubtitleState.updatePartial(ready)
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
        if (cueCaptureLogged.add(key)) {
            Log.i(
                TAG,
                "subtitle cues captured: role=${if (isTargetTrack) "target" else "source"} " +
                    "track=$key cueCount=${texts.size}",
            )
        }

        val startMs = startUs / 1_000L
        val endMs = endUs / 1_000L
        if (startMs < 0L || endMs <= startMs) return

        val entry = tracks.getOrPut(key) { TrackCues(language = null, label = null) }
        val capturedCues = texts.map { text ->
            LearningSubtitleCue(
                startTimeMs = startMs,
                endTimeMs = endMs,
                text = if (isTargetTrack) {
                    val track = tracks[key]
                    val converted = OpenCcSubtitleTextConverter.toSimplifiedIfTraditional(
                        text = text,
                        trackLanguage = track?.language,
                        trackLabel = track?.label,
                    )
                    if (conversionDecisionLogged.add(key)) {
                        Log.i(
                            TAG,
                            "target subtitle script: language=${track?.language} label=${track?.label} " +
                                "traditionalDetected=${OpenCcSubtitleTextConverter.isTraditionalTrack(text, track?.language, track?.label)} " +
                                "converted=${converted != text}",
                        )
                    }
                    converted
                } else text,
            )
        }
        val cueAdded = synchronized(entry.lock) {
            if (isSourceTrack) entry.parserCapturedCueCount += capturedCues.size
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
