package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class LearningSubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
)

enum class LearningSubtitleStatus {
    NONE,
    LOADING,
    READY,
    PARTIAL,
    UNSUPPORTED,
    ERROR,
}

object PlayerLearningSubtitleState {
    var cues by mutableStateOf<List<LearningSubtitleCue>>(emptyList())
        private set
    private var mutableStatus by mutableStateOf(LearningSubtitleStatus.NONE)
    val status: LearningSubtitleStatus get() = mutableStatus
    private val completedLiveCues = mutableListOf<LearningSubtitleCue>()
    private val activeLiveCues = linkedMapOf<String, Long>()

    /** Starts an explicitly partial capture for embedded tracks whose samples arrive during playback. */
    fun beginLiveCapture() {
        cues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        mutableStatus = LearningSubtitleStatus.LOADING
    }

    /** Captures only cues actually emitted during playback; seeking can leave gaps. */
    fun captureLiveCueGroup(texts: List<String>, positionMs: Long) {
        val timeMs = positionMs.coerceAtLeast(0L)
        val visibleTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val visibleSet = visibleTexts.toSet()
        activeLiveCues.toMap().forEach { (text, startMs) ->
            if (text !in visibleSet) {
                completedLiveCues += LearningSubtitleCue(startMs, timeMs.coerceAtLeast(startMs + 1L), text)
                activeLiveCues.remove(text)
            }
        }
        visibleTexts.forEach { text -> if (text !in activeLiveCues) activeLiveCues[text] = timeMs }
        val activeSnapshot = activeLiveCues.map { (text, startMs) ->
            LearningSubtitleCue(startMs, maxOf(timeMs + 1_500L, startMs + 1_500L), text)
        }
        cues = (completedLiveCues + activeSnapshot)
            .distinctBy { it.startTimeMs to it.text }
            .sortedBy { it.startTimeMs }
        if (cues.isNotEmpty()) mutableStatus = LearningSubtitleStatus.PARTIAL
    }

    fun setStatus(newStatus: LearningSubtitleStatus) {
        mutableStatus = newStatus
        if (newStatus != LearningSubtitleStatus.READY) cues = emptyList()
    }

    fun beginLoading() {
        cues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        mutableStatus = LearningSubtitleStatus.LOADING
    }

    fun markUnsupported() = setStatus(LearningSubtitleStatus.UNSUPPORTED)

    fun markError() = setStatus(LearningSubtitleStatus.ERROR)

    /** Updates the visible list with cues discovered by embedded-track extraction. */
    fun updatePartial(newCues: List<LearningSubtitleCue>) {
        cues = newCues
            .asSequence()
            .filter { it.text.isNotBlank() && it.endTimeMs > it.startTimeMs }
            .distinctBy { it.startTimeMs to it.text }
            .sortedBy { it.startTimeMs }
            .toList()
        mutableStatus = if (cues.isEmpty()) LearningSubtitleStatus.LOADING else LearningSubtitleStatus.PARTIAL
    }

    fun update(newCues: List<LearningSubtitleCue>) {
        completedLiveCues.clear()
        activeLiveCues.clear()
        cues = newCues
            .asSequence()
            .filter { it.text.isNotBlank() && it.endTimeMs > it.startTimeMs }
            .sortedBy { it.startTimeMs }
            .toList()
        mutableStatus = if (cues.isEmpty()) LearningSubtitleStatus.UNSUPPORTED else LearningSubtitleStatus.READY
    }

    fun clear() {
        cues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        mutableStatus = LearningSubtitleStatus.NONE
    }
}

/**
 * Builds a prefix maximum of cue end times. Subtitle cues are ordered by start time, but their
 * end times are not necessarily ordered: overlapping cues can outlive cues that start later.
 * The prefix maximum lets the active-cue lookup stop as soon as no earlier cue can still be active.
 */
internal fun buildLearningSubtitlePrefixMaxEndMs(
    cues: List<LearningSubtitleCue>,
): List<Long> {
    if (cues.isEmpty()) return emptyList()
    val prefixMaxEndMs = ArrayList<Long>(cues.size)
    var maxEndMs = Long.MIN_VALUE
    cues.forEach { cue ->
        maxEndMs = maxOf(maxEndMs, cue.endTimeMs)
        prefixMaxEndMs.add(maxEndMs)
    }
    return prefixMaxEndMs
}

