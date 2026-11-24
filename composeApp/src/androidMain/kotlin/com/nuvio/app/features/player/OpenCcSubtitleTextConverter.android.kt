package com.nuvio.app.features.player

import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.github.houbb.opencc4j.util.ZhHkConverterUtil
import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * Converts only the embedded target-language subtitle track to the configured Chinese variant.
 * The source-language cues and externally selected subtitle files are never modified.
 */
internal object OpenCcSubtitleTextConverter {
    fun convert(text: String, targetLanguage: String?): String {
        if (text.isBlank() || !containsChinese(text)) return text
        val tag = targetLanguage
            ?.trim()
            ?.replace('_', '-')
            ?.lowercase()
            ?: return text
        val parts = tag.split('-')
        if (parts.firstOrNull() != "zh") return text

        return runCatching {
            when {
                parts.any { it == "hant" } || parts.any { it == "tw" } ->
                    ZhTwConverterUtil.toTraditional(text)
                parts.any { it == "hk" || it == "mo" } ->
                    ZhHkConverterUtil.toTraditional(text)
                parts.any { it == "hans" } || parts.any { it == "cn" || it == "sg" } ->
                    ZhConverterUtil.toSimple(text)
                else -> text
            }
        }.getOrElse { text }
    }

    private fun containsChinese(text: String): Boolean =
        text.any { character ->
            Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
        }
}
