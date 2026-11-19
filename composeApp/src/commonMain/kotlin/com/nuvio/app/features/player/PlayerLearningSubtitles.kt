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
    UNSUPPORTED,
    ERROR,
}

object PlayerLearningSubtitleState {
    var cues by mutableStateOf<List<LearningSubtitleCue>>(emptyList())
        private set
    var status by mutableStateOf(LearningSubtitleStatus.NONE)
        private set

    fun setStatus(newStatus: LearningSubtitleStatus) {
        status = newStatus
        if (newStatus != LearningSubtitleStatus.READY) cues = emptyList()
    }

    fun beginLoading() {
        cues = emptyList()
        status = LearningSubtitleStatus.LOADING
    }

    fun markUnsupported() = setStatus(LearningSubtitleStatus.UNSUPPORTED)

    fun markError() = setStatus(LearningSubtitleStatus.ERROR)

    fun update(newCues: List<LearningSubtitleCue>) {
        cues = newCues
            .asSequence()
            .filter { it.text.isNotBlank() && it.endTimeMs > it.startTimeMs }
            .sortedBy { it.startTimeMs }
            .toList()
        status = if (cues.isEmpty()) LearningSubtitleStatus.UNSUPPORTED else LearningSubtitleStatus.READY
    }

    fun clear() {
        cues = emptyList()
        status = LearningSubtitleStatus.NONE
    }
}

internal fun findLearningSubtitleIndex(
    cues: List<LearningSubtitleCue>,
    positionMs: Long,
): Int {
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
        if (cues[index].startTimeMs <= positionMs && cues[index].endTimeMs > positionMs) {
            return index
        }
        if (index < candidate && cues[index].endTimeMs <= positionMs) break
    }
    return -1
}
