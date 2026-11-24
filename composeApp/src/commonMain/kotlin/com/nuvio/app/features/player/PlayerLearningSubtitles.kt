package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

data class LearningSubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val translationText: String? = null,
) {
    val displayText: String
        get() = listOfNotNull(text.takeIf(String::isNotBlank), translationText?.takeIf(String::isNotBlank))
            .joinToString("\n")
}

enum class LearningSubtitleStatus {
    NONE,
    LOADING,
    READY,
    PARTIAL,
    UNSUPPORTED,
    ERROR,
}

object PlayerLearningSubtitleState {
    var cues by mutableStateOf<List<LearningSubtitleCue>>(emptyList())
        private set
    private var mutableStatus by mutableStateOf(LearningSubtitleStatus.NONE)
    val status: LearningSubtitleStatus get() = mutableStatus
    var loadingSubtitleName by mutableStateOf<String?>(null)
        private set
    var isTranslating by mutableStateOf(false)
    private var manualTranslationEnabled by mutableStateOf(true)
    var translationMessage by mutableStateOf<String?>(null)
        private set
    private var manualTranslationAction: (suspend (List<LearningSubtitleCue>, Long, suspend (List<LearningSubtitleCue>) -> Unit) -> List<LearningSubtitleCue>)? = null
    private var manualTranslationDiagnosticAction: ((Boolean, Boolean, Int, String) -> Unit)? = null

    fun setManualTranslationDiagnosticAction(action: ((Boolean, Boolean, Int, String) -> Unit)?) {
        manualTranslationDiagnosticAction = action
    }
    private var translationGeneration = 0L
    val hasManualTranslationAction: Boolean get() = manualTranslationAction != null
    private val completedLiveCues = mutableListOf<LearningSubtitleCue>()
    private val activeLiveCues = linkedMapOf<String, Long>()
    private var prefetchedCues: List<LearningSubtitleCue> = emptyList()
    private var completeExtractionLoaded = false

    private fun mergeCueLists(vararg lists: List<LearningSubtitleCue>): List<LearningSubtitleCue> {
        val merged = linkedMapOf<Pair<Long, String>, LearningSubtitleCue>()
        lists.forEach { list ->
            list.forEach cueLoop@{ cue ->
                if (cue.text.isBlank() || cue.endTimeMs <= cue.startTimeMs) return@cueLoop
                val key = cue.startTimeMs to cue.text
                val previous = merged[key]
                merged[key] = if (previous == null) cue else previous.copy(
                    endTimeMs = maxOf(previous.endTimeMs, cue.endTimeMs),
                    translationText = cue.translationText?.takeIf(String::isNotBlank) ?: previous.translationText,
                )
            }
        }
        return merged.values.sortedBy { it.startTimeMs }
    }

    private fun publishCombined(live: List<LearningSubtitleCue>) {
        cues = mergeCueLists(live, prefetchedCues)
    }

    fun updateManualTranslationEnabled(enabled: Boolean) {
        manualTranslationEnabled = enabled
        if (!enabled) {
            isTranslating = false
            translationMessage = null
        }
    }

    fun setManualTranslationAction(action: (suspend (List<LearningSubtitleCue>, Long, suspend (List<LearningSubtitleCue>) -> Unit) -> List<LearningSubtitleCue>)?) {
        manualTranslationAction = action
        translationGeneration += 1L
        isTranslating = false
        translationMessage = null
    }

