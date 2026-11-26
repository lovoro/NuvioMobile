package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearningSubtitleMultilineHighlightTest {
    @Test
    fun everyWordInTheReportedMultilineCueGetsAHighlightWindow() {
        val text = "I'm\ngonna put\nsome music on.\nNo, cam,\nI have to go to work."
        val expectedWords = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")
            .findAll(text)
            .map { it.range }
            .toList()
        val observed = mutableSetOf<IntRange>()

        // Sample more frequently than a typical playback-position update.
        for (positionMs in 0L until 8_000L step 10L) {
            findLearningSubtitleWordRange(text, 0L, 8_000L, positionMs)?.let(observed::add)
        }

        assertEquals(expectedWords.toSet(), observed)
    }

    @Test
    fun shortWordsKeepAVisibleHighlightWindowInDenseMultilineCues() {
        val text = "I'm gonna put some music on. No, cam, I have to go to work."
        val words = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")
            .findAll(text)
            .map { it.range }
            .toList()
        val firstSeen = mutableMapOf<IntRange, Long>()
        val lastSeen = mutableMapOf<IntRange, Long>()

        for (positionMs in 0L until 4_000L) {
            val range = findLearningSubtitleWordRange(text, 0L, 4_000L, positionMs) ?: continue
            firstSeen.putIfAbsent(range, positionMs)
            lastSeen[range] = positionMs
        }

        assertEquals(words.toSet(), firstSeen.keys)
        words.forEach { range ->
            assertTrue((lastSeen.getValue(range) - firstSeen.getValue(range)) >= 119L)
        }
    }
}
