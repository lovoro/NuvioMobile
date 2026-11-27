package com.nuvio.app.features.player

import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * Converts only the embedded target-language subtitle track to Simplified Chinese.
 * Simplified tracks pass through untouched; only a Traditional target track is converted.
 */
internal object OpenCcSubtitleTextConverter {
    fun toSimplifiedIfTraditional(
        text: String,
        trackLanguage: String?,
        trackLabel: String?,
    ): String {
        if (text.isBlank() || !containsChinese(text)) return text
        if (!isTraditionalTrack(text, trackLanguage, trackLabel)) return text
        return runCatching { ZhConverterUtil.toSimple(text) }.getOrElse { text }
    }

    /** Prefer explicit script/region metadata, then use Traditional-specific characters if metadata is generic. */
    fun isTraditionalTrack(text: String, trackLanguage: String?, trackLabel: String?): Boolean {
        val metadata = listOfNotNull(trackLanguage, trackLabel)
            .joinToString(" ").trim().lowercase().replace('_', '-')
        val explicitTraditional =
            Regex("""(^|[^a-z])(hant|tw|hk|mo|traditional)([^a-z]|$)""").containsMatchIn(metadata) ||
            listOf("繁體", "繁体", "台灣", "台湾", "香港", "澳門", "澳门", "臺灣", "正體")
                .any(metadata::contains)
        if (explicitTraditional) return true
        if (Regex("""(^|[^a-z])(hans|cn|sg|simplified)([^a-z]|$)""").containsMatchIn(metadata) ||
            listOf("简体", "簡體", "中国大陆", "新加坡").any(metadata::contains)
        ) return false
        val traditionalOnly = "體臺灣國門後發現為這麼裏與說話時間來對於還從進開關學習點線電視網絡廣東萬歲書車馬風雲龍愛歡樂醫藥頭髮"
        return text.any { it in traditionalOnly }
    }

    private fun containsChinese(text: String): Boolean =
        text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
}
