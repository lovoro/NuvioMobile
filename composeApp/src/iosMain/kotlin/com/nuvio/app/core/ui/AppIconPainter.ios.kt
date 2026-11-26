package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ic_player_aspect_ratio
import nuvio.composeapp.generated.resources.ic_player_audio_filled
import nuvio.composeapp.generated.resources.ic_player_episodes
import nuvio.composeapp.generated.resources.ic_player_pause
import nuvio.composeapp.generated.resources.ic_player_play
import nuvio.composeapp.generated.resources.ic_player_source
import nuvio.composeapp.generated.resources.ic_player_subtitles
import nuvio.composeapp.generated.resources.ic_player_repeat_one
import nuvio.composeapp.generated.resources.ic_language_learning
import nuvio.composeapp.generated.resources.ic_language_learning_google
import nuvio.composeapp.generated.resources.ic_language_learning_ai
import nuvio.composeapp.generated.resources.ic_language_learning_deepl
import nuvio.composeapp.generated.resources.library_add_plus
import org.jetbrains.compose.resources.painterResource

@Composable
actual fun appIconPainter(icon: AppIconResource): Painter =
    painterResource(
        when (icon) {
            AppIconResource.PlayerPlay -> Res.drawable.ic_player_play
            AppIconResource.PlayerPause -> Res.drawable.ic_player_pause
            AppIconResource.PlayerAspectRatio -> Res.drawable.ic_player_aspect_ratio
            AppIconResource.PlayerSubtitles -> Res.drawable.ic_player_subtitles
            AppIconResource.LanguageLearning -> Res.drawable.ic_language_learning
            AppIconResource.LanguageLearningGoogle -> Res.drawable.ic_language_learning_google
            AppIconResource.LanguageLearningAI -> Res.drawable.ic_language_learning_ai
            AppIconResource.LanguageLearningDeepL -> Res.drawable.ic_language_learning_deepl
            AppIconResource.PlayerAudioFilled -> Res.drawable.ic_player_audio_filled
            AppIconResource.PlayerSource -> Res.drawable.ic_player_source
            AppIconResource.PlayerEpisodes -> Res.drawable.ic_player_episodes
            AppIconResource.PlayerRepeatOne -> Res.drawable.ic_player_repeat_one
            AppIconResource.LibraryAddPlus -> Res.drawable.library_add_plus
        }
    )
