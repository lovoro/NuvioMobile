package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class LearningSubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
)

object PlayerLearningSubtitleState {
    var cues by mutableStateOf<List<LearningSubtitleCue>>(emptyList())
        private set

    fun update(newCues: List<LearningSubtitleCue>) {
        cues = newCues
            .asSequence()
            .filter { it.text.isNotBlank() && it.endTimeMs > it.startTimeMs }
            .sortedBy { it.startTimeMs }
            .toList()
    }

    fun clear() {
        cues = emptyList()
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