    suspend fun translateManually(currentPositionMs: Long) {
        manualTranslationDiagnosticAction?.invoke(
            manualTranslationEnabled,
            manualTranslationAction != null,
            cues.size,
            status.name,
        )
        if (isTranslating) return
        if (!manualTranslationEnabled) {
            translationMessage = "当前字幕语言与插件配置的源语言不一致，无需翻译。"
            return
        }
        val action = manualTranslationAction
        if (action == null) {
            translationMessage = "当前字幕暂时无法翻译，请重新选择字幕后重试。"
            return
        }
        val source = cues
        if (source.isEmpty()) {
            translationMessage = "字幕尚未加载完成，请稍后再试。"
            return
        }
        val requestGeneration = translationGeneration
        isTranslating = true
        translationMessage = null
        try {
            val translated = action(source, currentPositionMs.coerceAtLeast(0L)) { batch ->
                if (requestGeneration == translationGeneration) applyTranslations(batch)
            }
            if (requestGeneration != translationGeneration) return
            if (translated.none { !it.translationText.isNullOrBlank() }) error("没有获取到译文，请检查 Double Subtitles 插件是否启用及配置是否正确。")
            applyTranslations(translated)
            translationMessage = null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (requestGeneration == translationGeneration) {
                translationMessage = error.message?.takeIf(String::isNotBlank) ?: "翻译失败，请稍后重试。"
            }
        } finally {
            if (requestGeneration == translationGeneration) isTranslating = false
        }
    }

    private fun applyTranslations(translatedCues: List<LearningSubtitleCue>) {
        cues = mergeCueLists(cues, translatedCues)
        prefetchedCues = mergeCueLists(prefetchedCues, translatedCues)
    }

    fun mergePrefetched(newCues: List<LearningSubtitleCue>) {
        prefetchedCues = mergeCueLists(prefetchedCues, newCues)
        publishCombined(cues)
        if (cues.isNotEmpty()) mutableStatus = LearningSubtitleStatus.PARTIAL
    }

    /** Starts an explicitly partial capture for embedded tracks whose samples arrive during playback. */
    fun beginLiveCapture(subtitleName: String? = null) {
        setManualTranslationAction(null)
        loadingSubtitleName = subtitleName?.takeIf { it.isNotBlank() }
        cues = emptyList()
        prefetchedCues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        completeExtractionLoaded = false
        mutableStatus = LearningSubtitleStatus.LOADING
    }

    /** Captures only cues actually emitted during playback; seeking can leave gaps. */
    fun captureLiveCueGroup(texts: List<String>, positionMs: Long) {
        if (completeExtractionLoaded) return
        val timeMs = positionMs.coerceAtLeast(0L)
        val visibleTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val visibleSet = visibleTexts.toSet()
        activeLiveCues.toMap().forEach { (text, startMs) ->
            if (text !in visibleSet) {
                completedLiveCues += LearningSubtitleCue(startMs, timeMs.coerceAtLeast(startMs + 1L), text)
                activeLiveCues.remove(text)
            }
        }
        visibleTexts.forEach { text -> if (text !in activeLiveCues) activeLiveCues[text] = timeMs }
        val activeSnapshot = activeLiveCues.map { (text, startMs) ->
            LearningSubtitleCue(startMs, maxOf(timeMs + 1_500L, startMs + 1_500L), text)
        }
        publishCombined(completedLiveCues + activeSnapshot)
        if (cues.isNotEmpty()) {
            mutableStatus = LearningSubtitleStatus.PARTIAL
            loadingSubtitleName = null
        }
    }

    fun setStatus(newStatus: LearningSubtitleStatus) {
        mutableStatus = newStatus
        if (newStatus != LearningSubtitleStatus.LOADING) loadingSubtitleName = null
        if (newStatus != LearningSubtitleStatus.READY) cues = emptyList()
    }

    fun beginLoading(subtitleName: String? = null) {
        // Loading a new cue batch does not own the translation action lifecycle.
        // The player registers/replaces the action when a subtitle is selected; clearing it
        // here can leave a successfully loaded Sidecar subtitle with no manual action.
        translationGeneration += 1L
        isTranslating = false
        translationMessage = null
        subtitleName?.takeIf { it.isNotBlank() }?.let { loadingSubtitleName = it }
        cues = emptyList()
        prefetchedCues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        completeExtractionLoaded = false
        mutableStatus = LearningSubtitleStatus.LOADING
    }

    fun markUnsupported() = setStatus(LearningSubtitleStatus.UNSUPPORTED)

    fun markError() = setStatus(LearningSubtitleStatus.ERROR)

