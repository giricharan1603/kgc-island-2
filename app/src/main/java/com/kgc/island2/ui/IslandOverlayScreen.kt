package com.kgc.island2.ui

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun IslandOverlayScreen(
    onStatusBarSwipeDown: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleHotspot: () -> Unit,
    onToggleAutoRotate: () -> Unit,
    onToggleRingerMode: () -> Unit = {},
    onSetVolumeFraction: (Float) -> Unit = {},
    onSetBrightnessFraction: (Float) -> Unit = {},
    onAdjustVolume: (Float) -> Unit,
    onRequestKeyboardFocus: (Boolean) -> Unit,
    onPasteTextToFocusedApp: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val displayMode by IslandStateEngine.displayMode.collectAsState()
    val primaryPayload by IslandStateEngine.primarySlot.collectAsState()
    val secondaryPayload by IslandStateEngine.secondarySlot.collectAsState()
    val privacyState by IslandStateEngine.privacyState.collectAsState()
    val quickTileStates by IslandStateEngine.quickTileStates.collectAsState()
    val cutoutConfig by IslandStateEngine.cutoutConfig.collectAsState()
    val burnInOffset by IslandStateEngine.burnInOffset.collectAsState()
    val clipboardHistory by IslandStateEngine.clipboardHistory.collectAsState()
    val stashItems by IslandStateEngine.stashItems.collectAsState()
    val dragTargetApps by IslandStateEngine.dragTargetApps.collectAsState()

    val transition = updateTransition(targetState = displayMode, label = "island_morph")

    val totalWidth by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.72f, stiffness = 340f) },
        label = "width"
    ) { mode ->
        when (mode) {
            IslandDisplayMode.IDLE -> {
                if (privacyState.isCameraActive || privacyState.isMicActive) {
                    (cutoutConfig.idleCircleSizeDp + 18).dp
                } else {
                    cutoutConfig.idleCircleSizeDp.dp
                }
            }
            IslandDisplayMode.COMPACT_SINGLE -> 212.dp
            IslandDisplayMode.COMPACT_SPLIT -> 264.dp
            IslandDisplayMode.EXPANDED_CARD,
            IslandDisplayMode.ORIGIN_DRAG_WHEEL,
            IslandDisplayMode.QUICK_TOOLS -> 356.dp
        }
    }

    val totalHeight by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.72f, stiffness = 340f) },
        label = "height"
    ) { mode ->
        when (mode) {
            IslandDisplayMode.IDLE -> cutoutConfig.idleCircleSizeDp.dp
            IslandDisplayMode.COMPACT_SINGLE,
            IslandDisplayMode.COMPACT_SPLIT -> 36.dp
            IslandDisplayMode.EXPANDED_CARD,
            IslandDisplayMode.ORIGIN_DRAG_WHEEL,
            IslandDisplayMode.QUICK_TOOLS -> 218.dp
        }
    }

    val cornerRadius by transition.animateDp(
        transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 380f) },
        label = "corner"
    ) { mode ->
        when (mode) {
            IslandDisplayMode.EXPANDED_CARD,
            IslandDisplayMode.ORIGIN_DRAG_WHEEL,
            IslandDisplayMode.QUICK_TOOLS -> 32.dp
            else -> 50.dp
        }
    }

    // Feature 1: Transparent fill in IDLE mode so ONLY the circumference ring wraps around the camera hole!
    val containerBgColor by transition.animateColor(
        transitionSpec = { tween(220) },
        label = "bg_color"
    ) { mode ->
        if (mode == IslandDisplayMode.IDLE) Color.Transparent else Color.Black
    }

    val idleRingBrush = remember(primaryPayload, quickTileStates.isHotspotOn, privacyState) {
        when {
            privacyState.isCameraActive || privacyState.isMicActive -> {
                Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFFF97316)))
            }
            quickTileStates.isHotspotOn -> {
                Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF4ADE80)))
            }
            primaryPayload is IslandPayload.MediaActivity -> {
                val accent = (primaryPayload as IslandPayload.MediaActivity).accentColor
                Brush.linearGradient(listOf(accent, Color(0xFF38BDF8)))
            }
            else -> {
                Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFFA855F7)))
            }
        }
    }

    var cumulativeDx by remember { mutableFloatStateOf(0f) }
    var cumulativeDy by remember { mutableFloatStateOf(0f) }

    val gestureModifier = Modifier
        .pointerInput(displayMode, primaryPayload) {
            detectTapGestures(
                onTap = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    when (displayMode) {
                        IslandDisplayMode.IDLE -> IslandStateEngine.onIdleCircleTapped()
                        // Feature 2: Clicking compact pill expands the island to show song controls instead of opening Spotify!
                        IslandDisplayMode.COMPACT_SINGLE,
                        IslandDisplayMode.COMPACT_SPLIT -> IslandStateEngine.expandIsland()
                        else -> {}
                    }
                },
                onLongPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (displayMode == IslandDisplayMode.EXPANDED_CARD ||
                        displayMode == IslandDisplayMode.QUICK_TOOLS
                    ) {
                        onRequestKeyboardFocus(false)
                        IslandStateEngine.collapseIsland()
                    } else {
                        IslandStateEngine.expandIsland()
                    }
                }
            )
        }
        .pointerInput(displayMode) {
            detectDragGestures(
                onDragStart = {
                    cumulativeDx = 0f
                    cumulativeDy = 0f
                },
                onDragEnd = {
                    val isSwipeUp = cumulativeDy < -24f
                    val isHorizontalSwipe = abs(cumulativeDx) > 45f
                    val isDiagonalUp = abs(cumulativeDx) > 22f && cumulativeDy < -18f
                    val isSwipeDown = cumulativeDy > 42f

                    when {
                        isSwipeUp || isHorizontalSwipe || isDiagonalUp -> {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onRequestKeyboardFocus(false)
                            IslandStateEngine.handleDismissSwipe()
                        }
                        isSwipeDown && displayMode != IslandDisplayMode.EXPANDED_CARD -> {
                            onStatusBarSwipeDown()
                        }
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    cumulativeDx += dragAmount.x
                    cumulativeDy += dragAmount.y
                    if (displayMode == IslandDisplayMode.IDLE && abs(dragAmount.x) > 10f) {
                        onAdjustVolume(dragAmount.x)
                    }
                }
            )
        }

    if (displayMode == IslandDisplayMode.COMPACT_SPLIT) {
        Row(
            modifier = Modifier
                .size(totalWidth, totalHeight)
                .offset { burnInOffset }
                .then(gestureModifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Primary Pill: Tapping expands to big control card (never auto-opens Spotify app)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50.dp))
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(50.dp))
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        IslandStateEngine.expandIsland()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                CompactSingleView(
                    payload = primaryPayload,
                    privacyState = privacyState,
                    onToggleTorch = onToggleTorch
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right Detached Circle: Swaps Hotspot/2nd task or opens Quick Tools
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF334155), CircleShape)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        IslandStateEngine.swapPrimaryAndSecondary()
                    },
                contentAlignment = Alignment.Center
            ) {
                SideCircleContent(secondaryPayload = secondaryPayload)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(totalWidth, totalHeight)
                .offset { burnInOffset }
                .clip(RoundedCornerShape(cornerRadius))
                .background(containerBgColor)
                .border(
                    width = if (displayMode == IslandDisplayMode.IDLE) 2.2.dp else 1.dp,
                    brush = if (displayMode == IslandDisplayMode.IDLE) {
                        idleRingBrush
                    } else {
                        SolidColor(Color(0xFF1E293B))
                    },
                    shape = RoundedCornerShape(cornerRadius)
                )
                .then(gestureModifier)
                .padding(
                    horizontal = if (displayMode == IslandDisplayMode.IDLE) 0.dp else 12.dp,
                    vertical = if (displayMode == IslandDisplayMode.IDLE) 0.dp else 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            when (displayMode) {
                IslandDisplayMode.IDLE -> {
                    // Feature 1: Hollow Circumference Ring Only
                    IdleHollowCircumferenceRing(privacyState = privacyState)
                }

                IslandDisplayMode.COMPACT_SINGLE -> {
                    CompactSingleView(
                        payload = primaryPayload,
                        privacyState = privacyState,
                        onToggleTorch = onToggleTorch
                    )
                }

                IslandDisplayMode.EXPANDED_CARD -> {
                    ExpandedIslandCard(
                        payload = primaryPayload,
                        onCollapse = {
                            onRequestKeyboardFocus(false)
                            IslandStateEngine.collapseIsland()
                        },
                        onMinimizeToHole = {
                            onRequestKeyboardFocus(false)
                            IslandStateEngine.minimizeToIdle()
                        },
                        onOpenQuickTools = {
                            IslandStateEngine.openQuickTools()
                        },
                        onRequestKeyboardFocus = onRequestKeyboardFocus
                    )
                }

                IslandDisplayMode.ORIGIN_DRAG_WHEEL -> {
                    OriginDragAppsDock(
                        targetApps = dragTargetApps,
                        onAppClick = { app ->
                            context.startActivity(app.launchIntent)
                            IslandStateEngine.setDragWheelActive(false)
                        }
                    )
                }

                IslandDisplayMode.QUICK_TOOLS -> {
                    IdleQuickToolsDock(
                        quickTileStates = quickTileStates,
                        clipboardHistory = clipboardHistory,
                        stashItems = stashItems,
                        onToggleTorch = onToggleTorch,
                        onToggleBluetooth = onToggleBluetooth,
                        onToggleHotspot = onToggleHotspot,
                        onToggleAutoRotate = onToggleAutoRotate,
                        onToggleRingerMode = onToggleRingerMode,
                        onSetVolumeFraction = onSetVolumeFraction,
                        onSetBrightnessFraction = onSetBrightnessFraction,
                        onPasteText = { text ->
                            onPasteTextToFocusedApp(text)
                            IslandStateEngine.collapseIsland()
                        },
                        onClose = { IslandStateEngine.collapseIsland() }
                    )
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun IdleHollowCircumferenceRing(privacyState: PrivacyIndicatorState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        if (privacyState.isCameraActive || privacyState.isMicActive) {
            PrivacyDotsRow(privacyState)
        }
    }
}

@Composable
private fun SideCircleContent(secondaryPayload: IslandPayload?) {
    when (secondaryPayload) {
        is IslandPayload.SystemAlert -> {
            Icon(
                imageVector = if (secondaryPayload.iconType == AlertIconType.HOTSPOT) {
                    Icons.Default.WifiTethering
                } else {
                    Icons.Default.Notifications
                },
                contentDescription = null,
                tint = secondaryPayload.accentColor,
                modifier = Modifier.size(18.dp)
            )
        }
        is IslandPayload.LiveProgressActivity -> {
            if (secondaryPayload.appIcon != null) {
                Image(
                    bitmap = secondaryPayload.appIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        is IslandPayload.MediaActivity -> {
            if (secondaryPayload.albumArt != null) {
                Image(
                    bitmap = secondaryPayload.albumArt.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = secondaryPayload.accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        is IslandPayload.CommunicationActivity -> {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                tint = Color(0xFF22C55E),
                modifier = Modifier.size(18.dp)
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PrivacyDotsRow(privacyState: PrivacyIndicatorState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (privacyState.isCameraActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF22C55E))
            )
        }
        if (privacyState.isMicActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF97316))
            )
        }
    }
}

@Composable
private fun CompactSingleView(
    payload: IslandPayload,
    privacyState: PrivacyIndicatorState,
    onToggleTorch: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        when (payload) {
            is IslandPayload.SystemAlert -> {
                Icon(
                    imageVector = when (payload.iconType) {
                        AlertIconType.CHARGING -> Icons.Default.BatteryChargingFull
                        AlertIconType.LOW_BATTERY -> Icons.Default.BatteryAlert
                        AlertIconType.RINGER_SILENT -> Icons.AutoMirrored.Filled.VolumeOff
                        AlertIconType.RINGER_VIBRATE -> Icons.Default.Vibration
                        AlertIconType.TORCH -> Icons.Default.FlashlightOn
                        AlertIconType.HOTSPOT -> Icons.Default.WifiTethering
                        else -> Icons.Default.Notifications
                    },
                    contentDescription = null,
                    tint = payload.accentColor,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(enabled = payload.iconType == AlertIconType.TORCH) {
                            onToggleTorch()
                        }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (payload.subtitle.isNotBlank()) "${payload.title} • ${payload.subtitle}" else payload.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            is IslandPayload.MediaActivity -> {
                if (payload.albumArt != null) {
                    Image(
                        bitmap = payload.albumArt.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = payload.accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = payload.trackTitle,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                ProceduralAudioWaveform(
                    isPlaying = payload.isPlaying,
                    tint = payload.accentColor
                )
            }

            is IslandPayload.LiveProgressActivity -> {
                if (payload.appIcon != null) {
                    Image(
                        bitmap = payload.appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = payload.accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = payload.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                )
                Text(
                    text = payload.etaOrTimerText,
                    color = payload.accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            is IslandPayload.CommunicationActivity -> {
                if (payload.avatar != null) {
                    Image(
                        bitmap = payload.avatar.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = if (payload.isOngoingCall) Icons.Default.Call else Icons.Default.ChatBubble,
                        contentDescription = null,
                        tint = Color(0xFF4ADE80),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "${payload.senderName} ${payload.messageOrStatus}".trim(),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                )
            }

            is IslandPayload.OriginAiSuggestion -> {
                val firstApp = payload.targetApps.firstOrNull()
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            payload.primaryIntent?.let { context.startActivity(it) }
                            IslandStateEngine.removePayload(payload.id)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (firstApp != null) {
                        Image(
                            bitmap = firstApp.icon.toComposeBitmap(64).asImageBitmap(),
                            contentDescription = firstApp.label,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = payload.actionTitle,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            IslandPayload.Idle -> {}
        }
        PrivacyDotsRow(privacyState)
    }
}

@Composable
private fun ExpandedIslandCard(
    payload: IslandPayload,
    onCollapse: () -> Unit,
    onMinimizeToHole: () -> Unit,
    onOpenQuickTools: () -> Unit,
    onRequestKeyboardFocus: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var replyText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        when (payload) {
            is IslandPayload.MediaActivity -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (payload.albumArt != null) {
                        Image(
                            bitmap = payload.albumArt.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    launchPayloadIntent(context, payload)
                                    onCollapse()
                                }
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = payload.trackTitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = payload.artist,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Open Spotify App Explicitly Button
                    IconButton(
                        onClick = {
                            launchPayloadIntent(context, payload)
                            onCollapse()
                        },
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = payload.accentColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Quick Tools Button
                    IconButton(
                        onClick = onOpenQuickTools,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Collapse to Pill Button
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Interactive Scrubbing / Seek Slider
                Slider(
                    value = payload.progress,
                    onValueChange = { newFraction ->
                        if (payload.durationMs > 0L) {
                            payload.onSeekTo?.invoke((newFraction * payload.durationMs).toLong())
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = payload.accentColor,
                        activeTrackColor = payload.accentColor,
                        inactiveTrackColor = Color(0xFF334155)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                )

                // Song Control Buttons + Minimize to Camera Ring Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { payload.onPrevious?.invoke() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { payload.onPlayPause?.invoke() }) {
                        Icon(
                            if (payload.isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    IconButton(onClick = { payload.onNext?.invoke() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onMinimizeToHole) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    }
                }
            }

            is IslandPayload.SystemAlert -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (payload.iconType == AlertIconType.HOTSPOT) {
                                Icons.Default.WifiTethering
                            } else {
                                Icons.Default.Notifications
                            },
                            contentDescription = null,
                            tint = payload.accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = payload.title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (payload.subtitle.isNotBlank()) {
                                Text(
                                    text = payload.subtitle,
                                    color = payload.accentColor,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White)
                    }
                }
                Button(
                    onClick = onOpenQuickTools,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Direct Quick Toggles", color = Color.White)
                }
            }

            is IslandPayload.CommunicationActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (payload.avatar != null) {
                        Image(
                            bitmap = payload.avatar.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = payload.senderName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                        Text(
                            text = payload.messageOrStatus,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            maxLines = 2
                        )
                    }
                    if (payload.isOngoingCall) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            payload.acceptIntent?.let { accept ->
                                IconButton(
                                    onClick = {
                                        try { accept.send() } catch (_: Exception) {}
                                        onCollapse()
                                    },
                                    modifier = Modifier.background(Color(0xFF22C55E), CircleShape)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                                }
                            }
                            payload.declineIntent?.let { decline ->
                                IconButton(
                                    onClick = {
                                        try { decline.send() } catch (_: Exception) {}
                                        onCollapse()
                                    },
                                    modifier = Modifier.background(Color(0xFFEF4444), CircleShape)
                                ) {
                                    Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }

                if (payload.remoteInput != null && payload.replyActionIntent != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = replyText,
                            onValueChange = {
                                onRequestKeyboardFocus(true)
                                replyText = it
                            },
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(Color(0xFF38BDF8)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onRequestKeyboardFocus(true) }
                        )
                        IconButton(
                            onClick = {
                                sendInlineReply(
                                    context = context,
                                    remoteInput = payload.remoteInput,
                                    actionIntent = payload.replyActionIntent,
                                    replyText = replyText
                                )
                                replyText = ""
                                onCollapse()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color(0xFF38BDF8))
                        }
                    }
                }
            }

            is IslandPayload.LiveProgressActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = payload.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White)
                    }
                }
                Text(
                    text = payload.etaOrTimerText,
                    color = payload.accentColor,
                    fontSize = 13.sp,
                    maxLines = 2
                )
                LinearProgressIndicator(
                    progress = { payload.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = payload.accentColor,
                    trackColor = Color.DarkGray
                )
            }

            is IslandPayload.OriginAiSuggestion -> {
                Text(
                    text = payload.extractedText,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                OriginDragAppsDock(
                    targetApps = payload.targetApps,
                    onAppClick = { app ->
                        context.startActivity(app.launchIntent)
                        onCollapse()
                    }
                )
            }

            else -> {}
        }
    }
}

@Composable
private fun OriginDragAppsDock(
    targetApps: List<TargetAppInfo>,
    onAppClick: (TargetAppInfo) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        targetApps.forEach { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onAppClick(app) }
                    .padding(4.dp)
            ) {
                Image(
                    bitmap = app.icon.toComposeBitmap(96).asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Feature 3: Direct One-Tap ON/OFF Hardware Toggles + Dual Volume & Brightness Sliders
 */
@Composable
private fun IdleQuickToolsDock(
    quickTileStates: QuickTileStates,
    clipboardHistory: List<String>,
    stashItems: List<StashItem>,
    onToggleTorch: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleHotspot: () -> Unit,
    onToggleAutoRotate: () -> Unit,
    onToggleRingerMode: () -> Unit,
    onSetVolumeFraction: (Float) -> Unit,
    onSetBrightnessFraction: (Float) -> Unit,
    onPasteText: (String) -> Unit,
    onClose: () -> Unit
) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Row 1: 5 Direct ON/OFF Hardware Toggles + Close Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingCircleTile(
                icon = if (quickTileStates.isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                label = if (quickTileStates.isTorchOn) "ON" else "OFF",
                isActive = quickTileStates.isTorchOn,
                activeColor = Color(0xFFFACC15),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToggleTorch()
                }
            )
            QuickSettingCircleTile(
                icon = if (quickTileStates.isBluetoothOn) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                label = if (quickTileStates.isBluetoothOn) "ON" else "OFF",
                isActive = quickTileStates.isBluetoothOn,
                activeColor = Color(0xFF38BDF8),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToggleBluetooth()
                }
            )
            QuickSettingCircleTile(
                icon = Icons.Default.WifiTethering,
                label = if (quickTileStates.isHotspotOn) "ON" else "OFF",
                isActive = quickTileStates.isHotspotOn,
                activeColor = Color(0xFF22C55E),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToggleHotspot()
                }
            )
            QuickSettingCircleTile(
                icon = if (quickTileStates.isAutoRotateOn) Icons.Default.ScreenRotation else Icons.Default.ScreenLockPortrait,
                label = if (quickTileStates.isAutoRotateOn) "ON" else "OFF",
                isActive = quickTileStates.isAutoRotateOn,
                activeColor = Color(0xFFA855F7),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToggleAutoRotate()
                }
            )
            QuickSettingCircleTile(
                icon = when (quickTileStates.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> Icons.AutoMirrored.Filled.VolumeOff
                    AudioManager.RINGER_MODE_VIBRATE -> Icons.Default.Vibration
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                label = when (quickTileStates.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "Mute"
                    AudioManager.RINGER_MODE_VIBRATE -> "Vib"
                    else -> "Ring"
                },
                isActive = quickTileStates.ringerMode != AudioManager.RINGER_MODE_SILENT,
                activeColor = Color(0xFFF97316),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToggleRingerMode()
                }
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }

        // Row 2: Direct In-Island Volume & Brightness Sliders
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Slider(
                    value = quickTileStates.volumeFraction,
                    onValueChange = onSetVolumeFraction,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF38BDF8),
                        inactiveTrackColor = Color(0xFF1E293B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Brightness6,
                    contentDescription = null,
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Slider(
                    value = quickTileStates.brightnessFraction,
                    onValueChange = onSetBrightnessFraction,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFACC15),
                        activeTrackColor = Color(0xFFFACC15),
                        inactiveTrackColor = Color(0xFF1E293B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                )
            }
        }

        // Row 3: Clipboard & Stash Quick Paste Chips
        if (clipboardHistory.isNotEmpty() || stashItems.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clipboardHistory) { clipText ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { onPasteText(clipText) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = clipText.take(28),
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
                items(stashItems) { stash ->
                    stash.text?.let { text ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF312E81))
                                .clickable { onPasteText(text) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = text.take(28),
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSettingCircleTile(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.24f) else Color(0xFF1E293B))
                .border(
                    width = 1.2.dp,
                    color = if (isActive) activeColor else Color(0xFF334155),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) activeColor else Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else Color.Gray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ProceduralAudioWaveform(isPlaying: Boolean, tint: Color) {
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(33L)
            phase += 0.35f
        }
    }

    Canvas(modifier = Modifier.size(width = 24.dp, height = 16.dp)) {
        val barCount = 4
        val barWidth = size.width / (barCount * 2f)
        for (i in 0 until barCount) {
            val normalized = if (isPlaying) {
                ((sin(phase + i * 1.1f) + 1f) / 2f).coerceIn(0.2f, 1f)
            } else {
                0.25f
            }
            val barH = size.height * normalized
            drawRoundRect(
                color = tint,
                topLeft = Offset(x = i * (barWidth * 2f), y = (size.height - barH) / 2f),
                size = Size(width = barWidth, height = barH),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}

private fun Drawable.toComposeBitmap(sizePx: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true)
    }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

private fun launchPayloadIntent(context: Context, payload: IslandPayload) {
    try {
        when (payload) {
            is IslandPayload.MediaActivity -> {
                if (payload.contentIntent != null) {
                    payload.contentIntent.send()
                } else if (payload.packageName.isNotEmpty()) {
                    context.packageManager.getLaunchIntentForPackage(payload.packageName)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(it)
                    }
                }
            }
            is IslandPayload.CommunicationActivity -> payload.contentIntent?.send()
            is IslandPayload.LiveProgressActivity -> payload.contentIntent?.send()
            is IslandPayload.OriginAiSuggestion -> payload.primaryIntent?.let { context.startActivity(it) }
            else -> {}
        }
    } catch (_: Exception) {
    }
}

private fun sendInlineReply(
    context: Context,
    remoteInput: RemoteInput,
    actionIntent: android.app.PendingIntent,
    replyText: String
) {
    if (replyText.isBlank()) return
    val intent = Intent()
    val bundle = Bundle().apply {
        putCharSequence(remoteInput.resultKey, replyText)
    }
    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
    try {
        actionIntent.send(context, 0, intent)
    } catch (_: Exception) {
    }
}