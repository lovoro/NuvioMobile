package com.nuvio.app.features.player

internal data class SubtitleCuePair(
    val sourceCue: LearningSubtitleCue,
    val targetCue: LearningSubtitleCue,
)

/**
 * Matches two subtitle tracks one-to-one. A target cue is never copied onto multiple source
 * cues. Real interval overlaps take precedence; only targets with no overlap may use the
 * small timestamp-drift fallback.
 */
internal fun pairSubtitleCuesOneToOne(
    sourceCues: List<LearningSubtitleCue>,
    targetCues: List<LearningSubtitleCue>,
    nearestToleranceMs: Long = 1_500L,
    allowNearestFallback: Boolean = true,
): List<SubtitleCuePair> {
    if (sourceCues.isEmpty() || targetCues.isEmpty()) return emptyList()

    data class Candidate(
        val sourceIndex: Int,
        val targetIndex: Int,
        val overlapMs: Long,
        val gapMs: Long,
        val centerDistanceMs: Long,
    )

    val candidates = mutableListOf<Candidate>()
    targetCues.forEachIndexed { targetIndex, target ->
        val overlappingSources = sourceCues.mapIndexedNotNull { sourceIndex, source ->
            val overlap = minOf(source.endTimeMs, target.endTimeMs) -
                maxOf(source.startTimeMs, target.startTimeMs)
            if (overlap > 0L) sourceIndex to overlap else null
        }
        if (overlappingSources.isNotEmpty()) {
            overlappingSources.forEach { (sourceIndex, overlap) ->
                val source = sourceCues[sourceIndex]
                val sourceCenter = source.startTimeMs + (source.endTimeMs - source.startTimeMs) / 2L
                val targetCenter = target.startTimeMs + (target.endTimeMs - target.startTimeMs) / 2L
                candidates += Candidate(
                    sourceIndex = sourceIndex,
                    targetIndex = targetIndex,
                    overlapMs = overlap,
                    gapMs = 0L,
                    centerDistanceMs = kotlin.math.abs(sourceCenter - targetCenter),
                )
            }
        } else if (allowNearestFallback) {
            val nearest = sourceCues.mapIndexed { sourceIndex, source ->
                val gap = when {
                    target.endTimeMs <= source.startTimeMs -> source.startTimeMs - target.endTimeMs
                    target.startTimeMs >= source.endTimeMs -> target.startTimeMs - source.endTimeMs
                    else -> 0L
                }
                val sourceCenter = source.startTimeMs + (source.endTimeMs - source.startTimeMs) / 2L
                val targetCenter = target.startTimeMs + (target.endTimeMs - target.startTimeMs) / 2L
                Candidate(
                    sourceIndex = sourceIndex,
                    targetIndex = targetIndex,
                    overlapMs = 0L,
                    gapMs = gap,
                    centerDistanceMs = kotlin.math.abs(sourceCenter - targetCenter),
                )
            }.minWithOrNull(
                compareBy<Candidate> { it.gapMs }
                    .thenBy { it.centerDistanceMs }
                    .thenBy { it.sourceIndex },
            )
            // A zero-gap boundary means the cues merely touch; do not attach one
            // subtitle to the cue immediately before or after it.
            if (nearest != null && nearest.gapMs in 1L..nearestToleranceMs) candidates += nearest
        }
    }

    val orderedCandidates = candidates.sortedWith(
        compareByDescending<Candidate> { it.overlapMs > 0L }
            .thenByDescending { it.overlapMs }
            .thenBy { it.gapMs }
            .thenBy { it.centerDistanceMs }
            .thenBy { it.sourceIndex }
            .thenBy { it.targetIndex },
    )
    val usedSources = mutableSetOf<Int>()
    val usedTargets = mutableSetOf<Int>()
    val pairs = mutableListOf<SubtitleCuePair>()
    orderedCandidates.forEach { candidate ->
        if (candidate.sourceIndex !in usedSources && candidate.targetIndex !in usedTargets) {
            usedSources += candidate.sourceIndex
            usedTargets += candidate.targetIndex
            pairs += SubtitleCuePair(
                sourceCue = sourceCues[candidate.sourceIndex],
                targetCue = targetCues[candidate.targetIndex],
            )
        }
    }
    return pairs.sortedBy { it.sourceCue.startTimeMs }
}
