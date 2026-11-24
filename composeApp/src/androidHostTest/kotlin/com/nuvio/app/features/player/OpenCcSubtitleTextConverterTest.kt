package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenCcSubtitleTextConverterTest {
    @Test
    fun convertsTraditionalTargetTrackToSimplifiedWhenConfiguredForMainlandChinese() {
        assertEquals(
            "繁体中文",
            OpenCcSubtitleTextConverter.convert("繁體中文", "zh-CN"),
        )
    }

    @Test
    fun convertsSimplifiedTargetTrackToTaiwanTraditionalWhenConfiguredForTaiwanChinese() {
        assertEquals(
            "滑鼠裡面的矽二極體壞了，導致游標解析度降低。",
            OpenCcSubtitleTextConverter.convert(
                "鼠标里面的硅二极管坏了，导致光标分辨率降低。",
                "zh-TW",
            ),
        )
    }

    @Test
    fun leavesAmbiguousChineseAndNonChineseTargetsUnchanged() {
        assertEquals("繁體中文", OpenCcSubtitleTextConverter.convert("繁體中文", "zh"))
        assertEquals("Hello", OpenCcSubtitleTextConverter.convert("Hello", "zh-CN"))
        assertEquals("繁體中文", OpenCcSubtitleTextConverter.convert("繁體中文", "en"))
    }
}
