package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleCuePairingTest {
    @Test
    fun eachTargetCueIsAssignedToOnlyOneSourceCue() {
        val source = listOf(
            cue(33_000L, 34_000L, "Beep, beep!"),
            cue(34_000L, 36_000L, "Anyone need a taxi?"),
            cue(36_000L, 38_000L, "Taxi!"),
        )
        val target = listOf(
            cue(34_000L, 36_000L, "有人要乘搭的士吗？"),
            cue(36_000L, 38_000L, "的士！"),
        )

        val pairs = pairSubtitleCuesOneToOne(source, target)

        assertEquals(
            listOf(
                "Anyone need a taxi?" to "有人要乘搭的士吗？",
                "Taxi!" to "的士！",
            ),
            pairs.map { it.sourceCue.text to it.targetCue.text },
        )
    }

    @Test
    fun prefersTheSourceWithTheLongestOverlapWhenOneTargetOverlapsSeveralSources() {
        val source = listOf(
            cue(1_000L, 3_000L, "short overlap"),
            cue(2_000L, 6_000L, "long overlap"),
        )
        val target = listOf(cue(2_000L, 5_000L, "译文"))

        val pairs = pairSubtitleCuesOneToOne(source, target)

        assertEquals(1, pairs.size)
        assertEquals("long overlap", pairs.single().sourceCue.text)
    }

    @Test
    fun nearestFallbackAlsoConsumesEachTargetOnlyOnce() {
        val source = listOf(
            cue(10_000L, 11_000L, "first"),
            cue(12_000L, 13_000L, "second"),
        )
        val target = listOf(cue(11_200L, 11_800L, "target"))

        val pairs = pairSubtitleCuesOneToOne(source, target)

        assertEquals(1, pairs.size)
        assertEquals("first", pairs.single().sourceCue.text)
    }

    @Test
    fun doesNotUseTheCueThatOnlyTouchesAtAnIntervalBoundary() {
        val source = listOf(cue(33_000L, 34_000L, "Beep, beep!"))
        val target = listOf(cue(34_000L, 36_000L, "有人要乘搭的士吗？"))

        assertEquals(emptyList(), pairSubtitleCuesOneToOne(source, target))
    }

    @Test
    fun streamingPairingWaitsForAnActualOverlapInsteadOfNearestFallback() {
        val source = listOf(cue(33_000L, 34_000L, "Beep, beep!"))
        val target = listOf(cue(34_100L, 36_000L, "有人要乘搭的士吗？"))

        assertEquals(
            emptyList(),
            pairSubtitleCuesOneToOne(source, target, allowNearestFallback = false),
        )
    }

    private fun cue(start: Long, end: Long, text: String) =
        LearningSubtitleCue(startTimeMs = start, endTimeMs = end, text = text)
}
