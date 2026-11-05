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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun IslandOverlayScreen(
    onStatusBarSwipeDown: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleBluetooth: () -> Unit = {},
    onToggleHotspot: () -> Unit = {},
    onToggleAutoRotate: () -> Unit = {},
    onToggleRingerMode: () -> Unit = {},
    onTakeScreenshot: () -> Unit = {},
    onLockScreen: () -> Unit = {},
    onSetVolumeFraction: (Float) -> Unit = {},
    onSetBrightnessFraction: (Float) -> Unit = {},
    onAdjustVolume: (Float) -> Unit,
    onAdjustBrightness: (Float) -> Unit = {},
    onDragReposition: (Float, Float) -> Unit = { _, _ -> },
    onDragRepositionEnd: () -> Unit = {},
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
    val pinnedShortcuts by IslandStateEngine.pinnedShortcuts.collectAsState()

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
            IslandDisplayMode.QUICK_TOOLS -> 220.dp
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

    val containerBgColor by transition.animateColor(
        transitionSpec = { tween(220) },
        label = "bg_color"
    ) { mode ->
        if (mode == IslandDisplayMode.IDLE) Color.Transparent else Color.Black
    }

    var cumulativeDx by remember { mutableFloatStateOf(0f) }
    var cumulativeDy by remember { mutableFloatStateOf(0f) }

    val gestureModifier = Modifier
        .pointerInput(displayMode, primaryPayload, cutoutConfig.isHoldAndDragEnabled) {
            detectTapGestures(
                onTap = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    when (displayMode) {
                        IslandDisplayMode.IDLE -> IslandStateEngine.onIdleCircleTapped()
                        IslandDisplayMode.COMPACT_SINGLE,
                        IslandDisplayMode.COMPACT_SPLIT -> IslandStateEngine.expandIsland()
                        else -> {}
                    }
                },
                onDoubleTap = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    IslandStateEngine.openQuickTools()
                }
            )
        }
        .pointerInput(cutoutConfig.isHoldAndDragEnabled, cutoutConfig.isLandscape) {
            if (cutoutConfig.isHoldAndDragEnabled) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                    onDragEnd = {
                        onDragRepositionEnd()
                    },
                    onDragCancel = {
                        onDragRepositionEnd()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragReposition(dragAmount.x, dragAmount.y)
                    }
                )
            }
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
                .then(
                    if (displayMode != IslandDisplayMode.IDLE) {
                        Modifier.border(1.dp, Color(0xFF1E293B), RoundedCornerShape(cornerRadius))
                    } else {
                        Modifier
                    }
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
                    IdleAnimatedCircumferenceRing(
                        style = cutoutConfig.circleAnimationStyle,
                        privacyState = privacyState,
                        isHotspotOn = quickTileStates.isHotspotOn,
                        batteryPct = quickTileStates.batteryPct,
                        primaryPayload = primaryPayload
                    )
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
                            try {
                                context.startActivity(app.launchIntent)
                            } catch (e: Exception) {}
                            IslandStateEngine.setDragWheelActive(false)
                        }
                    )
                }

                IslandDisplayMode.QUICK_TOOLS -> {
                    IdleQuickToolsDock(
                        quickTileStates = quickTileStates,
                        pinnedShortcuts = pinnedShortcuts,
                        clipboardHistory = clipboardHistory,
                        stashItems = stashItems,
                        onToggleTorch = onToggleTorch,
                        onToggleBluetooth = onToggleBluetooth,
                        onToggleHotspot = onToggleHotspot,
                        onToggleAutoRotate = onToggleAutoRotate,
                        onToggleRingerMode = onToggleRingerMode,
                        onTakeScreenshot = onTakeScreenshot,
                        onLockScreen = onLockScreen,
                        onLaunchShortcutApp = { app ->
                            try {
                                context.startActivity(app.launchIntent)
                                IslandStateEngine.collapseIsland()
                            } catch (e: Exception) {}
                        },
                        onSetVolumeFraction = onSetVolumeFraction,
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
private fun IdleAnimatedCircumferenceRing(
    style: CircleAnimationStyle,
    privacyState: PrivacyIndicatorState,
    isHotspotOn: Boolean,
    batteryPct: Int,
    primaryPayload: IslandPayload
) {
    val accentColor = when {
        privacyState.isCameraActive || privacyState.isMicActive -> Color(0xFF22C55E)
        isHotspotOn -> Color(0xFF22C55E)
        primaryPayload is IslandPayload.MediaActivity -> primaryPayload.accentColor
        else -> Color(0xFF38BDF8)
    }
    val secondaryColor = if (isHotspotOn) Color(0xFF4ADE80) else Color(0xFFA855F7)

    val sweepBrush = remember(accentColor, secondaryColor) {
        Brush.sweepGradient(listOf(accentColor, secondaryColor, Color(0xFF0EA5E9), accentColor))
    }
    val linearBrush = remember(accentColor, secondaryColor) {
        Brush.linearGradient(listOf(accentColor, secondaryColor))
    }

    var angle by remember { mutableFloatStateOf(0f) }
    var pulseScale by remember { mutableFloatStateOf(2.2f) }

    LaunchedEffect(style) {
        when (style) {
            CircleAnimationStyle.CYBER_SWEEP,
            CircleAnimationStyle.ORBIT_DOTS -> {
                while (true) {
                    delay(45L)
                    angle = (angle + 6f) % 360f
                }
            }
            CircleAnimationStyle.PULSE_GLOW -> {
                var t = 0f
                while (true) {
                    delay(50L)
                    t += 0.18f
                    pulseScale = 1.8f + ((sin(t) + 1f) * 0.8f)
                }
            }
            CircleAnimationStyle.BATTERY_RING,
            CircleAnimationStyle.STEALTH_STATIC -> {
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(1.5.dp)) {
            val strokePx = pulseScale.dp.toPx()
            val radius = (size.minDimension / 2f) - (strokePx / 2f)

            when (style) {
                CircleAnimationStyle.CYBER_SWEEP -> {
                    rotate(angle) {
                        drawCircle(
                            brush = sweepBrush,
                            radius = radius,
                            style = Stroke(width = 2.3.dp.toPx())
                        )
                    }
                }

                CircleAnimationStyle.PULSE_GLOW -> {
                    drawCircle(
                        brush = linearBrush,
                        radius = radius,
                        style = Stroke(width = strokePx)
                    )
                }

                CircleAnimationStyle.ORBIT_DOTS -> {
                    drawCircle(
                        color = Color(0xFF1E293B),
                        radius = radius,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    val rad1 = (angle * PI / 180.0).toFloat()
                    val rad2 = ((angle + 180f) * PI / 180.0).toFloat()
                    val cx = center.x
                    val cy = center.y
                    drawCircle(
                        color = accentColor,
                        radius = 3.dp.toPx(),
                        center = Offset(
                            cx + (radius * cos(rad1)),
                            cy + (radius * sin(rad1))
                        )
                    )
                    drawCircle(
                        color = secondaryColor,
                        radius = 2.6.dp.toPx(),
                        center = Offset(
                            cx + (radius * cos(rad2)),
                            cy + (radius * sin(rad2))
                        )
                    )
                }

                CircleAnimationStyle.BATTERY_RING -> {
                    drawCircle(
                        color = Color(0xFF1E293B),
                        radius = radius,
                        style = Stroke(width = 2.2.dp.toPx())
                    )
                    val sweep = (batteryPct.coerceIn(1, 100) / 100f) * 360f
                    val batColor = when {
                        batteryPct <= 20 -> Color(0xFFEF4444)
                        isHotspotOn -> Color(0xFF22C55E)
                        else -> Color(0xFF4ADE80)
                    }
                    drawArc(
                        color = batColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                CircleAnimationStyle.STEALTH_STATIC -> {
                    drawCircle(
                        brush = linearBrush,
                        radius = radius,
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                }
            }
        }

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
                val img = remember(secondaryPayload.id) { secondaryPayload.appIcon.asImageBitmap() }
                Image(
                    bitmap = img,
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
                val img = remember(secondaryPayload.trackTitle) { secondaryPayload.albumArt.asImageBitmap() }
                Image(
                    bitmap = img,
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
                if (payload.iconType == AlertIconType.CHARGING) {
                    ProceduralAudioWaveform(isPlaying = true, tint = payload.accentColor)
                }
            }

            is IslandPayload.MediaActivity -> {
                if (payload.albumArt != null) {
                    val artImg = remember(payload.trackTitle, payload.albumArt) { payload.albumArt.asImageBitmap() }
                    Image(
                        bitmap = artImg,
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
                    val iconImg = remember(payload.id) { payload.appIcon.asImageBitmap() }
                    Image(
                        bitmap = iconImg,
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
                    val avatarImg = remember(payload.id) { payload.avatar.asImageBitmap() }
                    Image(
                        bitmap = avatarImg,
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
                            try {
                                payload.primaryIntent?.let { context.startActivity(it) }
                            } catch (e: Exception) {}
                            IslandStateEngine.removePayload(payload.id)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (firstApp != null) {
                        val appIconBmp = remember(firstApp.packageName) {
                            firstApp.icon.toComposeBitmap(64).asImageBitmap()
                        }
                        Image(
                            bitmap = appIconBmp,
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
                        val artImg = remember(payload.trackTitle, payload.albumArt) { payload.albumArt.asImageBitmap() }
                        Image(
                            bitmap = artImg,
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
                    Text("Open Quick Tools & App Dock", color = Color.White)
                }
            }

            is IslandPayload.CommunicationActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (payload.avatar != null) {
                        val avatarImg = remember(payload.id) { payload.avatar.asImageBitmap() }
                        Image(
                            bitmap = avatarImg,
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
                                        try { accept.send() } catch (e: Exception) {}
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
                                        try { decline.send() } catch (e: Exception) {}
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
                        try {
                            context.startActivity(app.launchIntent)
                        } catch (e: Exception) {}
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
            val cachedIcon = remember(app.packageName) {
                app.icon.toComposeBitmap(96).asImageBitmap()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onAppClick(app) }
                    .padding(4.dp)
            ) {
                Image(
                    bitmap = cachedIcon,
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

@Composable
private fun IdleQuickToolsDock(
    quickTileStates: QuickTileStates,
    pinnedShortcuts: List<TargetAppInfo>,
    clipboardHistory: List<String>,
    stashItems: List<StashItem>,
    onToggleTorch: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleHotspot: () -> Unit,
    onToggleAutoRotate: () -> Unit,
    onToggleRingerMode: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onLockScreen: () -> Unit,
    onLaunchShortcutApp: (TargetAppInfo) -> Unit,
    onSetVolumeFraction: (Float) -> Unit,
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
            Spacer(modifier = Modifier.width(8.dp))
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
                    .height(20.dp)
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                QuickSettingCircleTile(
                    icon = Icons.Default.Screenshot,
                    label = "Shot",
                    isActive = false,
                    activeColor = Color(0xFF38BDF8),
                    onClick = onTakeScreenshot
                )
            }
            item {
                QuickSettingCircleTile(
                    icon = Icons.Default.Lock,
                    label = "Lock",
                    isActive = false,
                    activeColor = Color(0xFFEF4444),
                    onClick = onLockScreen
                )
            }
            items(pinnedShortcuts, key = { it.packageName }) { app ->
                val cachedAppBitmap = remember(app.packageName) {
                    app.icon.toComposeBitmap(84).asImageBitmap()
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onLaunchShortcutApp(app)
                    }
                ) {
                    Image(
                        bitmap = cachedAppBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color(0xFF334155), CircleShape)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app.label.take(7),
                        color = Color.LightGray,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            items(clipboardHistory) { clipText ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { onPasteText(clipText) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "📋 ${clipText.take(18)}",
                        color = Color.White,
                        fontSize = 10.sp,
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
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = text.take(18),
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
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
                .size(42.dp)
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
                modifier = Modifier.size(20.dp)
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
            delay(40L)
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
    } catch (e: Exception) {
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
    } catch (e: Exception) {
    }
}