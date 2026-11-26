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
}