    /** Updates the visible list with cues discovered by embedded-track extraction. */
    fun updatePartial(newCues: List<LearningSubtitleCue>) {
        if (completeExtractionLoaded) return
        publishCombined(newCues
            .asSequence()
            .filter { it.text.isNotBlank() && it.endTimeMs > it.startTimeMs }
            .distinctBy { it.startTimeMs to it.text }
            .sortedBy { it.startTimeMs }
            .toList())
        mutableStatus = if (cues.isEmpty()) LearningSubtitleStatus.LOADING else LearningSubtitleStatus.PARTIAL
        if (cues.isNotEmpty()) loadingSubtitleName = null
    }

    fun update(newCues: List<LearningSubtitleCue>) {
        completeExtractionLoaded = true
        val translatedPartialCues = prefetchedCues
        prefetchedCues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        cues = mergeCueLists(newCues, translatedPartialCues)
        mutableStatus = if (cues.isEmpty()) LearningSubtitleStatus.UNSUPPORTED else LearningSubtitleStatus.READY
        loadingSubtitleName = null
    }

    fun clear() {
        setManualTranslationAction(null)
        manualTranslationEnabled = true
        translationMessage = null
        isTranslating = false
        completeExtractionLoaded = false
        prefetchedCues = emptyList()
        cues = emptyList()
        completedLiveCues.clear()
        activeLiveCues.clear()
        loadingSubtitleName = null
        mutableStatus = LearningSubtitleStatus.NONE
    }
}

/**
 * Builds a prefix maximum of cue end times. Subtitle cues are ordered by start time, but their
 * end times are not necessarily ordered: overlapping cues can outlive cues that start later.
 * The prefix maximum lets the active-cue lookup stop as soon as no earlier cue can still be active.
 */
internal fun buildLearningSubtitlePrefixMaxEndMs(
    cues: List<LearningSubtitleCue>,
): List<Long> {
    if (cues.isEmpty()) return emptyList()
    val prefixMaxEndMs = ArrayList<Long>(cues.size)
    var maxEndMs = Long.MIN_VALUE
    cues.forEach { cue ->
        maxEndMs = maxOf(maxEndMs, cue.endTimeMs)
        prefixMaxEndMs.add(maxEndMs)
    }
    return prefixMaxEndMs
}

/**
 * Returns the most recently started cue active at [positionMs].
 *
 * [cues] must be sorted by startTimeMs and [prefixMaxEndMs] must be built for that same list.
 * Lookup is O(log n + k), where k is the number of overlapping candidates inspected.
 */
internal fun findLearningSubtitleIndex(
    cues: List<LearningSubtitleCue>,
    prefixMaxEndMs: List<Long>,
    positionMs: Long,
): Int {
    if (cues.isEmpty() || prefixMaxEndMs.size != cues.size) return -1

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
        if (prefixMaxEndMs[index] <= positionMs) break
        val cue = cues[index]
        if (cue.endTimeMs > positionMs) return index
    }
    return -1
}

/** Convenience overload for callers that do not repeatedly query the same cue list. */
internal fun findLearningSubtitleIndex(
    cues: List<LearningSubtitleCue>,
    positionMs: Long,
): Int = findLearningSubtitleIndex(
    cues = cues,
    prefixMaxEndMs = buildLearningSubtitlePrefixMaxEndMs(cues),
    positionMs = positionMs,
)

/**
 * Identifies source-language lines in a multiline cue. Bilingual tracks commonly put the
 * original first and the translation below it. Lines using a different writing system remain
 * visible but are not word-highlighted; multiple original lines in the same script still are.
 */
internal fun learningSubtitleHighlightableLines(lines: List<String>): List<Boolean> {
    val scripts = lines.map(::learningSubtitleScript)
    val sourceScript = scripts.firstOrNull { it != LearningSubtitleScript.Unknown }
        ?: return lines.map { true }
    return scripts.map { it == sourceScript }
}

private enum class LearningSubtitleScript {
    Latin, Cyrillic, Arabic, Hebrew, Korean, Devanagari, Thai, Other, Unknown,
}

