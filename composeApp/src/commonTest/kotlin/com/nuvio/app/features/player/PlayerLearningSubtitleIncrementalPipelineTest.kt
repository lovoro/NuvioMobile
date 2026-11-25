package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for the required streaming behavior:
 * parsed cues and each completed translation batch must become visible
 * without waiting for the rest of the subtitle track.
 */
class PlayerLearningSubtitleIncrementalPipelineTest {

    @Test
    fun firstTranslatedBatchIsVisibleBeforeSecondBatchIsParsedOrTranslated() {
        PlayerLearningSubtitleState.clear()
        try {
            PlayerLearningSubtitleState.beginLoading("Bluey S01E33")

            val firstBatch = listOf(
                LearningSubtitleCue(1_000L, 2_000L, "Hello, Bluey!"),
                LearningSubtitleCue(2_100L, 3_000L, "Let's jump!"),
            )

            // Simulate the parser emitting its first partial batch.
            PlayerLearningSubtitleState.appendPartialCues(firstBatch)
            assertEquals(2, PlayerLearningSubtitleState.cues.size)
            assertEquals(null, PlayerLearningSubtitleState.cues.first().translationText)

            // Simulate the first HTTP response and its onBatchTranslated callback.
            val firstTranslatedBatch = firstBatch.mapIndexed { index, cue ->
                cue.copy(translationText = listOf("你好，布鲁伊！", "我们跳起来！")[index])
            }
            PlayerLearningSubtitleState.mergePrefetched(firstTranslatedBatch)

            // This assertion is deliberately before the second parser batch exists.
            assertEquals(2, PlayerLearningSubtitleState.cues.size)
            assertEquals("你好，布鲁伊！", PlayerLearningSubtitleState.cues.first().translationText)
            assertEquals("Hello, Bluey!\n你好，布鲁伊！", PlayerLearningSubtitleState.cues.first().displayText)

            val secondBatch = listOf(
                LearningSubtitleCue(3_100L, 4_000L, "Ready?"),
            )
            // The parser continues while the already translated first batch stays visible.
            PlayerLearningSubtitleState.appendPartialCues(secondBatch)
            assertEquals(3, PlayerLearningSubtitleState.cues.size)
            assertEquals("你好，布鲁伊！", PlayerLearningSubtitleState.cues.first().translationText)
            assertNull(PlayerLearningSubtitleState.cues.last().translationText)

            // The second HTTP response publishes independently.
            PlayerLearningSubtitleState.mergePrefetched(
                secondBatch.map { it.copy(translationText = "准备好了吗？") },
            )
            assertEquals("准备好了吗？", PlayerLearningSubtitleState.cues.last().translationText)
            assertEquals(
                listOf("你好，布鲁伊！", "我们跳起来！", "准备好了吗？"),
                PlayerLearningSubtitleState.cues.mapNotNull { it.translationText },
            )
        } finally {
            PlayerLearningSubtitleState.clear()
        }
    }
}
