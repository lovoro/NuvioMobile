package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLearningSubtitlesTest {

    @Test
    fun findsCueAtItsStartAndTreatsEndAsExclusive() {
        val cues = listOf(
            cue(1_000L, 3_000L, "first"),
            cue(3_000L, 5_000L, "second"),
        )
        val prefix = buildLearningSubtitlePrefixMaxEndMs(cues)

        assertEquals(0, findLearningSubtitleIndex(cues, prefix, 1_000L))
        assertEquals(1, findLearningSubtitleIndex(cues, prefix, 3_000L))
        assertEquals(-1, findLearningSubtitleIndex(cues, prefix, 5_000L))
    }

    @Test
    fun keepsEarlierLongCueActiveAfterShorterOverlappingCueEnds() {
        val cues = listOf(
            cue(0L, 10_000L, "long cue"),
            cue(5_000L, 6_000L, "short overlapping cue"),
            cue(11_000L, 13_000L, "later cue"),
        )
        val prefix = buildLearningSubtitlePrefixMaxEndMs(cues)

        // The short cue has ended, but the earlier long cue is still on screen.
        assertEquals(0, findLearningSubtitleIndex(cues, prefix, 7_000L))
    }

    @Test
    fun prefersTheMostRecentlyStartedCueWhenSeveralAreActive() {
        val cues = listOf(
            cue(0L, 10_000L, "first"),
            cue(2_000L, 8_000L, "second"),
            cue(4_000L, 7_000L, "third"),
        )
        val prefix = buildLearningSubtitlePrefixMaxEndMs(cues)

        assertEquals(2, findLearningSubtitleIndex(cues, prefix, 5_000L))
        assertEquals(1, findLearningSubtitleIndex(cues, prefix, 7_500L))
        assertEquals(0, findLearningSubtitleIndex(cues, prefix, 9_000L))
    }

    @Test
    fun returnsNoCueBeforeFirstCueAndBetweenNonOverlappingCues() {
        val cues = listOf(
            cue(1_000L, 2_000L, "first"),
            cue(3_000L, 4_000L, "second"),
        )
        val prefix = buildLearningSubtitlePrefixMaxEndMs(cues)

        assertEquals(-1, findLearningSubtitleIndex(cues, prefix, 500L))
        assertEquals(-1, findLearningSubtitleIndex(cues, prefix, 2_500L))
    }

    @Test
    fun handlesEmptyCueLists() {
        assertEquals(emptyList(), buildLearningSubtitlePrefixMaxEndMs(emptyList()))
        assertEquals(-1, findLearningSubtitleIndex(emptyList(), emptyList(), 0L))
    }

    private fun cue(start: Long, end: Long, text: String) =
        LearningSubtitleCue(startTimeMs = start, endTimeMs = end, text = text)
}