private fun learningSubtitleScript(line: String): LearningSubtitleScript {
    var hasHangul = false
    val counts = mutableMapOf<LearningSubtitleScript, Int>()
    line.forEach { char ->
        val code = char.code
        val script = when {
            // Han and kana scripts do not provide reliable whitespace word boundaries.
            // Keep those lines visible but exclude them from estimated word highlighting.
            code in 0x3040..0x30FF || code in 0x31F0..0x31FF -> LearningSubtitleScript.Unknown
            code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F -> {
                hasHangul = true
                LearningSubtitleScript.Korean
            }
            code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF -> LearningSubtitleScript.Unknown
            code in 0x0041..0x024F || code in 0x1E00..0x1EFF -> LearningSubtitleScript.Latin
            code in 0x0400..0x052F || code in 0x2DE0..0x2DFF || code in 0xA640..0xA69F -> LearningSubtitleScript.Cyrillic
            code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF ||
                code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF -> LearningSubtitleScript.Arabic
            code in 0x0590..0x05FF -> LearningSubtitleScript.Hebrew
            code in 0x0900..0x097F -> LearningSubtitleScript.Devanagari
            code in 0x0E00..0x0E7F -> LearningSubtitleScript.Thai
            char.isLetter() -> LearningSubtitleScript.Other
            else -> null
        }
        if (script != null) counts[script] = (counts[script] ?: 0) + 1
    }
    if (hasHangul) return LearningSubtitleScript.Korean
    return counts.maxByOrNull { it.value }?.key ?: LearningSubtitleScript.Unknown
}

/**
 * Estimates the active whitespace-delimited word from a cue's overall timing.
 *
 * Most subtitle formats only provide sentence-level timestamps. For Latin words we use a
 * lightweight syllable estimate rather than raw character count, then add a small pause weight
 * for punctuation. Other whitespace-delimited writing systems retain a character-based fallback.
 * This remains an estimate, not source-provided word timing.
 */
private fun estimatedLearningWordWeight(token: String): Float {
    val letters = token.lowercase().filter { it.isLetter() }
    if (letters.isEmpty()) return 1f

    val script = learningSubtitleScript(letters)
    if (script != LearningSubtitleScript.Latin) return letters.length.coerceAtLeast(1).toFloat()

    val vowelGroups = Regex("[aeiouy]+").findAll(letters).count()
    var syllables = vowelGroups.coerceAtLeast(1)
    // A final silent "e" usually does not form a syllable, except common consonant + "le" endings.
    if (letters.length > 2 && letters.endsWith("e") && !letters.endsWith("le") && syllables > 1) {
        syllables--
    }
    if (letters.endsWith("le") && letters.length > 2 &&
        letters[letters.lastIndex - 2] !in "aeiouy"
    ) {
        syllables++
    }
    if (letters.endsWith("ed") && letters.length > 3 &&
        letters[letters.lastIndex - 2] !in "td"
    ) {
        syllables = (syllables - 1).coerceAtLeast(1)
    }

    val punctuationWeight = when {
        token.endsWith("...") || token.endsWith("…") -> 0.65f
        token.endsWith(".") || token.endsWith("!") || token.endsWith("?") -> 0.45f
        token.endsWith(",") || token.endsWith(";") || token.endsWith(":") -> 0.2f
        else -> 0f
    }
    return syllables.toFloat() + punctuationWeight
}

private data class LearningSubtitleWord(
    val highlightRange: IntRange,
    val weightToken: String,
    val pauseAfterWeight: Float = 0f,
)