/**
 * Returns the most recently started cue active at [positionMs].
 *
 * [cues] must be sorted by startTimeMs and [prefixMaxEndMs] must be built for that same list.
 * Lookup is O(log n + k), where k is the number of overlapping candidates inspected.
 */
internal fun findLearningSubtitleIndex(
    cues: List<LearningSubtitleCue>,
    prefixMaxEndMs: List<Long>,
    positionMs: Long,
): Int {
    if (cues.isEmpty() || prefixMaxEndMs.size != cues.size) return -1

    var low = 0
    var high = cues.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (cues[middle].startTimeMs <= positionMs) {
            candidate = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    if (candidate < 0) return -1

    for (index in candidate downTo 0) {
        if (prefixMaxEndMs[index] <= positionMs) break
        val cue = cues[index]
        if (cue.endTimeMs > positionMs) return index
    }
    return -1
}

/** Convenience overload for callers that do not repeatedly query the same cue list. */
internal fun findLearningSubtitleIndex(
    cues: List<LearningSubtitleCue>,
    positionMs: Long,
): Int = findLearningSubtitleIndex(
    cues = cues,
    prefixMaxEndMs = buildLearningSubtitlePrefixMaxEndMs(cues),
    positionMs = positionMs,
)

/**
 * Estimates the active whitespace-delimited word from a cue's overall timing.
 *
 * Most SRT/VTT subtitles provide timing for the whole sentence, not individual words.
 * Word durations are therefore distributed by word length; this is a visual learning aid,
 * not source-provided word-level timing.
 */
internal fun findLearningSubtitleWordRange(
    text: String,
    startTimeMs: Long,
    endTimeMs: Long,
    positionMs: Long,
): IntRange? {
    if (endTimeMs <= startTimeMs || positionMs < startTimeMs || positionMs >= endTimeMs) return null
    // Unicode letters/marks/numbers keep this usable for Latin, Cyrillic, Arabic, etc.
    // Scripts without spaces (for example Japanese or Chinese) remain a single token:
    // we deliberately avoid pretending to know word boundaries that the subtitle lacks.
    val words = Regex("\\S+").findAll(text).toList()
    if (words.isEmpty()) return null

    val totalWeight = words.sumOf { it.value.length }.toFloat()
    if (totalWeight <= 0f) return null
    val progress = ((positionMs - startTimeMs).toFloat() / (endTimeMs - startTimeMs).toFloat())
        .coerceIn(0f, 0.999999f)
    val targetWeight = progress * totalWeight
    var consumedWeight = 0f
    words.forEach { word ->
        val nextWeight = consumedWeight + word.value.length
        if (targetWeight < nextWeight) return word.range
        consumedWeight = nextWeight
    }
    return words.last().range
}


/**
 * Returns a useful list target for [positionMs], even when no subtitle cue is active.
 * During gaps, the closest neighboring cue is selected (preferring the upcoming cue on ties).
 */
internal fun findLearningSubtitleScrollIndex(
    cues: List<LearningSubtitleCue>,
    positionMs: Long,
): Int {
    if (cues.isEmpty()) return -1
    val activeIndex = findLearningSubtitleIndex(cues, positionMs)
    if (activeIndex >= 0) return activeIndex

    var low = 0
    var high = cues.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (cues[middle].startTimeMs <= positionMs) low = middle + 1 else high = middle
    }
    val nextIndex = low
    if (nextIndex == 0) return 0
    if (nextIndex >= cues.size) return cues.lastIndex

    val previous = cues[nextIndex - 1]
    val next = cues[nextIndex]
    val distanceFromPrevious = (positionMs - previous.endTimeMs).coerceAtLeast(0L)
    val distanceToNext = (next.startTimeMs - positionMs).coerceAtLeast(0L)
    return if (distanceToNext <= distanceFromPrevious) nextIndex else nextIndex - 1
}
