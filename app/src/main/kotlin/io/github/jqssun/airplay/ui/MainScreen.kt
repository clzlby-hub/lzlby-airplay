package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.gestures.BrightnessState
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.SeekGestureState
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalGesture
import io.github.jqssun.airplay.ui.gestures.VideoContentScale
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoPlayerGestures
import io.github.jqssun.airplay.ui.gestures.VolumeAndBrightnessGestureState
import io.github.jqssun.airplay.ui.gestures.ServerVolumeState
import io.github.jqssun.airplay.ui.gestures.ZoomState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.material3.MiniController
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit = {},
    onLocalNetwork: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember { mutableStateOf(false) }
    val pin by viewModel.pinCode.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val videoSessionPending by viewModel.videoSessionPending.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val autoFullscreen by viewModel.pref(Prefs.AUTO_FULLSCREEN).collectAsState()

    // don't use movableContentOf: moving AndroidView across subcomposition boundaries makes it crash on reparent
    val video: @Composable () -> Unit = {
        val aspect by viewModel.videoAspect.collectAsState()
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = aspect
        )
    }

    // fullscreen only once mirroring reports a size: connections rise before the kind is known
    var prevMirroringActive by remember { mutableStateOf(false) }
    LaunchedEffect(mirroringActive, audioOnly, videoPlaybackActive, pin) {
        val justStarted = !prevMirroringActive && mirroringActive
        prevMirroringActive = mirroringActive
        if (justStarted && !audioOnly && !videoPlaybackActive && autoFullscreen && pin == null) {
            fullscreen = true
        }
    }

    val activity = LocalContext.current as? Activity
    val hideOnDisconnect by viewModel.pref(Prefs.HIDE_ON_DISCONNECT).collectAsState()
    var hadConnectedSession by remember { mutableStateOf(false) }
    var hadVideoSession by remember { mutableStateOf(false) }
    var isVideoSwitching by remember { mutableStateOf(false) }

    // 监听视频播放状态：切集/切换节目时保持平滑过渡，防止 TV 桌面或指引卡片闪现
    LaunchedEffect(videoPlaybackActive) {
        if (videoPlaybackActive) {
            hadVideoSession = true
            isVideoSwitching = false
            fullscreen = false
        } else {
            fullscreen = false
            if (hadVideoSession && connections > 0) {
                isVideoSwitching = true
                delay(2500L)
                isVideoSwitching = false
            }
        }
    }

    // 监听投屏断开：采用 1.5 秒防抖，避免切集/重连瞬间 Activity 反复退后台又弹前台
    LaunchedEffect(connections) {
        if (connections > 0) {
            hadConnectedSession = true
        } else if (hadConnectedSession && hideOnDisconnect) {
            delay(1500L)
            if (viewModel.connectionCount.value == 0 && !viewModel.videoPlaybackActive.value && !viewModel.mirroringActive.value) {
                hadConnectedSession = false
                hadVideoSession = false
                isVideoSwitching = false
                fullscreen = false
                activity?.moveTaskToBack(true)
            }
        }
    }
    val videoScreen = videoPlaybackActive || videoSessionPending || isVideoSwitching
    LaunchedEffect(fullscreen, videoScreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen || videoScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (videoScreen) {
        val videoPlaybackAspect by viewModel.videoPlaybackAspect.collectAsState()
        val playback: @Composable () -> Unit = {
            VideoSurfaceView(
                onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                aspectRatio = videoPlaybackAspect
            )
        }
        if (isInPip) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                playback()
            }
            return
        }
        val overlayTick by viewModel.videoOverlayTick.collectAsState()
        val playing by viewModel.videoPlaying.collectAsState()
        val positionMs by viewModel.videoPositionMs.collectAsState()
        val durationMs by viewModel.videoDurationMs.collectAsState()
        val scrubPositionMs by viewModel.videoScrubPositionMs.collectAsState()
        val downloadProgress by viewModel.videoDownloadProgress.collectAsState()
        val scrubbing = scrubPositionMs != null
        val downloading = downloadProgress != null
        var overlayVisible by remember { mutableStateOf(false) }
        LaunchedEffect(overlayTick, playing, scrubbing, downloading, videoPlaybackActive, isVideoSwitching) {
            // 切集过渡期不显示控制层，保持纯净黑屏
            if (isVideoSwitching) {
                overlayVisible = false
            } else if (!videoPlaybackActive) {
                overlayVisible = true
            } else if (overlayTick == 0L) {
                overlayVisible = false
            } else {
                overlayVisible = true
                if (playing && !scrubbing && !downloading) {
                    delay(VIDEO_OVERLAY_HIDE_MS)
                    overlayVisible = false
                }
            }
        }
        val gestureScope = rememberCoroutineScope()
        val tapGestureState = remember(viewModel) { TapGestureState(viewModel, gestureScope) }
        val seekGestureState = remember(viewModel) { SeekGestureState(viewModel) }
        val context = LocalContext.current
        val serverVolume = remember { ServerVolumeState(context) }
        val brightnessState = remember(activity) { activity?.window?.let { BrightnessState(it) } }
        val volumeAndBrightnessGestureState = remember(serverVolume, brightnessState) {
            VolumeAndBrightnessGestureState(serverVolume, brightnessState, gestureScope)
        }
        val zoomState = remember(viewModel) { ZoomState(viewModel, gestureScope) }
        var controlsLocked by remember { mutableStateOf(false) }
        DisposableEffect(serverVolume) { serverVolume.handleLifecycle(this) }
        LaunchedEffect(overlayTick) {
            if (overlayTick == 0L) {
                zoomState.reset()
                controlsLocked = false
            }
        }
        DisposableEffect(brightnessState) {
            onDispose { brightnessState?.clearOverride() }
        }
        // orientation follows the video aspect; manual rotate holds until the next video
        LaunchedEffect(videoPlaybackAspect) {
            activity?.requestedOrientation = if (videoPlaybackAspect < 1f) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        DisposableEffect(activity) {
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        val videoPlaybackSize by viewModel.videoPlaybackSize.collectAsState()
        val buffering by viewModel.videoBuffering.collectAsState()
        val videoTitle by viewModel.videoTitle.collectAsState()
        val videoLocation by viewModel.videoLocation.collectAsState()
        val speed by viewModel.videoSpeed.collectAsState()
        val isPipSupported = remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }
        var showSpeedSelector by remember { mutableStateOf(false) }
        BackHandler { 
            viewModel.stopVideoPlayback()
            if (hideOnDisconnect) {
                activity?.moveTaskToBack(true)
            }
        }

        val rootFocusRequester = remember { FocusRequester() }
        val playPauseFocusRequester = remember { FocusRequester() }
        val unlockFocusRequester = remember { FocusRequester() }
        var isPlayPauseFocused by remember { mutableStateOf(false) }
        var isUnlockFocused by remember { mutableStateOf(false) }
        LaunchedEffect(overlayVisible, controlsLocked, showSpeedSelector) {
            if (showSpeedSelector) return@LaunchedEffect
            if (!overlayVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsLocked
            val target = if (locked) unlockFocusRequester else playPauseFocusRequester
            target.requestFocusUntilLanded(attempts = 20) { if (locked) isUnlockFocused else isPlayPauseFocused }
        }

        // dpad seeking (controls hidden): accumulate the skipped amount and briefly show it
        var dpadSeekOffsetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekTargetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekActive by remember { mutableStateOf(false) }
        var dpadSeekTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(dpadSeekTick) {
            if (!dpadSeekActive) return@LaunchedEffect
            delay(1000)
            dpadSeekActive = false
        }
        val showDpadSeekFeedback: (Long) -> Unit = { deltaMs ->
            if (!dpadSeekActive) dpadSeekOffsetMs = 0L
            dpadSeekOffsetMs += deltaMs
            dpadSeekTargetMs = (dpadSeekTargetMs.takeIf { dpadSeekActive } ?: positionMs).plus(deltaMs)
                .coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
            dpadSeekActive = true
            dpadSeekTick++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (showSpeedSelector) {
                        false
                    } else {
                        handlePlayerKeyEvent(
                            keyEvent = keyEvent,
                            controlsVisible = overlayVisible,
                            controlsLocked = controlsLocked,
                            isPlayPauseFocused = isPlayPauseFocused,
                            seekIncrementMs = TapGestureState.SEEK_INCREMENT_MS,
                            viewModel = viewModel,
                            showControls = { viewModel.showVideoOverlay() },
                            unlockControls = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            onDpadSeek = showDpadSeekFeedback,
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            VideoContentFrame(
                aspect = videoPlaybackAspect,
                videoSizePx = videoPlaybackSize,
                contentScale = zoomState.contentScale,
                zoom = zoomState.zoom
            ) { sizeModifier ->
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    applyAspectRatio = false,
                    modifier = sizeModifier
                )
            }
            VideoPlayerGestures(
                enabled = videoPlaybackActive,
                locked = controlsLocked,
                onTap = {
                    // a pending session pins the overlay; taps must not unpin it
                    if (!videoPlaybackActive) return@VideoPlayerGestures
                    if (overlayVisible) overlayVisible = false else viewModel.showVideoOverlay()
                },
                tapGestureState = tapGestureState,
                seekGestureState = seekGestureState,
                volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                zoomState = zoomState
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(72.dp))
            }
            DoubleTapIndicator(tapGestureState = tapGestureState)
            val seekAmountMs = seekGestureState.seekAmountMs
            when {
                seekAmountMs != null -> GestureInfoText(
                    info = "${if (seekAmountMs < 0) "-" else "+"}${formatVideoTime(abs(seekAmountMs))}\n" +
                        "[${formatVideoTime(seekGestureState.targetPositionMs ?: 0)}]",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.isZooming -> GestureInfoText(
                    info = "${(zoomState.zoom * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.showContentScaleIndicator -> GestureInfoText(
                    info = stringResource(zoomState.contentScale.nameRes()),
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    PlayPauseButton(
                        playing = playing,
                        onClick = { viewModel.toggleVideoPlayPause() },
                        modifier = Modifier
                            .focusRequester(playPauseFocusRequester)
                            .onFocusChanged { isPlayPauseFocused = it.hasFocus }
                    )
                }
            }
            DpadSeekIndicator(
                visible = dpadSeekActive && dpadSeekOffsetMs != 0L,
                offsetMs = dpadSeekOffsetMs,
                positionMs = dpadSeekTargetMs
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = serverVolume.percentage, icon = Icons.AutoMirrored.Rounded.VolumeUp)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = brightnessState?.percentage ?: 0, icon = Icons.Rounded.BrightnessHigh)
            }
            if (controlsLocked) {
                if (overlayVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp)
                    ) {
                        UnlockButton(
                            onClick = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            modifier = Modifier
                                .focusRequester(unlockFocusRequester)
                                .onFocusChanged { isUnlockFocused = it.hasFocus }
                        )
                    }
                }
            } else {
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    VideoControlsTop(
                        title = videoTitle,
                        videoUrl = videoLocation,
                        downloadProgress = downloadProgress,
                        showDownload = durationMs > 0,
                        onBackClick = { viewModel.stopVideoPlayback() },
                        onSpeedClick = {
                            overlayVisible = false
                            showSpeedSelector = true
                        },
                        onDownloadClick = { viewModel.toggleVideoDownload() }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    VideoControlsBottom(
                        positionMs = scrubPositionMs ?: positionMs,
                        durationMs = durationMs,
                        contentScale = zoomState.contentScale,
                        isPipSupported = isPipSupported,
                        onRotateClick = {
                            activity?.let {
                                it.requestedOrientation =
                                    if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                            }
                        },
                        onLockClick = {
                            viewModel.showVideoOverlay()
                            controlsLocked = true
                        },
                        onContentScaleClick = {
                            viewModel.showVideoOverlay()
                            zoomState.switchToNextContentScale()
                        },
                        onPipClick = onPip,
                        onSeek = { seekGestureState.onSeek(it) },
                        onSeekEnd = { seekGestureState.onSeekEnd() },
                        seekBarModifier = Modifier
                            .focusProperties { up = playPauseFocusRequester }
                            .dpadAdjust(
                                onLeft = {
                                    viewModel.seekVideoBy(-TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                },
                                onRight = {
                                    viewModel.seekVideoBy(TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                }
                            )
                    )
                }
            }
            val skipSilence by viewModel.videoSkipSilence.collectAsState()
            PlaybackSpeedSelector(
                show = showSpeedSelector,
                speed = speed,
                skipSilence = skipSilence,
                onSpeedChange = { viewModel.setVideoSpeed(it) },
                onSkipSilenceChange = { viewModel.setVideoSkipSilence(it) },
                onDismiss = { showSpeedSelector = false }
            )
        }
        return
    }

    // pip mode: show only the video surface
    if (isInPip) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            video()
        }
        return
    }

    // restore system bars when exiting pip back to non-fullscreen
    LaunchedEffect(isInPip) {
        if (!isInPip && !fullscreen) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // exit fullscreen while a pin is being shown so the dialog isn't covered
    LaunchedEffect(pin) {
        if (pin != null) fullscreen = false
    }

    val tv = isTv()
    if (fullscreen) {
        BackHandler {
            fullscreen = false
            if (connections == 0 || (tv && hideOnDisconnect)) {
                activity?.moveTaskToBack(true)
            }
        }
        FullscreenVideo(
            viewModel = viewModel,
            video = video,
            onExitFullscreen = {
                fullscreen = false
                if (connections == 0 || (tv && hideOnDisconnect)) {
                    activity?.moveTaskToBack(true)
                }
            },
            onPip = onPip
        )
    } else if (tv) {
        BackHandler {
            activity?.moveTaskToBack(true)
        }
        // 电视大屏：横向分栏，左侧垂直侧边栏，右侧主展示区
        Row(modifier = Modifier.fillMaxSize().background(Color(0xFF121418))) {
            NavigationRail(
                modifier = Modifier
                    .width(136.dp)
                    .fillMaxHeight(),
                containerColor = Color(0xFF1A1D24),
                header = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2A313E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Tv,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "AirPlay",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            ) {
                Spacer(Modifier.height(16.dp))
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null, modifier = Modifier.size(24.dp)) },
                        label = { Text(stringResource(t.labelRes), fontSize = 13.sp) },
                        modifier = Modifier
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .tvItemFocus(shape = RoundedCornerShape(12.dp))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF0F1115))
            ) {
                TabContent(
                    tab, viewModel, video,
                    onFullscreen = { fullscreen = true }, onPip = onPip, onLocalNetwork = onLocalNetwork,
                    showAudioMode = audioOnly
                )
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                Column {
                    AudioMiniController(
                        viewModel,
                        visible = audioOnly && connections > 0 && tab != Tab.OVERVIEW,
                        onClick = { tab = Tab.OVERVIEW }
                    )
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.labelRes)) },
                                modifier = Modifier.dpadFocus()
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                TabContent(
                    tab, viewModel, video,
                    onFullscreen = { fullscreen = true }, onPip = onPip, onLocalNetwork = onLocalNetwork,
                    showAudioMode = audioOnly
                )
            }
        }
    }

    // pin dialog
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(stringResource(R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }

}

