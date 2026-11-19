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
