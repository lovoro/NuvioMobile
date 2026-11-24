package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.core.ui.LocalFold
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.features.player.skip.internalSkipAction
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreenRuntime.RenderPlayerRuntimeUi(
    partyPanelWidth: Dp,
    partyPanelInset: Dp,
    dockPartyPanel: Boolean,
    learningMode: Boolean,
    containerHeight: Dp,
) {
    val runtime = this
    var showSubtitleAutomationSettingsDialog by remember { mutableStateOf(false) }
    var bilingualMergeSwitch by remember { mutableStateOf(true) }
    var automaticTranslationSwitch by remember { mutableStateOf(false) }
    val isInPip = rememberIsInPictureInPicture()
    val displayedPositionMs = scrubbingPositionMs ?: playbackSnapshot.positionMs
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    val currentGestureFeedback = liveGestureFeedback ?: gestureFeedback
    val isP2pPlaybackActive = activeTorrentInfoHash != null
    val p2pConnecting = p2pStreamingState as? P2pStreamingState.Connecting
    val p2pStats = p2pStreamingState as? P2pStreamingState.Streaming
    val p2pPeerInfo = p2pStats?.let { stats ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            stats.seeds,
            stats.peers,
        )
    }
    val p2pDownloadSpeed = p2pStats?.let { formatP2pSpeed(it.downloadSpeed) }
    val p2pLoadingBytes = p2pStats?.let { maxOf(it.downloadedBytes, it.deliveredBytes) } ?: 0L
    val connectingPeerInfo = p2pConnecting?.let { state ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            state.seeds,
            state.peers,
        )
    }
    val p2pInitialLoadingMessage = when {
        !isP2pPlaybackActive || initialLoadCompleted -> null
        p2pConnecting != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                p2pConnectingPhaseLabel(p2pConnecting.phase)
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_connecting_status,
                    p2pConnectingPhaseLabel(p2pConnecting.phase),
                    connectingPeerInfo.orEmpty(),
                    formatP2pSpeed(p2pConnecting.downloadSpeed),
                )
            }
        }
        p2pStats != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                null
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_loading_status,
                    formatP2pMegabytes(p2pLoadingBytes),
                    p2pPeerInfo.orEmpty(),
                    p2pDownloadSpeed.orEmpty(),
                )
            }
        }
        else -> org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
        )
    }
    val bufferedAheadMs = (playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs)
        .coerceAtLeast(0L)
    val p2pInitialLoadingProgress = when {
        !isP2pPlaybackActive || initialLoadCompleted || p2pStats == null -> null
        else -> p2pInitialLoadingProgress(
            bufferedAheadMs = bufferedAheadMs,
            downloadedBytes = p2pStats.downloadedBytes,
            deliveredBytes = p2pStats.deliveredBytes,
        )
    }
    val showP2pRebufferStats = isP2pPlaybackActive &&
        initialLoadCompleted &&
        playbackSnapshot.isLoading &&
        p2pStats != null &&
        !p2pSettingsUiState.hideTorrentStats
    val p2pRebufferMessage = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000L)
                .coerceAtLeast(0L)
            "${bufferedSeconds}s buffered · ${p2pPeerInfo.orEmpty()} · ${p2pDownloadSpeed.orEmpty()}"
        }
    }
    val p2pRebufferProgress = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000f)
                .coerceAtLeast(0f)
            (bufferedSeconds / 10f).coerceIn(0f, 1f)
        }
    }
    val gestureCallbacks = rememberSurfaceGestureCallbacks()
    val fold = LocalFold.current?.takeIf { !isInPip }
    val density = LocalDensity.current
    val learningVideoAspectRatio = remember(playbackSnapshot.videoWidth, playbackSnapshot.videoHeight) {
        resolveLearningVideoAspectRatio(
            width = playbackSnapshot.videoWidth,
            height = playbackSnapshot.videoHeight,
        )
    }
    val learningVideoHeight = with(density) {
        if (layoutSize.width > 0) (layoutSize.width.toDp() / learningVideoAspectRatio).coerceAtMost(containerHeight)
        else containerHeight * 0.34f
    }
    val videoModifier = when {
        fold != null -> Modifier.fillMaxWidth().height(fold.top)
        learningMode -> Modifier.fillMaxWidth().aspectRatio(learningVideoAspectRatio)
        else -> Modifier.fillMaxSize()
    }
    val panelModifier = if (fold != null) Modifier.fillMaxSize().padding(top = fold.bottom) else Modifier.fillMaxSize()
    val playbackGesturesEnabled = initialLoadCompleted && errorMessage == null
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(keyFocusRequester) {
        keyFocusRequester.requestFocus()
    }

    BindAutoSyncRuntimeEffects() // AutoSync hook

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = partyPanelInset)
            .clipToBounds()
            .onSizeChanged { layoutSize = it }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || playerControlsLocked) return@onKeyEvent false
                when {
                    event.key == Key.Spacebar -> togglePlayback()
                    event.key == Key.DirectionLeft && playbackGesturesEnabled -> seekBy(-10_000L)
                    event.key == Key.DirectionRight && playbackGesturesEnabled -> seekBy(10_000L)
                    event.key == Key.DirectionUp -> changeVolumeBy(PlayerKeyVolumeStep)
                    event.key == Key.DirectionDown -> changeVolumeBy(-PlayerKeyVolumeStep)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusRequester(keyFocusRequester)
            .focusable()
            .playerSurfaceTapGestures(
                layoutSize = layoutSize,
                playbackGesturesEnabled = playbackGesturesEnabled,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                onSurfaceTap = gestureCallbacks.onSurfaceTap,
                onSurfaceDoubleTap = gestureCallbacks.onSurfaceDoubleTap,
                activateHoldToSpeedState = gestureCallbacks.activateHoldToSpeed,
                deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
            )
            .playerSurfaceDragGestures(
                gestureController = gestureController,
                layoutSize = layoutSize,
                playbackGesturesEnabled = playbackGesturesEnabled,
                sideGestureSystemEdgeExclusionPx = sideGestureSystemEdgeExclusionPx,
                horizontalSeekSystemEdgeExclusionPx = horizontalSeekSystemEdgeExclusionPx,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                touchGesturesEnabledState = gestureCallbacks.touchGesturesEnabled,
                isHoldToSpeedGestureActiveState = gestureCallbacks.isHoldToSpeedGestureActive,
                currentPositionMsState = gestureCallbacks.currentPositionMs,
                currentDurationMsState = gestureCallbacks.currentDurationMs,
                deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                showHorizontalSeekPreviewState = gestureCallbacks.showHorizontalSeekPreview,
                showBrightnessFeedbackState = gestureCallbacks.showBrightnessFeedback,
                showVolumeFeedbackState = gestureCallbacks.showVolumeFeedback,
                clearLiveGestureFeedbackState = gestureCallbacks.clearLiveGestureFeedback,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
                commitHorizontalSeekState = gestureCallbacks.commitHorizontalSeek,
            ),
    ) {
        val playerSurfaceSourceUrl = if (isP2pPlaybackActive) p2pResolvedSourceUrl else activeSourceUrl
        val playbackKey = activePlaybackKey
        val initialPositionRequestKey = currentInitialPositionRequestKey()

        val resolvedAutoEngineState = remember(sourcePlaybackKey) {
            mutableStateOf<AndroidPlaybackEngine?>(null)
        }
        if (!initialLoadCompleted || resolvedAutoEngineState.value == null) {
            resolvedAutoEngineState.value = resolveAutoPlaybackEngine(
                playbackEngineSetting = playerSettingsUiState.androidPlaybackEngine,
                videoId = activeVideoId,
                streamFilename = activeTorrentFilename ?: activeStreamFilename,
                streamName = activeStreamTitle,
                streamDescription = activeStreamSubtitle,
                contentTitle = title,
                genres = playerMeta?.genres.orEmpty(),
                country = playerMeta?.country,
            )
        }
        val resolvedAutoEngine = resolvedAutoEngineState.value

        if (playerSurfaceSourceUrl != null) {
            key(playbackKey) {
                val active = remember { mutableStateOf(true) }
                DisposableEffect(Unit) {
                    onDispose { active.value = false }
                }
                PlatformPlayerSurface(
                    sourceUrl = playerSurfaceSourceUrl,
                    sourceAudioUrl = activeSourceAudioUrl,
                    sourceHeaders = activeSourceHeaders,
                    sourceResponseHeaders = activeSourceResponseHeaders,
                    externalSubtitles = externalSubtitles,
                    streamType = activeStreamType,
                    modifier = videoModifier,
                    playWhenReady = shouldPlay,
                    initialPositionMs = activeInitialPositionMs.takeIf { it > 0L },
                    initialPositionRequestKey = initialPositionRequestKey,
                    resizeMode = if (partyPanelInset > 0.dp) PlayerResizeMode.Fit else resizeMode,
                    playbackEngine = playbackEngineOverride ?: resolvedAutoEngine,
                    onInitialPositionHandled = { key, handled ->
                        if (active.value && playbackKey == activePlaybackKey && key == currentInitialPositionRequestKey()) {
                            initialSeekApplied = handled
                        }
                    },
                    onControllerReady = { controller ->
                        if (active.value && playbackKey == activePlaybackKey) {
                            playerController = controller
                            playerControllerSourceUrl = activeSourceUrl
                        }
                    },
                    onSnapshot = { snapshot ->
                        if (!active.value || !updatePlaybackSnapshot(snapshot, playbackKey)) return@PlatformPlayerSurface
                        refreshAudioTracksIfChanged()
                        if (!snapshot.isLoading) initialLoadCompleted = true
                        if (snapshot.isEnded) {
                            shouldPlay = false
                            controlsVisible = !playerControlsLocked
                        }
                    },
                    onError = { message ->
                        if (!active.value || playbackKey != activePlaybackKey) return@PlatformPlayerSurface
                        if (message != null && tryRefreshExpiredLinkAfterError(message)) {
                            return@PlatformPlayerSurface
                        }
                        if (message != null && tryFallbackToNextSourceAfterError(message)) {
                            return@PlatformPlayerSurface
                        }
                        // Engine failover is decided here, driven by its own player setting;
                        // the engine's own decoder/MIME retries have already been tried.
                        if (message != null && maybeFailoverPlaybackEngine(resolvedAutoEngine)) {
                            return@PlatformPlayerSurface
                        }
                        errorMessage = message
                        if (message != null) {
                            scrubbingPositionMs = null
                            controlsVisible = !playerControlsLocked
                            removeFailedStreamFromCache()
                        }
                    },
                )
            }
        }

        if (learningMode && fold == null && !isInPip) {
            LearningSubtitlePanel(
                cues = PlayerLearningSubtitleState.cues,
                subtitleStyle = playerSettingsUiState.subtitleStyle,
                positionMs = displayedPositionMs,
                isPlaying = playbackSnapshot.isPlaying,
                onCueClick = { cue ->
                    playerController?.seekTo(cue.startTimeMs)
                    scrubbingPositionMs = null
                },
                onPlay = { playerController?.play(); shouldPlay = true },
                onPause = { playerController?.pause(); shouldPlay = false },
                onPauseAtCueEnd = { cue ->
                    playerController?.pause()
                    playerController?.seekTo(cue.endTimeMs)
                    scrubbingPositionMs = null
                    shouldPlay = false
                },
                onPrevious = { cue ->
                    playerController?.seekTo(cue.startTimeMs)
                    scrubbingPositionMs = null
                },
                onNext = { cue ->
                    playerController?.seekTo(cue.startTimeMs)
                    scrubbingPositionMs = null
                },
                timelineContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                    ) {
                        PlayerTimeline(
                            snapshot = playbackSnapshot,
                            displayedPositionMs = displayedPositionMs,
                            onScrubChange = { positionMs ->
                                isScrubbingTimeline = true
                                scrubbingPositionMs = positionMs
                            },
                            onScrubFinished = { positionMs ->
                                val targetMs = if (isPartyControlLocked) ownSeekTarget(positionMs) else positionMs
                                if (targetMs == null) {
                                    isScrubbingTimeline = false
                                    scrubbingPositionMs = null
                                } else {
                                    finishTimelineScrub(targetMs)
                                    playerController?.seekTo(targetMs)
                                    scheduleProgressSyncAfterSeek()
                                    publishPartyPlayback(positionMs = targetMs)
                                }
                            },
                            chapters = chapters,
                        )
                        Text(
                            text = formatPlaybackRuntime(displayedPositionMs, playbackSnapshot.durationMs, showRemainingTime),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = (metrics.timeSize.value + 2).sp),
                            color = Color(0xFF505762),
                            modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 2.dp),
                        )
                    }
                },
                toolbarContent = {
                    PlayerControlActions(
                        playbackSnapshot = playbackSnapshot,
                        displayedPositionMs = displayedPositionMs,
                        showRemainingTime = showRemainingTime,
                        onRuntimeClick = { showRemainingTime = !showRemainingTime },
                        metrics = metrics,
                        resizeMode = resizeMode,
                        onSubtitleClick = {
                            refreshTracks()
                            showSubtitleModal = true
                        },
                        onAudioClick = {
                            refreshTracks()
                            showAudioModal = true
                        },
                        onSourcesClick = if (activeVideoId != null) { { openSourcesPanel() } } else null,
                        onEpisodesClick = if (isSeries) { { openEpisodesPanel() } } else null,
                        onChaptersClick = if (chapters.isNotEmpty()) { { openChaptersPanel() } } else null,
                        onNextEpisodeClick = if (nextEpisodeInfo?.hasAired == true && !nextEpisodeAutoPlaySearching && nextEpisodeAutoPlayCountdown == null) {
                            { if (!partyBlocksControl()) playNextEpisode() }
                        } else null,
                        onSpeedClick = { cyclePlaybackSpeed() },
                        onResizeModeClick = { cycleResizeMode() },
                        onVideoSettingsClick = if (isIos) { { showVideoSettingsModal = true; controlsVisible = true } } else null,
                        onOpenInExternalPlayer = null,
                        onStreamInfoClick = { openStreamInfo() },
                        onSubmitIntroClick = if (isSeries && playerSettingsUiState.introSubmitEnabled && playerSettingsUiState.introDbApiKey.isNotBlank()) {
                            { showSubmitIntroModal = true }
                        } else null,
                        onInteraction = { controlsActivityTick += 1 },
                        showRuntime = false,
                        iconTint = Color(0xFF343A43),
                        showResizeModeAction = false,
                        expandAllActions = true,
                        onSubtitleAutomationSettingsClick = { showSubtitleAutomationSettingsDialog = true },
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((containerHeight - learningVideoHeight).coerceAtLeast(0.dp)),
            )
        }

        AnimatedVisibility(
            visible = playerSettingsUiState.pauseOverlayEnabled && pausedOverlayVisible && !controlsVisible && !playerControlsLocked && fold == null && !learningMode,
            enter = fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            PauseMetadataOverlay(
                title = title,
                logo = logo,
                isEpisode = isEpisode,
                seasonNumber = activeSeasonNumber,
                episodeNumber = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                pauseDescription = activePauseDescription ?: activeStreamSubtitle,
                providerName = activeProviderName,
                metrics = metrics,
                horizontalSafePadding = horizontalSafePadding,
                modifier = Modifier.fillMaxSize(),
            )
        }

        RenderPlayerControls(
            displayedPositionMs = displayedPositionMs,
            isEpisode = isEpisode,
            folded = fold != null,
            learningMode = learningMode && fold == null && !isInPip,
            learningVideoHeight = learningVideoHeight,
            modifier = panelModifier,
        )
        Box(modifier = videoModifier) {
            RenderPlaybackOverlays(
                runtime = runtime,
                displayedPositionMs = displayedPositionMs,
                currentGestureFeedback = currentGestureFeedback,
                p2pInitialLoadingMessage = p2pInitialLoadingMessage,
                p2pInitialLoadingProgress = p2pInitialLoadingProgress,
                showP2pRebufferStats = showP2pRebufferStats,
                p2pRebufferMessage = p2pRebufferMessage,
                p2pRebufferProgress = p2pRebufferProgress,
                folded = fold != null,
            )
        }
    }
    Box(modifier = panelModifier) {
        RenderParty(
            runtime = runtime,
            panelWidth = partyPanelWidth,
            modal = !dockPartyPanel,
            panelVisible = showPartyPanel && !isInPip,
        )
        RenderPlayerModals(displayedPositionMs = displayedPositionMs)
    }

    if (showSubtitleAutomationSettingsDialog) {
        Dialog(onDismissRequest = { showSubtitleAutomationSettingsDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("字幕设置", style = MaterialTheme.typography.titleLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("双语合并", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "自动读取并合并内嵌双语字幕",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = bilingualMergeSwitch,
                            onCheckedChange = { bilingualMergeSwitch = it },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动翻译", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "自动翻译字幕内容（暂未启用功能）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = automaticTranslationSwitch,
                            onCheckedChange = { automaticTranslationSwitch = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun p2pConnectingPhaseLabel(phase: String): String = when (phase) {
    "add_magnet" -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_fetching_metadata,
    )
    "prepare_stream", "attach_route" -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_preparing_stream,
    )
    else -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
    )
}

private val PlayerScreenRuntime.activeAddonLogo: String?
    get() = addonsUiState.addons.firstNotNullOfOrNull { addon ->
        addon.manifest?.takeIf { addon.streamAddonInstanceId(it.id) == activeProviderAddonId }?.logoUrl
    }

private fun PlayerScreenRuntime.currentInitialPositionRequestKey(): String? {
    val positionMs = activeInitialPositionMs.takeIf { it > 0L } ?: return null
    return "$activePlaybackIdentity:${activeVideoId.orEmpty()}:$positionMs"
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerControls(
    displayedPositionMs: Long,
    isEpisode: Boolean,
    folded: Boolean,
    learningMode: Boolean,
    learningVideoHeight: Dp,
    modifier: Modifier,
) {
    val isInPip = rememberIsInPictureInPicture()
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val partyActive = partyState.party != null
    val playerBadges = rememberPlayerBadges()
    LaunchedEffect(folded) {
        if (folded) {
            controlsVisible = true
            showParentalGuide = false
        }
    }
    AnimatedVisibility(
        visible = (controlsVisible || folded || showParentalGuide) && !playerControlsLocked && !isInPip &&
            !(folded && isPanelOpen),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerControlsShell(
            title = title,
            streamTitle = activeStreamTitle,
            providerName = activeProviderName,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            resizeMode = resizeMode,
            isLocked = playerControlsLocked,
            useLegacyLayout = playerSettingsUiState.useLegacyPlayerLayout,
            showRemainingTime = showRemainingTime,
            onRuntimeClick = { showRemainingTime = !showRemainingTime },
            releaseInfo = metaUiState.meta?.takeIf { it.id == parentMetaId }?.releaseInfo,
            badges = playerBadges,
            hideDetails = activeSkipInterval != null && !skipIntervalDismissed,
            onNextEpisodeClick = if (nextEpisodeInfo?.hasAired == true && !nextEpisodeAutoPlaySearching && nextEpisodeAutoPlayCountdown == null) {
                {
                    if (!partyBlocksControl()) playNextEpisode()
                }
            } else null,
            onInteraction = { controlsActivityTick += 1 },
            showPlaybackControls = controlsVisible,
            onLockToggle = {
                if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
            },
            onBack = {
                flushWatchProgress()
                args.onBack()
            },
            onPartyClick = if (PartyRepository.available.collectAsStateWithLifecycle().value) {
                { openPartyPanel() }
            } else {
                null
            },
            partyActive = partyActive,
            onTogglePlayback = { togglePlayback() },
            onSeekBack = { seekBy(-10_000L) },
            onSeekForward = { seekBy(10_000L) },
            onResizeModeClick = { cycleResizeMode() },
            onSpeedClick = { cyclePlaybackSpeed() },
            onSubtitleClick = {
                refreshTracks()
                showSubtitleModal = true
            },
            onAudioClick = {
                refreshTracks()
                showAudioModal = true
            },
            onVideoSettingsClick = if (isIos) {
                {
                    showVideoSettingsModal = true
                    controlsVisible = true
                }
            } else {
                null
            },
            onSourcesClick = if (activeVideoId != null) { { openSourcesPanel() } } else null,
            onEpisodesClick = if (isSeries) { { openEpisodesPanel() } } else null,
            chapters = chapters,
            onChaptersClick = if (chapters.isNotEmpty()) { { openChaptersPanel() } } else null,
            onOpenInExternalPlayer = args.onOpenInExternalPlayer
                ?.takeUnless { ServerStreams.isServerSourceId(activeProviderAddonId) }
                ?.let { openExternal ->
                {
                    val loadedSubtitles = addonSubtitles
                        .takeIf { it.isNotEmpty() }
                        ?.map { sub ->
                            SubtitleInput(
                                url = sub.url,
                                name = buildString {
                                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                                    append(sub.display)
                                },
                                lang = sub.language,
                            )
                        }
                    PlayerStreamsRepository.pauseSearchForPlayback()
                    openExternal(
                        ExternalPlayerPlaybackRequest(
                            sourceUrl = activeSourceUrl,
                            title = title,
                            streamTitle = activeStreamTitle,
                            sourceHeaders = activeSourceHeaders,
                            resumePositionMs = playbackSnapshot.positionMs,
                            durationMs = playbackSnapshot.durationMs.takeIf { it > 0L },
                            playbackSession = playbackSession,
                            subtitles = loadedSubtitles,
                            season = activeSeasonNumber,
                            episode = activeEpisodeNumber,
                            episodeTitle = activeEpisodeTitle,
                        ),
                    )
                }
            },
            onSubmitIntroClick = if (
                isSeries &&
                playerSettingsUiState.introSubmitEnabled &&
                playerSettingsUiState.introDbApiKey.isNotBlank()
            ) {
                { showSubmitIntroModal = true }
            } else {
                null
            },
            onSwitchEngineClick = if (playerController?.playbackEngine != null) {
                { showSwitchEngineConfirm = true }
            } else {
                null
            },
            onLearningModeClick = if (
                !isIos &&
                !playerSettingsUiState.externalPlayerEnabled &&
                playerSettingsUiState.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv &&
                !isInPip
            ) {
                { PlayerSettingsRepository.setEnglishLearningEnabled(!learningMode) }
            } else {
                null
            },
            onStreamInfoClick = { openStreamInfo() },
            parentalWarnings = parentalWarnings,
            showParentalGuide = showParentalGuide,
            onParentalGuideAnimationComplete = { showParentalGuide = false },
            onScrubChange = { positionMs ->
                isScrubbingTimeline = true
                scrubbingPositionMs = positionMs
            },
            onScrubFinished = { positionMs ->
                val targetMs = if (isPartyControlLocked) ownSeekTarget(positionMs) else positionMs
                if (targetMs == null) {
                    isScrubbingTimeline = false
                    scrubbingPositionMs = null
                } else {
                    finishTimelineScrub(targetMs)
                    playerController?.seekTo(targetMs)
                    scheduleProgressSyncAfterSeek()
                    publishPartyPlayback(positionMs = targetMs)
                }
            },
            horizontalSafePadding = horizontalSafePadding,
            centerControlsAreaHeight = learningVideoHeight.takeIf { learningMode },
            modifier = modifier,
            foldLayout = folded,
            gestureController = gestureController,
            onWake = {
                controlsVisible = true
                controlsActivityTick += 1
            },
        )
    }
    AnimatedVisibility(
        visible = folded && playerControlsLocked && !isInPip,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        FoldLockedControls(
            onUnlock = { unlockPlayerControls() },
            modifier = modifier,
        )
    }
}

@Composable
private fun BoxScope.RenderPlaybackOverlays(
    runtime: PlayerScreenRuntime,
    displayedPositionMs: Long,
    currentGestureFeedback: GestureFeedbackState?,
    p2pInitialLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
    folded: Boolean,
) {
    runtime.run {
        PlayerPlaybackOverlays(
            playerControlsLocked = playerControlsLocked,
            useLegacyLayout = playerSettingsUiState.useLegacyPlayerLayout,
            lockedOverlayVisible = lockedOverlayVisible && !folded,
            showRemainingTime = showRemainingTime,
            playbackSnapshot = playbackSnapshot,
        displayedPositionMs = displayedPositionMs,
        metrics = metrics,
        horizontalSafePadding = horizontalSafePadding,
        onUnlock = { unlockPlayerControls() },
        showOpeningOverlay = playerSettingsUiState.showLoadingOverlay && !initialLoadCompleted && errorMessage == null,
        backdropArtwork = background ?: poster,
        logo = logo,
        title = title,
        onBackWithProgress = {
            flushWatchProgress()
            args.onBack()
        },
        openingLoadingMessage = if (playerSettingsUiState.showPlayerLoadingStatus) {
            p2pInitialLoadingMessage ?: playerLoadingStatusMessage(
                showStatus = true,
                controllerReady = playerController != null,
                snapshot = playbackSnapshot,
            )
        } else null,
        p2pInitialLoadingProgress = p2pInitialLoadingProgress,
        showP2pRebufferStats = showP2pRebufferStats,
        p2pRebufferMessage = p2pRebufferMessage,
        p2pRebufferProgress = p2pRebufferProgress,
        currentGestureFeedback = currentGestureFeedback,
        renderedGestureFeedback = renderedGestureFeedback,
        initialLoadCompleted = initialLoadCompleted,
        pausedOverlayVisible = pausedOverlayVisible,
        activeSkipInterval = activeSkipInterval,
        skipsToPostCredits = activeSkipInterval?.internalSkipAction(skipIntervals, playbackSnapshot.durationMs)?.skipsToPostCredits == true,
        skipIntervalDismissed = skipIntervalDismissed,
        controlsVisible = controlsVisible,
        onSkipInterval = { interval ->
            interval.internalSkipAction(skipIntervals, playbackSnapshot.durationMs)?.let { action ->
                val durationMs = playbackSnapshot.durationMs
                val requestedMs = if (durationMs > 0L) action.targetMs.coerceAtMost(durationMs - 1) else action.targetMs
                val seekMs = if (isPartyControlLocked) ownSeekTarget(requestedMs) else requestedMs
                if (seekMs != null) {
                    playerController?.seekTo(seekMs)
                    scheduleProgressSyncAfterSeek()
                    publishPartyPlayback(positionMs = seekMs)
                    skipIntervalDismissed = true
                }
            }
        },
        onDismissSkipInterval = { skipIntervalDismissed = true },
        sliderEdgePadding = sliderEdgePadding,
        overlayBottomPadding = overlayBottomPadding,
        isSeries = isSeries,
        nextEpisodeInfo = nextEpisodeInfo,
        showNextEpisodeCard = showNextEpisodeCard,
        nextEpisodeAutoPlaySearching = nextEpisodeAutoPlaySearching,
        nextEpisodeAutoPlaySourceName = nextEpisodeAutoPlaySourceName,
        nextEpisodeAutoPlayCountdown = nextEpisodeAutoPlayCountdown,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        onPlayNextEpisode = {
            if (!partyBlocksControl()) playNextEpisode()
        },
        onDismissNextEpisode = {
            cancelNextEpisodeAutoPlay()
            nextEpisodeCardDismissed = true
            showNextEpisodeCard = false
        },
        errorMessage = errorMessage,
            onDismissError = {
                flushWatchProgress()
                args.onBack()
            },
        )
    }
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerModals(displayedPositionMs: Long) {
    PlayerChaptersPanel(
        visible = showChaptersPanel && chapters.isNotEmpty(),
        chapters = chapters,
        positionMs = displayedPositionMs,
        durationMs = playbackSnapshot.durationMs,
        onChapterSelected = { selectChapter(it) },
        onDismiss = {
            showChaptersPanel = false
            controlsVisible = true
        },
    )
    PlayerScreenModalHosts(
        pendingP2pSwitch = pendingP2pSwitch,
        onPendingP2pSwitchChanged = { pendingP2pSwitch = it },
        onP2pEpisodeStreamSelected = { stream, episode, isAutoPlay ->
            switchToP2pEpisodeStream(stream, episode, isAutoPlay)
        },
        onP2pSourceStreamSelected = { stream -> switchToP2pSourceStream(stream) },
        onNextEpisodeAutoPlaySearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onNextEpisodeAutoPlayCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeAutoPlaySourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        showAudioModal = showAudioModal,
        audioTracks = serverAudioTracks.ifEmpty { audioTracks },
        selectedAudioIndex = serverAudioTracks.firstOrNull { it.isSelected }?.index ?: selectedAudioIndex,
        onAudioTrackSelected = { index ->
            if (serverAudioTracks.isNotEmpty()) {
                selectServerAudioTrack(index)
            } else {
                selectedAudioIndex = index
                persistAudioPreference(audioTracks.firstOrNull { it.index == index })
                playerController?.selectAudioTrack(index)
            }
            scope.launch {
                kotlinx.coroutines.delay(200)
                showAudioModal = false
            }
        },
        onAudioModalDismissed = { showAudioModal = false },
        showAudioDelay = playerController?.supportsAudioDelay == true,
        audioDelayMs = audioDelayMs,
        onAudioDelayChanged = { delayMs -> setAudioDelay(delayMs) },
        showAudioAmplification = playerController?.supportsAudioAmplification == true,
        audioAmplificationDb = audioAmplificationDb,
        keepAudioAmplification = playerSettingsUiState.persistAudioAmplification,
        onAudioAmplificationChanged = { db -> setAudioAmplification(db) },
        onKeepAudioAmplificationChanged = { keep -> setKeepAudioAmplification(keep) },
        showCenterMix = centerMixAvailable,
        centerMixLevelDb = centerMixLevelDb,
        onCenterMixLevelChanged = { db -> setCenterMixLevel(db) },
        showSubtitleModal = showSubtitleModal,
        subtitleTracks = serverSubtitleTracks.ifEmpty { subtitleTracks },
        selectedSubtitleIndex = if (serverSubtitleTracks.isEmpty()) {
            selectedSubtitleIndex
        } else {
            serverSubtitleTracks.firstOrNull { it.isSelected }?.index ?: -1
        },
        addonSubtitles = visibleAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        subtitleAutoSyncState = subtitleAutoSyncState,
        onBuiltInSubtitleTrackSelected = { index ->
            if (serverSubtitleTracks.isNotEmpty() && index >= 0) {
                selectServerSubtitleTrack(index)
            } else {
                val wasCustom = useCustomSubtitles
                isUserExplicitSubtitleSelection = true
                preferredSubtitleSelectionApplied = true
                selectedSubtitleIndex = index
                selectedAddonSubtitleId = null
                useCustomSubtitles = false
                persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
                if (wasCustom) {
                    playerController?.clearExternalSubtitleAndSelect(index)
                } else {
                    playerController?.selectSubtitleTrack(index)
                }
                if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
            }
        },
        onAddonSubtitleSelected = { addon ->
            isUserExplicitSubtitleSelection = true
            selectedAddonSubtitleId = addon.selectionKey
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            preferredSubtitleSelectionApplied = true
            persistAddonSubtitlePreference(addon)
            if (playerSettingsUiState.englishLearningEnabled) {
                PlayerLearningSubtitleState.beginLoading(addon.display)
            }
            attachSelectedAddonSubtitleWithAutoSync(addon.url) // AutoSync hook
            if (hasBurnedInServerSubtitle) clearServerSubtitleTrack()
        },
        onFetchAddonSubtitles = { fetchAddonSubtitlesForActiveItem() },
        onSubtitleStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
        onSubtitleDelayChanged = { delayMs -> setSubtitleDelay(delayMs) },
        onSubtitleDelayReset = { setSubtitleDelay(0) },
        onAutoSyncCapture = { captureSubtitleAutoSyncTime() },
        onAutoSyncCueSelected = { cue -> applySubtitleAutoSyncCue(cue) },
        onAutoSyncReload = { loadSubtitleAutoSyncCues(force = true) },
        onSubtitleModalDismissed = { showSubtitleModal = false },
        showVideoSettingsModal = showVideoSettingsModal,
        playerSettings = playerSettingsUiState,
        onVideoSettingsChanged = {
            playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
        },
        onVideoSettingsModalDismissed = { showVideoSettingsModal = false },
        showSourcesPanel = showSourcesPanel,
        sourceStreamsState = sourceStreamsState,
        contentTitle = title,
        activeEpisodeTitle = activeEpisodeTitle,
        currentStream = PlayerCurrentStream(
            url = activeSourceUrl,
            name = activeStreamTitle,
            description = activeStreamSubtitle,
            addonId = activeProviderAddonId,
            infoHash = activeStream?.p2pInfoHash ?: activeTorrentInfoHash,
            fileIdx = activeStream?.p2pFileIdx ?: activeTorrentFileIdx,
            filename = activeSourceFilename,
        ),
        onSourceFilterSelected = PlayerStreamsRepository::selectSourceFilter,
        onSourceStreamSelected = { stream -> switchToSource(stream) },
        onReloadSources = {
            val vid = activeVideoId
            if (vid != null) {
                PlayerStreamsRepository.loadSources(
                    type = contentType ?: parentMetaType,
                    videoId = vid,
                    season = activeSeasonNumber,
                    episode = activeEpisodeNumber,
                    forceRefresh = true,
                )
            }
        },
        onSourcesPanelDismissed = {
            showSourcesPanel = false
            PlayerStreamsRepository.stopSourcesLoading()
            controlsVisible = true
        },
        isSeries = isSeries,
        showEpisodesPanel = showEpisodesPanel,
        allEpisodes = playerMetaVideos,
        parentMetaType = parentMetaType,
        parentMetaId = parentMetaId,
        activeSeasonNumber = activeSeasonNumber,
        activeEpisodeNumber = activeEpisodeNumber,
        watchProgressByVideoId = watchProgressUiState.byVideoIdForContent(parentMetaId),
        watchedKeys = watchedUiState.watchedKeys,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        episodeStreamsPanelState = episodeStreamsPanelState,
        episodeStreamsRepoState = episodeStreamsRepoState,
        onEpisodeSelectedForDownload = { episode ->
            selectDownloadedEpisodeForPlayback(
                parentMetaId = parentMetaId,
                episode = episode,
                onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
            )
        },
        onEpisodeStreamsRequested = { episode ->
            PlayerStreamsRepository.loadEpisodeStreams(
                type = contentType ?: parentMetaType,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
            )
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
        },
        onEpisodeStreamFilterSelected = PlayerStreamsRepository::selectEpisodeStreamsFilter,
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onBackToEpisodes = {
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        },
        onReloadEpisodeStreams = {
            val episode = episodeStreamsPanelState.selectedEpisode
            if (episode != null) {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            }
        },
        onEpisodesPanelDismissed = {
            showEpisodesPanel = false
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
            controlsVisible = true
        },
        showSubmitIntroModal = showSubmitIntroModal,
        activeVideoId = activeVideoId,
        metaUiState = metaUiState,
        displayedPositionMs = displayedPositionMs,
        submitIntroSegmentType = submitIntroSegmentType,
        onSubmitIntroSegmentTypeChanged = { submitIntroSegmentType = it },
        submitIntroStartTimeStr = submitIntroStartTimeStr,
        onSubmitIntroStartTimeChanged = { submitIntroStartTimeStr = it },
        submitIntroEndTimeStr = submitIntroEndTimeStr,
        onSubmitIntroEndTimeChanged = { submitIntroEndTimeStr = it },
        onSubmitIntroDismissed = { showSubmitIntroModal = false },
        onSubmitIntroSuccess = {
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            showSubmitIntroModal = false
        },
    )
    StreamInfoOverlay(
        visible = showStreamInfo,
        addonName = activeProviderName,
        addonLogo = activeAddonLogo,
        streamName = activeStreamTitle,
        streamDescription = activeStreamSubtitle,
        playbackEngine = playerController?.playbackEngine,
        serverPlayback = serverPlaybackSummary(),
        mediaInfo = streamMediaInfo,
        audioTrack = audioTracks.firstOrNull { it.index == selectedAudioIndex },
        subtitleTrack = subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }.takeIf { !useCustomSubtitles },
        addonSubtitle = selectedAddonSubtitle.takeIf { useCustomSubtitles },
        onDismiss = { showStreamInfo = false },
    )
    val switchEngineTarget = playerController?.playbackEngine?.switchTarget
    if (switchEngineTarget != null) {
        NuvioStatusModal(
            isVisible = showSwitchEngineConfirm,
            title = stringResource(Res.string.player_engine_switch_title, switchEngineTarget.label),
            message = stringResource(Res.string.player_engine_switch_message),
            confirmText = stringResource(Res.string.action_switch),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                showSwitchEngineConfirm = false
                switchPlaybackEngine()
            },
            onDismiss = { showSwitchEngineConfirm = false },
        )
    }
}


@Composable
private fun LearningSubtitlePanel(
    cues: List<LearningSubtitleCue>,
    subtitleStyle: SubtitleStyleState,
    positionMs: Long,
    isPlaying: Boolean,
    onCueClick: (LearningSubtitleCue) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPauseAtCueEnd: (LearningSubtitleCue) -> Unit,
    onPrevious: (LearningSubtitleCue) -> Unit,
    onNext: (LearningSubtitleCue) -> Unit,
    timelineContent: @Composable () -> Unit,
    toolbarContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefixMaxEndMs = remember(cues) {
        buildLearningSubtitlePrefixMaxEndMs(cues)
    }
    val activeIndex = remember(cues, prefixMaxEndMs, positionMs) {
        findLearningSubtitleIndex(cues, prefixMaxEndMs, positionMs)
    }
    // Auto-follow only the subtitle that is active at the current playback position.
    // Do not scroll to the nearest loaded cue during gaps: while cache batches are arriving,
    // that fallback can point at an older cue (for example 1:00 instead of restored 3:00).
    val scrollTargetIndex = activeIndex
    // Keep the target tied to cue identity, not its mutable list index. Earlier cached cues
    // may be inserted before it; stable LazyColumn keys preserve its visible position.
    val scrollTargetCue = cues.getOrNull(scrollTargetIndex)
    val listState = rememberLazyListState()
    val controlScope = rememberCoroutineScope()
    var repeatCurrentCue by remember { mutableStateOf(false) }
    var repeatDelayMs by remember { mutableStateOf(1_000L) }
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var repeatCueStartMs by remember { mutableStateOf<Long?>(null) }
    val repeatCue = repeatCueStartMs?.let { startMs -> cues.firstOrNull { it.startTimeMs == startMs } }
    val currentLineNumber = if (activeIndex >= 0) {
        (activeIndex + 1).coerceIn(1, cues.size)
    } else {
        0
    }
    LaunchedEffect(
        scrollTargetCue?.startTimeMs,
        scrollTargetCue?.endTimeMs,
        scrollTargetCue?.text,
    ) {
        if (scrollTargetCue != null && scrollTargetIndex >= 0) {
            listState.animateScrollToItem(scrollTargetIndex)
        }
    }
    LaunchedEffect(repeatCurrentCue, repeatCueStartMs, repeatCue?.endTimeMs, positionMs, isPlaying) {
        if (!repeatCurrentCue || !isPlaying || repeatJob != null) return@LaunchedEffect
        val cue = repeatCue ?: return@LaunchedEffect
        // Do not wait for a playback-position snapshot to cross the cue boundary:
        // snapshots can arrive late enough for the next subtitle to become audible.
        // Schedule the stop from the remaining cue duration instead.
        val playbackPositionMs = maxOf(positionMs, cue.startTimeMs)
        val remainingCueMs = (cue.endTimeMs - playbackPositionMs).coerceAtLeast(0L)
        repeatJob = controlScope.launch {
            // Stop slightly before the boundary to absorb coroutine/player command
            // latency, then seek to the exact cue end while paused.
            delay((remainingCueMs - 80L).coerceAtLeast(0L))
            onPauseAtCueEnd(cue)
            delay(repeatDelayMs)
            if (repeatCurrentCue && repeatCueStartMs == cue.startTimeMs) {
                onCueClick(cue)
                onPlay()
            }
            repeatJob = null
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFFF3F4F6))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val loadingSubtitleName = PlayerLearningSubtitleState.loadingSubtitleName
        val emptyMessage = when (PlayerLearningSubtitleState.status) {
            LearningSubtitleStatus.NONE -> "Choose a subtitle in the player's subtitle menu. The list follows your selection."
            LearningSubtitleStatus.LOADING -> loadingSubtitleName?.let { "Loading subtitle: $it" }
                ?: "Loading the selected subtitle..."
            LearningSubtitleStatus.READY -> ""
            LearningSubtitleStatus.PARTIAL -> ""
            LearningSubtitleStatus.UNSUPPORTED -> "The selected track cannot currently be extracted as a complete subtitle list. Try an SRT or VTT external subtitle."
            LearningSubtitleStatus.ERROR -> "The selected subtitle could not be read. Check the subtitle source and try selecting it again."
        }
        Text(
            text = when {
                cues.isEmpty() && PlayerLearningSubtitleState.status == LearningSubtitleStatus.LOADING ->
                    "Language Learning · Loading: ${loadingSubtitleName ?: "subtitle"}"
                cues.isEmpty() -> "Language Learning · Subtitles"
                PlayerLearningSubtitleState.status == LearningSubtitleStatus.PARTIAL ->
                    "Language Learning · $currentLineNumber/${cues.size} lines · live capture"
                else -> "Language Learning · $currentLineNumber/${cues.size} lines"
            },
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF262A30),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        if (cues.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8BEC8),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = cues,
                    key = { _, cue -> "${cue.startTimeMs}-${cue.endTimeMs}-${cue.text}" },
                ) { index, cue ->
                    val subtitleLines = cue.displayText.lines().filter { it.isNotBlank() }
                    val highlightableLines = remember(subtitleLines) {
                        learningSubtitleHighlightableLines(subtitleLines)
                    }
                    val sourceText = remember(subtitleLines, highlightableLines) {
                        subtitleLines.filterIndexed { lineIndex, _ -> highlightableLines[lineIndex] }
                            .joinToString("\n")
                    }
                    val activeWordRange = if (index == activeIndex && sourceText.isNotBlank()) {
                        findLearningSubtitleWordRange(
                            text = sourceText,
                            startTimeMs = cue.startTimeMs,
                            endTimeMs = cue.endTimeMs,
                            positionMs = positionMs,
                        )
                    } else {
                        null
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFFFFFFF))
                            .border(
                                width = if (index == activeIndex) 1.5.dp else 1.dp,
                                color = if (index == activeIndex) Color(0xFFB8C9DC) else Color(0xFFE1E3E7),
                                shape = RoundedCornerShape(20.dp),
                            )
                            .clickable {
                                repeatJob?.cancel()
                                repeatJob = null
                                repeatCueStartMs = cue.startTimeMs
                                onCueClick(cue)
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatLearningSubtitleTime(cue.startTimeMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF386CA8),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "#${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9299A3),
                            )
                        }
                        var sourceTextOffset = 0
                        subtitleLines.forEachIndexed { lineIndex, subtitleLine ->
                            val lineIsHighlightable = highlightableLines.getOrElse(lineIndex) { false }
                            val currentLineStart = sourceTextOffset
                            if (lineIsHighlightable) sourceTextOffset += subtitleLine.length + 1
                            val wordMatches = remember(subtitleLine) {
                                Regex("\\S+").findAll(subtitleLine).toList()
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                wordMatches.forEach { match ->
                                    val globalWordStart = currentLineStart + match.range.first
                                    val globalWordEnd = currentLineStart + match.range.last
                                    val wordIsActive = lineIsHighlightable &&
                                        activeWordRange != null &&
                                        globalWordStart <= activeWordRange.last &&
                                        globalWordEnd >= activeWordRange.first
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .border(
                                                width = 1.5.dp,
                                                color = if (wordIsActive) Color(0xFF3978C5) else Color.Transparent,
                                                shape = RoundedCornerShape(7.dp),
                                            )
                                            .padding(horizontal = 2.dp, vertical = 0.dp),
                                    ) {
                                        Text(
                                            text = match.value,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontFamily = rememberSubtitleFontFamily(subtitleStyle.appearance.fontId),
                                                fontSize = (subtitleStyle.fontSizeSp - if (lineIsHighlightable) 0f else 2f).coerceAtLeast(1f).sp,
                                                fontWeight = if (subtitleStyle.bold) FontWeight.Bold else FontWeight.Normal,
                                            ),
                                            color = if (lineIsHighlightable) Color(0xFF25282D) else Color(0xFF50545B),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        timelineContent()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (repeatCurrentCue) Color(0xFFE2ECF8) else Color.Transparent)
                    .clickable {
                        repeatCurrentCue = !repeatCurrentCue
                        repeatJob?.cancel()
                        repeatJob = null
                        repeatCueStartMs = if (repeatCurrentCue) {
                            cues.getOrNull(activeIndex.takeIf { it >= 0 } ?: scrollTargetIndex)?.startTimeMs
                        } else null
                    },
                contentAlignment = Alignment.Center,
            ) {
                val repeatTint = if (repeatCurrentCue) Color(0xFF3978C5) else Color(0xFF343A43)
                Icon(
                    painter = appIconPainter(AppIconResource.PlayerRepeatOne),
                    contentDescription = "Repeat current sentence",
                    tint = repeatTint,
                    modifier = Modifier.size(30.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = cues.isNotEmpty()) {
                            val index = findLearningSubtitleScrollIndex(cues, positionMs)
                            cues.getOrNull((index - 1).coerceAtLeast(0))?.let { cue ->
                                repeatJob?.cancel()
                                repeatJob = null
                                repeatCueStartMs = cue.startTimeMs
                                onPrevious(cue)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous sentence", tint = Color(0xFF343A43), modifier = Modifier.size(34.dp))
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3978C5))
                        .clickable {
                            if (isPlaying) {
                                repeatJob?.cancel()
                                repeatJob = null
                                onPause()
                            } else {
                                repeatJob?.cancel()
                                repeatJob = null
                                onPlay()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = cues.isNotEmpty()) {
                            repeatJob?.cancel()
                            repeatJob = null
                            val index = findLearningSubtitleScrollIndex(cues, positionMs)
                            cues.getOrNull((index + 1).coerceAtMost(cues.lastIndex))?.let { cue ->
                                repeatCueStartMs = cue.startTimeMs
                                onNext(cue)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next sentence", tint = Color(0xFF343A43), modifier = Modifier.size(34.dp))
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(enabled = cues.isNotEmpty() && !PlayerLearningSubtitleState.isTranslating) {
                        controlScope.launch { PlayerLearningSubtitleState.translateManually(positionMs) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (PlayerLearningSubtitleState.isTranslating) {
                    Box(
                        modifier = Modifier.size(19.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${PlayerLearningSubtitleState.translationProgressPercent}%",
                            modifier = Modifier.offset(y = (-0.5).dp),
                            fontSize = 7.sp,
                            lineHeight = 7.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                } else {
                    TranslationGlyph(
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        surface = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }

        PlayerLearningSubtitleState.translationMessage?.let { message ->
            val messageGeneration = PlayerLearningSubtitleState.translationMessageGeneration
            LaunchedEffect(message, messageGeneration) {
                delay(5_000L)
                PlayerLearningSubtitleState.dismissTranslationMessage(message, messageGeneration)
            }
            Text(text = message, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB45309), modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(2.dp))
        toolbarContent()
    }
}

@Composable
private fun TranslationGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    surface: Color,
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * sx, y * sy)
        val outline = 1.35.dp.toPx()
        val roundCap = androidx.compose.ui.graphics.StrokeCap.Round

        // Rear translation card: a thin rounded outline, partially hidden by the
        // foreground language card, matching the supplied two-card mark.
        drawRoundRect(
            color = tint,
            topLeft = p(8.9f, 5.0f),
            size = androidx.compose.ui.geometry.Size(12.8f * sx, 16.0f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.0f * sx, 2.0f * sy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = outline),
        )

        // 文: the short top stroke, long crossbar and two descending strokes.
        drawLine(tint, p(16.1f, 6.9f), p(16.1f, 8.7f), outline, cap = roundCap)
        drawLine(tint, p(13.0f, 9.1f), p(19.4f, 9.1f), outline, cap = roundCap)
        val leftTextStroke = Path().apply {
            moveTo(14.0f * sx, 10.5f * sy)
            cubicTo(14.7f * sx, 12.7f * sy, 16.0f * sx, 14.8f * sy, 18.6f * sx, 16.0f * sy)
        }
        drawPath(leftTextStroke, color = tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = outline, cap = roundCap))
        val rightTextStroke = Path().apply {
            moveTo(18.6f * sx, 10.5f * sy)
            cubicTo(17.8f * sx, 12.8f * sy, 16.2f * sx, 14.8f * sy, 14.5f * sx, 16.0f * sy)
        }
        drawPath(rightTextStroke, color = tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = outline, cap = roundCap))

        // Foreground solid speech card, with the characteristic slanted right
        // edge and pointed lower-right tail from the supplied reference.
        val frontCard = Path().apply {
            moveTo(4.0f * sx, 1.4f * sy)
            lineTo(9.9f * sx, 1.4f * sy)
            cubicTo(10.8f * sx, 1.4f * sy, 11.3f * sx, 1.9f * sy, 11.6f * sx, 2.8f * sy)
            lineTo(14.2f * sx, 15.7f * sy)
            cubicTo(14.4f * sx, 16.5f * sy, 14.2f * sx, 17.1f * sy, 13.6f * sx, 17.8f * sy)
            lineTo(11.5f * sx, 20.1f * sy)
            lineTo(10.6f * sx, 16.9f * sy)
            lineTo(4.0f * sx, 16.9f * sy)
            cubicTo(2.4f * sx, 16.9f * sy, 1.5f * sx, 16.0f * sy, 1.5f * sx, 14.4f * sy)
            lineTo(1.5f * sx, 4.0f * sy)
            cubicTo(1.5f * sx, 2.4f * sy, 2.4f * sx, 1.4f * sy, 4.0f * sx, 1.4f * sy)
            close()
        }
        drawPath(frontCard, color = tint)

        // Cut the capital A out of the solid foreground card.
        val letterA = Path().apply {
            moveTo(3.0f * sx, 13.9f * sy)
            lineTo(5.9f * sx, 5.2f * sy)
            cubicTo(6.1f * sx, 4.7f * sy, 6.4f * sx, 4.5f * sy, 6.9f * sx, 4.5f * sy)
            lineTo(7.9f * sx, 4.5f * sy)
            cubicTo(8.3f * sx, 4.5f * sy, 8.6f * sx, 4.8f * sy, 8.8f * sx, 5.3f * sy)
            lineTo(11.8f * sx, 13.9f * sy)
            lineTo(9.7f * sx, 13.9f * sy)
            lineTo(9.0f * sx, 11.8f * sy)
            lineTo(5.6f * sx, 11.8f * sy)
            lineTo(4.9f * sx, 13.9f * sy)
            close()
        }
        drawPath(letterA, color = surface)
        // Counter of the A remains the foreground card color.
        val letterCounter = Path().apply {
            moveTo(6.1f * sx, 10.0f * sy)
            lineTo(7.3f * sx, 6.4f * sy)
            lineTo(8.5f * sx, 10.0f * sy)
            close()
        }
        drawPath(letterCounter, color = tint)
    }
}

private fun formatLearningSubtitleTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}