@Composable
private fun TabContent(
    tab: Tab,
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean
) {
    when (tab) {
        Tab.OVERVIEW -> OverviewContent(
            viewModel, video,
            onFullscreen = onFullscreen, onPip = onPip, onLocalNetwork = onLocalNetwork, showAudioMode = showAudioMode
        )
        Tab.LOGS -> LogsScreen(viewModel)
        Tab.SETTINGS -> SettingsScreen(viewModel)
    }
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean = false
) {
    val state by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val grant = state != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val idlePreview by viewModel.pref(Prefs.IDLE_PREVIEW).collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val tv = isTv()
    val context = LocalContext.current
    val localIp = remember(context) { getLocalIpAddress(context) }
    val serverPort by viewModel.pref(Prefs.SERVER_PORT).collectAsState()
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tv) startFocus.requestFocus()
    }

    if (tv) {
        // 电视大屏定制概览布局
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // 顶部横条：设备名、状态、IP与启动控制按钮
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = serverName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.width(16.dp))
                            val isRunning = state == ServerState.RUNNING
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isRunning) Color(0xFF1E4620) else Color(0xFF3E2723))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isRunning) Color(0xFF4CAF50) else Color(0xFFEF5350))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isRunning) "服务运行中" else "服务已停止",
                                        color = if (isRunning) Color(0xFF81C784) else Color(0xFFE57373),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "本机 IP: $localIp : $serverPort",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp
                            )
                            if (connections > 0) {
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = "已连接 $connections 台设备",
                                    color = Color(0xFF64B5F6),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state == ServerState.RUNNING && (mirroringActive || connections > 0)) {
                            FilledTonalButton(
                                onClick = onFullscreen,
                                modifier = Modifier.tvItemFocus(shape = RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("进入全屏", fontSize = 14.sp)
                            }
                        }

                        val settings = localNetwork == LocalNetworkAccess.SETTINGS
                        val (icon, label, action) = when {
                            grant -> Triple(
                                if (settings) Icons.Default.Settings else Icons.Default.LockOpen,
                                if (settings) R.string.btn_open_settings else R.string.btn_allow,
                                onLocalNetwork
                            )
                            state == ServerState.RUNNING -> Triple(Icons.Default.Stop, R.string.btn_stop, viewModel::stopServer)
                            else -> Triple(Icons.Default.PlayArrow, R.string.btn_start, viewModel::startServer)
                        }
                        Button(
                            onClick = action,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state == ServerState.RUNNING) Color(0xFFC62828) else Color(0xFF1976D2)
                            ),
                            modifier = Modifier
                                .tvItemFocus(shape = RoundedCornerShape(12.dp))
                                .focusRequester(startFocus)
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 核心展示区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF161920)),
                contentAlignment = Alignment.Center
            ) {
                if (showAudioMode && state == ServerState.RUNNING && connections > 0) {
                    NowPlayingContent(viewModel)
                } else if (state == ServerState.RUNNING && (mirroringActive || idlePreview)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        video()
                        // 浮动全屏快捷提示
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "按遥控器 [OK] 键全屏播放",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else if (connections > 0) {
                    // 已有设备连接（等待视频流解析或切换节目中）：纯黑背景，彻底杜绝连接指引卡片闪现
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                } else {
                    // 等待连接：展示大气的三步指引
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = if (state == ServerState.RUNNING) "等待苹果设备连接投屏..." else "投屏服务已停止，请点击右上角启动",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 卡片 1
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF20242D))
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF2C3340)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Wifi, null, tint = Color(0xFF64B5F6), modifier = Modifier.size(26.dp))
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text("第 1 步：连入同一 Wi-Fi", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Spacer(Modifier.height(6.dp))
                                    Text("确保 iPhone/iPad/Mac 与本电视连入同一家庭局域网", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                                }
                            }

                            // 卡片 2
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF20242D))
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF2C3340)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Smartphone, null, tint = Color(0xFF81C784), modifier = Modifier.size(26.dp))
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text("第 2 步：打开控制中心", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Spacer(Modifier.height(6.dp))
                                    Text("从苹果设备屏幕右上角下拉，点击「屏幕镜像」图标", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                                }
                            }

                            // 卡片 3
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF20242D))
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF2C3340)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Tv, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(26.dp))
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text("第 3 步：选择本机名称", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Spacer(Modifier.height(6.dp))
                                    Text("在镜像列表中点击「$serverName」，即可秒速同屏！", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val connecting = state == ServerState.RUNNING && connections > 0 &&
                !mirroringActive && !videoPlaybackActive
            if (showAudioMode && state == ServerState.RUNNING && connections > 0) {
                NowPlayingContent(viewModel)
            } else {
                if (state == ServerState.RUNNING && (mirroringActive || idlePreview)) {
                    video()
                }
                if (state != ServerState.RUNNING || connections == 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (connections > 0) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (grant) stringResource(R.string.local_network_desc) else when (state) {
                                ServerState.STOPPED -> stringResource(R.string.server_stopped)
                                ServerState.RUNNING -> stringResource(R.string.waiting_for_connection)
                                ServerState.ERROR -> stringResource(R.string.error_starting_server)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else if (connecting) {
                    Text(
                        text = stringResource(R.string.waiting_for_playback),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (state == ServerState.RUNNING && mirroringActive) {
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                            Icon(
                                painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.dpadFocus()) {
                            Icon(
                                Icons.Default.Fullscreen, contentDescription = stringResource(R.string.cd_fullscreen),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            if (debugEnabled && connections > 0) {
                DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            var showRes by remember { mutableStateOf(false) }
            LaunchedEffect(videoResolution) {
                if (videoResolution.isNotEmpty() && !showAudioMode) {
                    showRes = true
                    delay(5000)
                    showRes = false
                }
            }
            if (!showAudioMode) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showRes && connections > 0,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                ) {
                    Text(
                        text = videoResolution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        val statusColor by animateColorAsState(
                            if (grant) MaterialTheme.colorScheme.error else when (state) {
                                ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                                ServerState.ERROR -> MaterialTheme.colorScheme.error
                                ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, label = "status"
                        )
                        Text(
                            text = if (grant) stringResource(R.string.local_network_label) else when (state) {
                                ServerState.RUNNING -> stringResource(R.string.connected_count, connections)
                                ServerState.ERROR -> stringResource(R.string.error_label)
                                ServerState.STOPPED -> stringResource(R.string.stopped_label)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }

                    val settings = localNetwork == LocalNetworkAccess.SETTINGS
                    val (icon, label, action) = when {
                        grant -> Triple(
                            if (settings) Icons.Default.Settings else Icons.Default.LockOpen,
                            if (settings) R.string.btn_open_settings else R.string.btn_allow,
                            onLocalNetwork
                        )
                        state == ServerState.RUNNING -> Triple(Icons.Default.Stop, R.string.btn_stop, viewModel::stopServer)
                        else -> Triple(Icons.Default.PlayArrow, R.string.btn_start, viewModel::startServer)
                    }
                    FilledTonalButton(
                        onClick = action,
                        modifier = Modifier.dpadFocus().focusRequester(startFocus)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenVideo(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onExitFullscreen: () -> Unit,
    onPip: () -> Unit
) {
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var tapTick by remember { mutableStateOf(0) }
    LaunchedEffect(tapTick) {
        controlsVisible = true
        delay(8000)
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { tapTick++ } },
        contentAlignment = Alignment.Center
    ) {
        video()
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                    Icon(
                        painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onExitFullscreen, modifier = Modifier.dpadFocus()) {
                    Icon(
                        Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.cd_exit_fullscreen),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (debugEnabled) {
            DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        var showRes by remember { mutableStateOf(false) }
        LaunchedEffect(videoResolution) {
            if (videoResolution.isNotEmpty()) {
                showRes = true
                delay(5000)
                showRes = false
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showRes,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) {
            Text(
                text = videoResolution,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

// dacp scanning is press-and-hold: beginff/beginrew while held, playresume on release
@Composable
private fun HoldScanButton(
    icon: ImageVector,
    contentDescription: String,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onBegin()
                    try {
                        awaitRelease()
                    } finally {
                        onEnd()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription)
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun AudioMiniController(viewModel: MainViewModel, visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    val player = viewModel.dacpPlayer ?: return
    val context = LocalContext.current
    MiniController(
        player,
        bitmapLoader = remember { DataSourceBitmapLoader.Builder(context).build() },
        onClick = onClick
    )
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun NowPlayingContent(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // cover art
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (track.coverArt != null) {
                Image(
                    bitmap = track.coverArt!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_cover_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // track info
        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (track.artist.isNotEmpty()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (track.album.isNotEmpty()) {
            Text(
                text = track.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))

        val player = viewModel.dacpPlayer
        if (player != null) {
            // BottomControls' default row, minus its video-overlay gradient
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PositionText(player, Modifier.padding(end = 8.dp))
                Box(modifier = Modifier.weight(1f)) { ProgressSlider(player) }
                DurationText(player, Modifier.padding(start = 8.dp))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HoldScanButton(
                    icon = Icons.Default.FastRewind,
                    contentDescription = stringResource(R.string.cd_rewind),
                    onBegin = { viewModel.audioScanBegin(false) },
                    onEnd = { viewModel.audioScanEnd() }
                )
                PreviousButton(player, modifier = Modifier.dpadFocus())
                PlayPauseButton(
                    player,
                    modifier = Modifier.size(63.dp).dpadFocus(CircleShape),
                    iconSize = 40.dp
                )
                NextButton(player, modifier = Modifier.dpadFocus())
                HoldScanButton(
                    icon = Icons.Default.FastForward,
                    contentDescription = stringResource(R.string.cd_fast_forward),
                    onBegin = { viewModel.audioScanBegin(true) },
                    onEnd = { viewModel.audioScanEnd() }
                )
            }

            Spacer(Modifier.height(8.dp))

            val context = LocalContext.current
            val serverVolume = remember { ServerVolumeState(context) }
            DisposableEffect(serverVolume) { serverVolume.handleLifecycle(this) }
            VolumeSlider(
                icon = painterResource(R.drawable.ic_download),
                label = stringResource(R.string.volume_server),
                value = serverVolume.percentage / 100f,
                onValueChange = { serverVolume.update((it * 100).roundToInt()) },
                onStep = { serverVolume.adjust(it > 0) }
            )

            val clientVolume by viewModel.clientVolume.collectAsState()
            val clientAdjustable by viewModel.clientVolumeAdjustable.collectAsState()
            // sender reports back only after each dacp step
            var clientDrag by remember { mutableStateOf<Float?>(null) }
            val clientShown = clientDrag ?: clientVolume
            VolumeSlider(
                icon = painterResource(R.drawable.ic_upload),
                label = stringResource(R.string.volume_client),
                value = clientShown,
                enabled = clientAdjustable,
                onValueChange = { clientDrag = it; viewModel.setClientVolume(it) },
                onValueChangeFinished = { clientDrag = null },
                onStep = { viewModel.setClientVolume((clientShown + it * CLIENT_VOLUME_STEP).coerceIn(0f, 1f)) }
            )
        }
    }
}

@Composable
private fun VolumeSlider(
    icon: Painter,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onStep: (Int) -> Unit,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier
                    .semantics { contentDescription = label }
                    .dpadFocus()
                    .dpadAdjust(onLeft = { onStep(-1) }, onRight = { onStep(1) })
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.volume_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 40.dp)
        )
    }
}

// sender has 16 volume steps above mute
private const val CLIENT_VOLUME_STEP = 1f / 16

private const val VIDEO_OVERLAY_HIDE_MS = 4000L

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val headingStyle = style.copy(fontWeight = FontWeight.Bold)
        val color = Color.White.copy(alpha = 0.9f)
        val headingColor = Color(0xFF80D8FF) // cyan
        val context = LocalContext.current
        debugOverlaySections(context, info).forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(section.title.uppercase(), style = headingStyle, color = headingColor)
            section.lines.forEach { Text(it, style = style, color = color) }
        }
    }
}

private data class DebugSection(val title: String, val lines: List<String>)

private fun debugOverlaySections(context: Context, info: DebugInfo): List<DebugSection> = buildList {
    buildList {
        if (info.videoCodec.isNotEmpty()) {
            add(context.getString(R.string.debug_video, info.videoCodec, info.videoRes))
            add(context.getString(R.string.debug_fps_bitrate, info.videoFps, info.bitrateStr))
            add(context.getString(R.string.debug_frames_drops, info.videoFrames, info.droppedFrames))
            add(context.getString(R.string.debug_jitter, info.jitterStr))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_video), it)) }

    buildList {
        if (info.audioCodec.isNotEmpty()) add(context.getString(R.string.debug_audio, info.audioCodec, info.serverVolume, info.clientVolume))
        info.audio?.let { a ->
            add(context.getString(R.string.debug_audio_buffer, a.backlogMs, a.tunedCushionMs))
            add(context.getString(R.string.debug_audio_glitch, a.trims, a.drops, a.silences, a.underruns, a.xrun))
            add(context.getString(R.string.debug_audio_decode, formatDecode(a.decodeMeanUs, a.decodeMaxUs, a.decodeHeld, a.decodeErrors)))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_audio), it)) }

    add(DebugSection(context.getString(R.string.debug_section_network), listOf(context.getString(R.string.debug_clients, info.connections))))
}

// "mean/max ms held=N", or "held=N" before first latency window lands
private fun formatDecode(meanUs: Int, maxUs: Int, held: Int, errors: Int): String =
    (if (meanUs == 0) "held=$held"
    else "%.1f/%.1f ms held=%d".format(meanUs / 1000.0, maxUs / 1000.0, held)) + " errs=$errors"
