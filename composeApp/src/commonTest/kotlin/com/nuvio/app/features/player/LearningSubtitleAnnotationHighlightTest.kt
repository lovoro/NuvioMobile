package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class LearningSubtitleAnnotationHighlightTest {

    @Test
    fun skipsLeadingAndTrailingAnnotationsWhenSpokenWordsExist() {
        val text = "[chuckles] Okay. [laughs]"
        assertEquals(11..14, findLearningSubtitleWordRange(text, 0L, 1_000L, 0L))
        assertEquals(11..14, findLearningSubtitleWordRange(text, 0L, 1_000L, 999L))
    }

    @Test
    fun skipsAnnotationsAtBothEndsAndInTheMiddle() {
        val text = "[sighs] I [chuckles] really mean it. [laughs]"
        assertEquals(text.indexOf("I")..text.indexOf("I"), findLearningSubtitleWordRange(text, 0L, 1_000L, 0L))
        assertEquals(
            text.indexOf("it")..text.indexOf("it") + 1,
            findLearningSubtitleWordRange(text, 0L, 1_000L, 999L),
        )
    }

    @Test
    fun keepsAStandaloneAnnotationHighlightable() {
        val text = "[chuckles]"
        assertEquals(1..8, findLearningSubtitleWordRange(text, 0L, 1_000L, 0L))
    }

    @Test
    fun doesNotTreatBracketedSentenceTextAsAnAnnotation() {
        val text = "[I don't know] Okay."
        assertEquals(1..1, findLearningSubtitleWordRange(text, 0L, 1_000L, 0L))
    }

    @Test
    fun skipsHalfWidthAndFullWidthParenthesisAnnotations() {
        val halfWidth = "(chuckles) Okay."
        assertEquals(
            halfWidth.indexOf("Okay")..halfWidth.indexOf("Okay") + 3,
            findLearningSubtitleWordRange(halfWidth, 0L, 1_000L, 0L),
        )
        val fullWidth = "（laughing）Hello."
        assertEquals(
            fullWidth.indexOf("Hello")..fullWidth.indexOf("Hello") + 4,
            findLearningSubtitleWordRange(fullWidth, 0L, 1_000L, 0L),
        )
    }

    @Test
    fun skipsParenthesisAnnotationsAtBothEndsAndInTheMiddle() {
        val text = "(sighs) I (chuckles) really mean it. （laughs）"
        assertEquals(
            text.indexOf("I")..text.indexOf("I"),
            findLearningSubtitleWordRange(text, 0L, 1_000L, 0L),
        )
        assertEquals(
            text.indexOf("it")..text.indexOf("it") + 1,
            findLearningSubtitleWordRange(text, 0L, 1_000L, 999L),
        )
    }

    @Test
    fun keepsStandaloneParenthesisAnnotationsHighlightable() {
        assertEquals(1..8, findLearningSubtitleWordRange("(chuckles)", 0L, 1_000L, 0L))
        assertEquals(1..7, findLearningSubtitleWordRange("（chuckles）", 0L, 1_000L, 0L))
    }

    @Test
    fun doesNotTreatParenthesizedSentenceAsAnAnnotation() {
        val text = "(I don't know) Okay."
        assertEquals(1..1, findLearningSubtitleWordRange(text, 0L, 1_000L, 0L))
    }

}
