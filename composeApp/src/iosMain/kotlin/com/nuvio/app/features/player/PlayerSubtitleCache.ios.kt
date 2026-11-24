package com.nuvio.app.features.player

/** iOS does not persist these Android subtitle cache formats. */
internal actual suspend fun clearPlayerSubtitleCaches(): Int = 0
