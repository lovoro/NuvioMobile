package com.nuvio.app.features.player

/** Removes embedded subtitle/translation data and temporary external-player subtitle files. */
internal actual suspend fun clearPlayerSubtitleCaches(): Int =
    PlayerEmbeddedSubtitleCache.clearAll() + SubtitleFileCache.clearCache()
