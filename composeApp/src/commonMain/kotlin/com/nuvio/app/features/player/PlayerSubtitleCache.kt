package com.nuvio.app.features.player

/** Clears persisted subtitle data only; playback history and app settings are preserved. */
internal expect suspend fun clearPlayerSubtitleCaches(): Int