/** Conservative vocabulary cues for bracketed sound-effect captions. */
private fun isLikelyNonSpeechCaption(annotation: String): Boolean {
    val words = annotation.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
    val cues = listOf(
        "whistle", "whistles", "whistling", "phone rings", "phone ringing", "cellphone",
        "ringing", "buzzing", "vibrating", "alarm", "door", "footsteps", "footstep",
        "keys jingling", "jingling", "music", "laughs", "laughing", "chuckles",
        "sighs", "sighing", "coughs", "coughing", "sobbing", "crying", "gasps",
        "thunder", "rain", "wind", "engine", "car horn", "horn honks", "honking",
        "glass breaking", "shattering", "explosion", "gunshot", "birds chirping",
        "dog barking", "cat meowing", "applause", "cheering", "crowd", "static",
    )
    return cues.any { cue -> words.contains(cue) }
}

/** Finds lexical words rather than treating every non-whitespace token as a word. */
private fun learningSubtitleWords(text: String): List<LearningSubtitleWord> {
    val result = mutableListOf<LearningSubtitleWord>()
    // In a mixed cue, bracketed sound-effect captions should not consume the
    // estimated speaking-time budget. A cue made entirely of a sound caption
    // remains highlightable, which is useful for learning vocabulary such as
    // "whistles", "footsteps", or "phone rings".
    val annotationMatches = Regex("\\[[^\\]\\r\\n]+\\]").findAll(text)
        .filter { isLikelyNonSpeechCaption(it.value) }
        .toList()
    val textOutsideAnnotations = buildString {
        var cursor = 0
        annotationMatches.forEach { match ->
            append(text.substring(cursor, match.range.first))
            append(' ')
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
    val hasWordsOutsideAnnotations = annotationMatches.isNotEmpty() &&
        Regex("[\\p{L}\\p{N}]+").containsMatchIn(textOutsideAnnotations)
    val excludedAnnotationRanges = if (hasWordsOutsideAnnotations) {
        annotationMatches.map { it.range }
    } else {
        emptyList()
    }
    val tokenMatches = Regex("\\S+").findAll(text).toList()
    tokenMatches.forEachIndexed { tokenIndex, tokenMatch ->
        val token = tokenMatch.value
        val tokenStart = tokenMatch.range.first
        var index = 0
        while (index < token.length) {
            // Skip subtitle styling markup (ASS override blocks, HTML tags and entities).
            if (token[index] == '{') {
                val close = token.indexOf('}', index + 1)
                if (close > index && token.substring(index + 1, close).startsWith("\\")) {
                    index = close + 1
                    continue
                }
            }
            if (token[index] == '<') {
                val close = token.indexOf('>', index + 1)
                if (close > index) {
                    val tag = token.substring(index + 1, close)
                    if (tag.firstOrNull()?.let { it.isLetter() || it == '/' || it == '!' } == true) {
                        index = close + 1
                        continue
                    }
                }
            }
            if (token[index] == '&') {
                val semicolon = token.indexOf(';', index + 1)
                if (semicolon > index + 1 &&
                    token.substring(index + 1, semicolon).all { it.isLetterOrDigit() || it == '#' }
                ) {
                    index = semicolon + 1
                    continue
                }
            }

            // Skip common UTF-8 decoding artifacts without discarding adjacent real words.
            val artifactLength = when {
                token.startsWith("â™ª", index) -> 3
                token.startsWith("â€™", index) || token.startsWith("â€œ", index) -> 3
                token.startsWith("â€", index) || token.startsWith("ï¿½", index) -> 3
                token.startsWith("Ã", index) || token.startsWith("Â", index) -> 2
                token[index] == '\uFFFD' -> 1
                else -> 0
            }
            if (artifactLength > 0) {
                index = (index + artifactLength).coerceAtMost(token.length)
                continue
            }
            val absoluteIndex = tokenStart + index
            if (excludedAnnotationRanges.any { absoluteIndex in it }) {
                index++
                continue
            }
            if (!token[index].isLetterOrDigit()) {
                index++
                continue
            }

            val wordStart = index
            var wordEnd = index + 1
            while (wordEnd < token.length) {
                val char = token[wordEnd]
                when {
                    char.isLetterOrDigit() || char.isSubtitleCombiningMark() -> wordEnd++
                    (char == '\'' || char == '’' || char == '-' || char == '‐' || char == '‑') &&
                        wordEnd + 1 < token.length &&
                        token[wordEnd - 1].isLetterOrDigit() &&
                        token[wordEnd + 1].isLetterOrDigit() -> wordEnd++
                    else -> break
                }
            }
            val word = token.substring(wordStart, wordEnd)
            if (learningSubtitleScript(word) != LearningSubtitleScript.Unknown) {
                result += LearningSubtitleWord(
                    highlightRange = (tokenStart + wordStart)..(tokenStart + wordEnd - 1),
                    weightToken = token.substring(wordStart),
                    // A real subtitle line break often marks a spoken phrase boundary.
                    // Reserve a short pause so later lines do not get highlighted too early.
                    pauseAfterWeight = if (
                        tokenIndex < tokenMatches.lastIndex &&
                        text.substring(tokenMatch.range.last + 1, tokenMatches[tokenIndex + 1].range.first).contains('\n')
                    ) 1.15f else 0f,
                )
            }
            index = wordEnd
        }
    }
    return result
}

private fun Char.isSubtitleCombiningMark(): Boolean =
    code in 0x0300..0x036F || code in 0x1AB0..0x1AFF ||
        code in 0x1DC0..0x1DFF || code in 0x20D0..0x20FF || code in 0xFE20..0xFE2F

internal fun findLearningSubtitleWordRange(
    text: String,
    startTimeMs: Long,
    endTimeMs: Long,
    positionMs: Long,
): IntRange? {
    if (endTimeMs <= startTimeMs || positionMs < startTimeMs || positionMs >= endTimeMs) return null
    // Scripts without whitespace (for example Japanese or Chinese) do not expose reliable
    // word boundaries here, so we deliberately avoid inventing them.
    val words = learningSubtitleWords(text)
    if (words.isEmpty()) return null

    val weights = words.map {
        estimatedLearningWordWeight(it.weightToken) + it.pauseAfterWeight
    }
    val totalWeight = weights.sum()
    if (totalWeight <= 0f) return null
    val progress = ((positionMs - startTimeMs).toFloat() / (endTimeMs - startTimeMs).toFloat())
        .coerceIn(0f, 0.999999f)
    val targetWeight = progress * totalWeight
    var consumedWeight = 0f
    words.forEachIndexed { index, word ->
        val nextWeight = consumedWeight + weights[index]
        if (targetWeight < nextWeight) return word.highlightRange
        consumedWeight = nextWeight
    }
    return words.last().highlightRange
}


/**
 * Returns a useful list target for [positionMs], even when no subtitle cue is active.
 * During gaps, the closest neighboring cue is selected (preferring the upcoming cue on ties).
 */
internal fun findLearningSubtitleScrollIndex(
    cues: List<LearningSubtitleCue>,
    positionMs: Long,
): Int {
    if (cues.isEmpty()) return -1
    val activeIndex = findLearningSubtitleIndex(cues, positionMs)
    if (activeIndex >= 0) return activeIndex

    var low = 0
    var high = cues.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (cues[middle].startTimeMs <= positionMs) low = middle + 1 else high = middle
    }
    val nextIndex = low
    if (nextIndex == 0) return 0
    if (nextIndex >= cues.size) return cues.lastIndex

    val previous = cues[nextIndex - 1]
    val next = cues[nextIndex]
    val distanceFromPrevious = (positionMs - previous.endTimeMs).coerceAtLeast(0L)
    val distanceToNext = (next.startTimeMs - positionMs).coerceAtLeast(0L)
    return if (distanceToNext <= distanceFromPrevious) nextIndex else nextIndex - 1
}


/**
 * Keeps the actual video aspect ratio for Language Learning layout.
 * Some sources (for example ultra-wide cinema and portrait clips) are wider/narrower
 * than the old 1.0..2.4 range. Clamping them to 2.4 made the video viewport disagree
 * with the PlayerView's own measured video bounds.
 */
internal fun resolveLearningVideoAspectRatio(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 9f
    return (width.toFloat() / height.toFloat()).coerceIn(0.5f, 3.0f)
}
