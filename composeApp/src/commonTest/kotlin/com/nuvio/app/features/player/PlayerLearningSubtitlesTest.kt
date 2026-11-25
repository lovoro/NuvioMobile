package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLearningSubtitlesTest {

    @Test
    fun stripsAssOverrideTagsButPreservesSubtitleTextAndMusicSymbols() {
        assertEquals(
            "♪ Hello, world! ♪",
            normalizeLearningSubtitleText("""{\an8}♪ Hello, world! ♪{\c&HFFFFFF&}"""),
        )
        assertEquals(
            "First line\nSecond line",
            normalizeLearningSubtitleText("""{\pos(100,200)}First line\NSecond line"""),
        )
    }

    @Test
    fun displayTextAlwaysRemovesAssTagsFromSourceAndTranslation() {
        val cue = LearningSubtitleCue(
            0L,
            2_000L,
            """{\an8}Hello there""",
            """{\pos(100,200)}First\NSecond""",
        )
        assertEquals("Hello there\nFirst\nSecond", cue.displayText)
    }

    @Test
    fun partialCueAppendStoresCleanAssText() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.appendPartialCues(
            listOf(cue(0L, 2_000L, """{\an8}Hello there"""))
        )
        assertEquals("Hello there", PlayerLearningSubtitleState.cues.single().text)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun liveCaptureStoresCleanedAssSubtitleText() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLiveCapture()

        PlayerLearningSubtitleState.captureLiveCueGroup(listOf("""{\an8}Hello there"""), 1_000L)

        assertEquals("Hello there", PlayerLearningSubtitleState.cues.single().text)
        PlayerLearningSubtitleState.clear()
    }

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
    fun scrollTargetUsesNearestCueWhenPlaybackIsBetweenSubtitles() {
        val cues = listOf(
            cue(1_000L, 2_000L, "first"),
            cue(10_000L, 11_000L, "second"),
            cue(20_000L, 21_000L, "third"),
        )

        assertEquals(0, findLearningSubtitleScrollIndex(cues, 500L))
        assertEquals(1, findLearningSubtitleScrollIndex(cues, 8_000L))
        assertEquals(1, findLearningSubtitleScrollIndex(cues, 15_000L))
        assertEquals(2, findLearningSubtitleScrollIndex(cues, 18_000L))
        assertEquals(2, findLearningSubtitleScrollIndex(cues, 30_000L))
    }

    @Test
    fun liveCaptureGapPrefersNextCueAndFallsBackToPreviousAtTimelineEnd() {
        val cues = listOf(
            cue(88 * 60_000L + 12_000L, 88 * 60_000L + 30_000L, "previous"),
            cue(90 * 60_000L + 5_000L, 90 * 60_000L + 20_000L, "upcoming"),
        )
        val prefix = buildLearningSubtitlePrefixMaxEndMs(cues)
        assertEquals(1, findLiveCaptureLearningSubtitleScrollIndex(cues, prefix, 89 * 60_000L + 20_000L))
        assertEquals(1, findLiveCaptureLearningSubtitleScrollIndex(cues, prefix, 90 * 60_000L + 4_999L))
        assertEquals(1, findLiveCaptureLearningSubtitleScrollIndex(cues, prefix, 90 * 60_000L + 5_000L))
        assertEquals(0, findLiveCaptureLearningSubtitleScrollIndex(cues, prefix, 91 * 60_000L))
        assertEquals(-1, findLiveCaptureLearningSubtitleScrollIndex(emptyList(), emptyList(), 89 * 60_000L))
    }

    @Test
    fun liveCaptureNavigationPrefersActiveCueBeforeUpcomingCue() {
        val cues = listOf(cue(1_000L, 5_000L, "active"), cue(8_000L, 9_000L, "upcoming"))
        val prefix = buildLearningSubtitlePrefixMaxEndMs(cues)
        assertEquals(0, findLiveCaptureLearningSubtitleScrollIndex(cues, prefix, 3_000L))
        assertEquals(1, findLiveCaptureLearningSubtitleScrollIndex(cues, prefix, 6_000L))
    }

    @Test
    fun scrollTargetPrefersActiveCueOverNeighboringCues() {
        val cues = listOf(
            cue(1_000L, 10_000L, "long"),
            cue(5_000L, 6_000L, "overlap"),
            cue(12_000L, 13_000L, "next"),
        )

        assertEquals(1, findLearningSubtitleScrollIndex(cues, 5_500L))
    }

    @Test
    fun learningVideoAspectRatioSupportsUltraWideAndPortraitVideo() {
        assertEquals(2.76f, resolveLearningVideoAspectRatio(2760, 1000), 0.001f)
        assertEquals(9f / 16f, resolveLearningVideoAspectRatio(900, 1600), 0.001f)
        assertEquals(16f / 9f, resolveLearningVideoAspectRatio(0, 0), 0.001f)
    }

    @Test
    fun handlesEmptyCueLists() {
        assertEquals(emptyList(), buildLearningSubtitlePrefixMaxEndMs(emptyList()))
        assertEquals(-1, findLearningSubtitleIndex(emptyList(), emptyList(), 0L))
    }


    @Test
    fun displaysSelectedSubtitleNameWhileLoadingAndClearsItWhenReady() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLoading("English.srt")

        assertEquals(LearningSubtitleStatus.LOADING, PlayerLearningSubtitleState.status)
        assertEquals("English.srt", PlayerLearningSubtitleState.loadingSubtitleName)

        PlayerLearningSubtitleState.update(listOf(cue(0L, 2_000L, "Hello")))

        assertEquals(LearningSubtitleStatus.READY, PlayerLearningSubtitleState.status)
        assertEquals(null, PlayerLearningSubtitleState.loadingSubtitleName)
        assertEquals("Hello", PlayerLearningSubtitleState.cues.single().text)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun countsCachedTranslationsWhenSubtitleTrackIsLoaded() {
        PlayerLearningSubtitleState.clear()
        val allCues = (0 until 164).map { index ->
            cue(index * 2_000L, index * 2_000L + 1_500L, "Line $index")
                .let { if (index < 60) it.copy(translationText = "译文 $index") else it }
        }

        PlayerLearningSubtitleState.update(allCues)

        assertEquals(36, PlayerLearningSubtitleState.translationProgressPercent)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun mergesDuplicateCuesFromOverlappingClusterReads() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLoading()
        PlayerLearningSubtitleState.mergePrefetched(
            listOf(cue(61_090L, 62_400L, "Slipper Truth?")),
        )
        PlayerLearningSubtitleState.mergePrefetched(
            listOf(cue(61_320L, 62_500L, "  Slipper   Truth?  ").copy(translationText = "玻璃鞋真心话")),
        )

        assertEquals(1, PlayerLearningSubtitleState.cues.size)
        assertEquals(61_090L, PlayerLearningSubtitleState.cues.single().startTimeMs)
        assertEquals("玻璃鞋真心话", PlayerLearningSubtitleState.cues.single().translationText)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun keepsSameTextWhenCueIntervalsDoNotOverlap() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLoading()
        PlayerLearningSubtitleState.mergePrefetched(
            listOf(
                cue(10_000L, 11_000L, "Hello"),
                cue(12_000L, 13_000L, "Hello"),
            ),
        )

        assertEquals(2, PlayerLearningSubtitleState.cues.size)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun addsNewlyTranslatedCuesToExistingCachedProgress() {
        PlayerLearningSubtitleState.clear()
        val allCues = (0 until 164).map { index ->
            cue(index * 2_000L, index * 2_000L + 1_500L, "Line $index")
                .let { if (index < 60) it.copy(translationText = "译文 $index") else it }
        }
        PlayerLearningSubtitleState.update(allCues)

        PlayerLearningSubtitleState.mergePrefetched(
            allCues.drop(60).take(20).map { it.copy(translationText = "新译文") },
        )

        assertEquals(48, PlayerLearningSubtitleState.translationProgressPercent)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun beginLoadingKeepsRegisteredManualTranslationActionAvailable() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.setManualTranslationAction { selectedCues, _, _ -> selectedCues }

        PlayerLearningSubtitleState.beginLoading("OpenSubtitles.srt")

        assertEquals(true, PlayerLearningSubtitleState.hasManualTranslationAction)
        PlayerLearningSubtitleState.clear()
        assertEquals(false, PlayerLearningSubtitleState.hasManualTranslationAction)
    }

    @Test
    fun preservesPartialBatchTranslationsWhenFullEmbeddedExtractionFinishes() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLoading()
        val original = cue(1_000L, 3_000L, "Hello there")
        PlayerLearningSubtitleState.mergePrefetched(listOf(original))
        PlayerLearningSubtitleState.mergePrefetched(listOf(original.copy(translationText = "你好")))

        PlayerLearningSubtitleState.update(listOf(original))

        assertEquals("你好", PlayerLearningSubtitleState.cues.single().translationText)
        assertEquals("Hello there\n你好", PlayerLearningSubtitleState.cues.single().displayText)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun appendsPartialCueBatchesWithoutDroppingEarlierTimelineEntries() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLoading()
        PlayerLearningSubtitleState.appendPartialCues(listOf(cue(1_000L, 2_000L, "First")))
        PlayerLearningSubtitleState.appendPartialCues(
            listOf(
                cue(3_000L, 4_000L, "Second"),
                cue(1_000L, 2_000L, "First").copy(translationText = "第一条"),
            ),
        )

        assertEquals(listOf("First", "Second"), PlayerLearningSubtitleState.cues.map { it.text })
        assertEquals("第一条", PlayerLearningSubtitleState.cues.first().translationText)
        assertEquals("First\n第一条", PlayerLearningSubtitleState.cues.first().displayText)
        PlayerLearningSubtitleState.clear()
    }

    @Test
    fun appendsTwoThousandMovieCuesInSmallBatchesAndKeepsTimelineOrdered() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLoading()
        val movieCues = (0 until 2_000).map { index ->
            cue(index * 2_000L, index * 2_000L + 1_800L, "Movie line $index")
        }

        movieCues.chunked(40).forEach(PlayerLearningSubtitleState::appendPartialCues)

        assertEquals(2_000, PlayerLearningSubtitleState.cues.size)
        assertEquals(0L, PlayerLearningSubtitleState.cues.first().startTimeMs)
        assertEquals(3_998_000L, PlayerLearningSubtitleState.cues.last().startTimeMs)
        assertEquals(true, PlayerLearningSubtitleState.cues.zipWithNext().all { (a, b) ->
            a.startTimeMs <= b.startTimeMs
        })
        PlayerLearningSubtitleState.clear()
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
    fun liveCaptureSeekClosesOldCueAndStartsSameTextAsANewCueAfterSeek() {
        PlayerLearningSubtitleState.clear()
        PlayerLearningSubtitleState.beginLiveCapture()
        assertEquals(true, PlayerLearningSubtitleState.isLiveCapture)

        PlayerLearningSubtitleState.captureLiveCueGroup(listOf("Same subtitle"), 1_000L)
        PlayerLearningSubtitleState.finishLiveCaptureAt(5_000L)
        assertEquals(5_000L, PlayerLearningSubtitleState.cues.single().endTimeMs)

        PlayerLearningSubtitleState.captureLiveCueGroup(listOf("Same subtitle"), 63_000L)

        assertEquals(2, PlayerLearningSubtitleState.cues.size)
        assertEquals(1_000L, PlayerLearningSubtitleState.cues[0].startTimeMs)
        assertEquals(5_000L, PlayerLearningSubtitleState.cues[0].endTimeMs)
        assertEquals(63_000L, PlayerLearningSubtitleState.cues[1].startTimeMs)
        assertEquals(true, PlayerLearningSubtitleState.isLiveCapture)

        PlayerLearningSubtitleState.clear()
        assertEquals(false, PlayerLearningSubtitleState.isLiveCapture)
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
    fun estimatesWordHighlightAcrossSentenceDurationUsingSyllableWeights() {
        val text = "I really like it"
        assertEquals(0..0, findLearningSubtitleWordRange(text, 0L, 4_000L, 0L))
        // "really" is estimated as a longer spoken unit than the one-syllable words.
        assertEquals(2..7, findLearningSubtitleWordRange(text, 0L, 4_000L, 1_500L))
        assertEquals(9..12, findLearningSubtitleWordRange(text, 0L, 4_000L, 2_500L))
        assertEquals(14..15, findLearningSubtitleWordRange(text, 0L, 4_000L, 3_900L))
    }

    @Test
    fun syllableWeightsGiveLongerWordsMoreTimeThanShortWords() {
        val text = "I understand you"
        assertEquals(2..12, findLearningSubtitleWordRange(text, 0L, 3_000L, 1_500L))
    }

    @Test
    fun punctuationAddsAWeightedPauseWithoutLeavingCueBounds() {
        val text = "Wait, really? Go!"
        assertEquals(0..3, findLearningSubtitleWordRange(text, 0L, 3_000L, 200L))
        assertEquals(14..15, findLearningSubtitleWordRange(text, 0L, 3_000L, 2_900L))
        assertEquals(null, findLearningSubtitleWordRange(text, 0L, 3_000L, 3_000L))
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

    @Test
    fun bilingualEnglishAndChineseHighlightsOnlyTheOriginalLine() {
        assertEquals(
            listOf(true, false),
            learningSubtitleHighlightableLines(listOf("Go, Bentley! You can do it!", "出发吧，本特利！你能做到！")),
        )
    }

    @Test
    fun multipleOriginalLinesInTheSameScriptRemainHighlightable() {
        assertEquals(
            listOf(true, true),
            learningSubtitleHighlightableLines(listOf("First English line", "Second English line")),
        )
    }

    @Test
    fun supportsWhitespaceSeparatedWritingSystemsAndSkipsCjkLines() {
        assertEquals(
            listOf(true, false),
            learningSubtitleHighlightableLines(listOf("Привет, мир!", "你好，世界！")),
        )
        assertEquals(
            listOf(false, true),
            learningSubtitleHighlightableLines(listOf("こんにちは、世界！", "Hello, world!")),
        )
        assertEquals(
            listOf(true, false),
            learningSubtitleHighlightableLines(listOf("مرحبا بالعالم", "你好，世界！")),
        )
    }

    @Test
    fun wordHighlightCanTargetWordsOnTheSecondSubtitleLine() {
        val text = "First line\nSecond line"

        assertEquals(11..16, findLearningSubtitleWordRange(text, 0L, 10_000L, 7_500L))
    }


    @Test
    fun ignoresStandaloneSymbolsEmojiAndPunctuationWhenSelectingHighlight() {
        val text = "♪ I'm still here ♪ !!!"
        assertEquals(2..4, findLearningSubtitleWordRange(text, 0L, 4_000L, 0L))
        assertEquals(12..15, findLearningSubtitleWordRange(text, 0L, 4_000L, 3_900L))
        assertEquals(null, findLearningSubtitleWordRange("♪ !!! 👏", 0L, 2_000L, 1_000L))
    }

    @Test
    fun ignoresCommonMojibakeMarkersWithoutDroppingNeighboringWords() {
        val text = "â™ª I'm still here â™ª"
        assertEquals(4..6, findLearningSubtitleWordRange(text, 0L, 4_000L, 0L))
        assertEquals(14..17, findLearningSubtitleWordRange(text, 0L, 4_000L, 3_900L))
    }

    @Test
    fun highlightsContractionsAndHyphenatedWordsAsSingleUnits() {
        val text = "I'm still here; well-known words"
        assertEquals(0..2, findLearningSubtitleWordRange(text, 0L, 4_000L, 0L))
        assertEquals(16..25, findLearningSubtitleWordRange(text, 0L, 4_000L, 2_800L))
    }

    @Test
    fun ignoresSpeakerAndDecorativePunctuationButKeepsWords() {
        val text = "— Hello, world! —"
        assertEquals(2..6, findLearningSubtitleWordRange(text, 0L, 2_000L, 0L))
        assertEquals(9..13, findLearningSubtitleWordRange(text, 0L, 2_000L, 1_900L))
    }


    @Test
    fun ignoresSubtitleMarkupAndHtmlEntitiesDuringHighlighting() {
        val text = """{\\i1}Hello{\\i0} &amp; <b>world</b>"""
        assertEquals(5..9, findLearningSubtitleWordRange(text, 0L, 2_000L, 0L))
        assertEquals(25..29, findLearningSubtitleWordRange(text, 0L, 2_000L, 1_900L))
    }
\n
    @Test
    fun multilineSubtitleLineBreakDelaysHighlightingOfTheFollowingLine() {
        val text = "We made some\ngreat progress today."

        // The break between spoken phrases reserves a brief pause. At this point
        // the highlight should still be on the second line's first word, not jump
        // prematurely to the final word.
        assertEquals(
            text.indexOf("great")..text.indexOf("great") + "great".length - 1,
            findLearningSubtitleWordRange(text, 0L, 4_000L, 2_500L),
        )
        assertEquals(
            text.indexOf("progress")..text.indexOf("progress") + "progress".length - 1,
            findLearningSubtitleWordRange(text, 0L, 4_000L, 3_000L),
        )
    }

    private fun cue(start: Long, end: Long, text: String) =
        LearningSubtitleCue(startTimeMs = start, endTimeMs = end, text = text)
}
