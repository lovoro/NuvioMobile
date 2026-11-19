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


    @Test
    fun capturesEmbeddedCueGroupsAsExplicitlyPartialPlaybackSamples() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLiveCapture()

        PlayerLearningSubtitleState.captureLiveCueGroup(listOf("Hello there"), 1_000L)
        assertEquals(LearningSubtitleStatus.PARTIAL, PlayerLearningSubtitleState.status)
        assertEquals("Hello there", PlayerLearningSubtitleState.cues.single().text)
        assertEquals(1_000L, PlayerLearningSubtitleState.cues.single().startTimeMs)

        PlayerLearningSubtitleState.captureLiveCueGroup(emptyList(), 2_400L)
        assertEquals(2_400L, PlayerLearningSubtitleState.cues.single().endTimeMs)

        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun liveCaptureKeepsEarlierCuesWhenLaterCuesArrive() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLiveCapture()

        PlayerLearningSubtitleState.captureLiveCueGroup(listOf("First"), 1_000L)
        PlayerLearningSubtitleState.captureLiveCueGroup(listOf("Second"), 2_000L)

        assertEquals(listOf("First", "Second"), PlayerLearningSubtitleState.cues.map { it.text })
        assertEquals(2_000L, PlayerLearningSubtitleState.cues.first().endTimeMs)
        assertEquals(LearningSubtitleStatus.PARTIAL, PlayerLearningSubtitleState.status)

        PlayerLearningSubtitleState.clear()
    }


    @Test
    fun estimatesWordHighlightAcrossSentenceDuration() {
        val text = "I really like it"
        assertEquals(0..0, findLearningSubtitleWordRange(text, 0L, 4_000L, 0L))
        assertEquals(2..7, findLearningSubtitleWordRange(text, 0L, 4_000L, 2_000L))
        assertEquals(14..15, findLearningSubtitleWordRange(text, 0L, 4_000L, 3_900L))
    }

    @Test
    fun wordHighlightRecognizesWhitespaceTokensAcrossLanguagesAndPunctuation() {
        val text = "Give your Mum a hug. What are you waiting for?"
        assertEquals(0..3, findLearningSubtitleWordRange(text, 0L, 10_000L, 0L))
        assertEquals(5..8, findLearningSubtitleWordRange(text, 0L, 10_000L, 9_900L))
        val multilingual = "Grüße aus Berlin"
        assertEquals(0..4, findLearningSubtitleWordRange(multilingual, 0L, 3_000L, 0L))
    }

    @Test
    fun wordHighlightIgnoresNonEnglishTextAndOutsideCueTime() {
        val text = "你好\nHello world"
        assertEquals(3..7, findLearningSubtitleWordRange(text, 1_000L, 3_000L, 1_000L))
        assertEquals(null, findLearningSubtitleWordRange(text, 1_000L, 3_000L, 3_000L))
    }

    private fun cue(start: Long, end: Long, text: String) =
        LearningSubtitleCue(startTimeMs = start, endTimeMs = end, text = text)
}
