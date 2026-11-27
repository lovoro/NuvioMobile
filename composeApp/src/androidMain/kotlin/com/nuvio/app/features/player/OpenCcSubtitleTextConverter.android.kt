package com.nuvio.app.features.player

import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.github.houbb.opencc4j.util.ZhHkConverterUtil
import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * Converts only the embedded target-language subtitle track to the configured Chinese variant.
 * The source-language cues and externally selected subtitle files are never modified.
 */
internal object OpenCcSubtitleTextConverter {
    /**
     * The merged learning subtitle is normalized to Simplified Chinese.
     * Simplified tracks pass through untouched; only an explicitly Traditional
     * target track is converted, using its own metadata rather than the add-on
     * target setting (which may describe the preferred track, not its actual script).
     */
    fun toSimplifiedIfTraditional(
        text: String,
        trackLanguage: String?,
        trackLabel: String?,
    ): String {
        if (text.isBlank() || !containsChinese(text)) return text
        val metadata = listOfNotNull(trackLanguage, trackLabel)
            .joinToString(" ")
            .trim()
            .lowercase()
            .replace('_', '-')
        if (!isTraditionalTrack(metadata)) return text

        return runCatching { ZhConverterUtil.toSimple(text) }.getOrElse { text }
    }

    private fun isTraditionalTrack(metadata: String): Boolean =
        Regex("""(^|[^a-z])(hant|tw|hk|mo|traditional)([^a-z]|$)""")
            .containsMatchIn(metadata) ||
            listOf("繁體", "繁体", "台灣", "台湾", "香港", "澳門", "澳门")
                .any(metadata::contains)

    private fun containsChinese(text: String): Boolean =
        text.any { character ->
            Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
        }
}
