package com.kgc.island2.ui

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
    onToggleBluetooth: () -> Unit = {},
    onToggleHotspot: () -> Unit = {},
    onToggleAutoRotate: () -> Unit = {},
    onAdjustVolume: (Float) -> Unit,
    onAdjustBrightness: (Float) -> Unit = {},
    onRequestKeyboardFocus: (Boolean) -> Unit,
    onPasteTextToFocusedApp: (String) -> Unit
) {
    val context = LocalContext.current
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
                    (cutoutConfig.baseWidthDp + 18).dp
                } else {
                    cutoutConfig.baseWidthDp.dp
                }
            }
            IslandDisplayMode.COMPACT_SINGLE -> 210.dp
            IslandDisplayMode.COMPACT_SPLIT -> 262.dp
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
            IslandDisplayMode.IDLE -> cutoutConfig.baseHeightDp.dp
            IslandDisplayMode.COMPACT_SINGLE,
            IslandDisplayMode.COMPACT_SPLIT -> 36.dp
            IslandDisplayMode.EXPANDED_CARD,
            IslandDisplayMode.ORIGIN_DRAG_WHEEL,
            IslandDisplayMode.QUICK_TOOLS -> 204.dp
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

    var cumulativeDx by remember { mutableFloatStateOf(0f) }
    var cumulativeDy by remember { mutableFloatStateOf(0f) }

    val gestureModifier = Modifier
        .pointerInput(displayMode, primaryPayload) {
            detectTapGestures(
                onTap = {
                    when (displayMode) {
                        IslandDisplayMode.IDLE -> IslandStateEngine.onIdleCircleTapped()
                        IslandDisplayMode.COMPACT_SINGLE -> IslandStateEngine.expandIsland()
                        IslandDisplayMode.EXPANDED_CARD,
                        IslandDisplayMode.QUICK_TOOLS -> {
                            onRequestKeyboardFocus(false)
                            IslandStateEngine.collapseIsland()
                        }
                        else -> {}
                    }
                },
                onLongPress = {
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
                    val isBottomToUp = cumulativeDy < -32f
                    val isLeftToRightOrRightToLeft = abs(cumulativeDx) > 45f
                    val isDiagonalUp = abs(cumulativeDx) > 25f && cumulativeDy < -22f
                    val isTopToDown = cumulativeDy > 42f

                    when {
                        isBottomToUp || isLeftToRightOrRightToLeft || isDiagonalUp -> {
                            onRequestKeyboardFocus(false)
                            IslandStateEngine.handleDismissSwipe()
                        }
                        isTopToDown && displayMode != IslandDisplayMode.EXPANDED_CARD -> {
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
                    .border(0.8.dp, Color(0xFF1E293B), RoundedCornerShape(50.dp))
                    .clickable { IslandStateEngine.expandIsland() }
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
                .background(Color.Black)
                .border(
                    width = if (displayMode == IslandDisplayMode.IDLE) 1.5.dp else 0.8.dp,
                    color = if (displayMode == IslandDisplayMode.IDLE) Color(0xFF27272A) else Color(0xFF1E293B),
                    shape = RoundedCornerShape(cornerRadius)
                )
                .then(gestureModifier)
                .padding(
                    horizontal = if (displayMode == IslandDisplayMode.IDLE) 4.dp else 12.dp,
                    vertical = if (displayMode == IslandDisplayMode.IDLE) 4.dp else 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            when (displayMode) {
                IslandDisplayMode.IDLE -> {
                    IdlePunchHoleCircle(privacyState = privacyState)
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
private fun SideCircleContent(secondaryPayload: IslandPayload?) {
    when (secondaryPayload) {
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
private fun IdlePunchHoleCircle(privacyState: PrivacyIndicatorState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        PrivacyDotsRow(privacyState)
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
                        AlertIconType.RINGER_SILENT -> Icons.Default.VolumeOff
                        AlertIconType.RINGER_VIBRATE -> Icons.Default.Vibration
                        AlertIconType.TORCH -> Icons.Default.FlashlightOn
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
                Text(
                    text = if (payload.subtitle.isNotBlank()) "${payload.title} • ${payload.subtitle}" else payload.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                val itemContext = LocalContext.current
                val firstApp = payload.targetApps.firstOrNull()
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            payload.primaryIntent?.let { itemContext.startActivity(it) }
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
                        imageVector = Icons.Default.ArrowForwardIos,
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
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    launchPayloadIntent(context, payload)
                                    onCollapse()
                                }
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                launchPayloadIntent(context, payload)
                                onCollapse()
                            }
                    ) {
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
                        onClick = onOpenQuickTools,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .size(32.dp)
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

                LinearProgressIndicator(
                    progress = { payload.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = payload.accentColor,
                    trackColor = Color.DarkGray
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { payload.onPrevious?.invoke() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { payload.onPlayPause?.invoke() }) {
                        Icon(
                            if (payload.isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    IconButton(onClick = { payload.onNext?.invoke() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
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
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color(0xFF38BDF8))
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
                val suggestionContext = LocalContext.current
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
                        suggestionContext.startActivity(app.launchIntent)
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
        targetApps.forEach { app: TargetAppInfo ->
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

@Composable
private fun IdleQuickToolsDock(
    quickTileStates: QuickTileStates,
    clipboardHistory: List<String>,
    stashItems: List<StashItem>,
    onToggleTorch: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleHotspot: () -> Unit,
    onToggleAutoRotate: () -> Unit,
    onPasteText: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingCircleTile(
                icon = if (quickTileStates.isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                isActive = quickTileStates.isTorchOn,
                activeColor = Color(0xFFFACC15),
                onClick = onToggleTorch
            )
            QuickSettingCircleTile(
                icon = if (quickTileStates.isBluetoothOn) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                isActive = quickTileStates.isBluetoothOn,
                activeColor = Color(0xFF38BDF8),
                onClick = onToggleBluetooth
            )
            QuickSettingCircleTile(
                icon = Icons.Default.WifiTethering,
                isActive = false,
                activeColor = Color(0xFF22C55E),
                onClick = onToggleHotspot
            )
            QuickSettingCircleTile(
                icon = if (quickTileStates.isAutoRotateOn) Icons.Default.ScreenRotation else Icons.Default.ScreenLockPortrait,
                isActive = quickTileStates.isAutoRotateOn,
                activeColor = Color(0xFFA855F7),
                onClick = onToggleAutoRotate
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }

        if (clipboardHistory.isNotEmpty() || stashItems.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clipboardHistory) { clipText ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { onPasteText(clipText) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = clipText.take(32),
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
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = text.take(32),
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
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (isActive) activeColor.copy(alpha = 0.22f) else Color(0xFF1E293B))
            .border(
                width = 1.dp,
                color = if (isActive) activeColor else Color(0xFF334155),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) activeColor else Color.White,
            modifier = Modifier.size(24.dp)
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
            is IslandPayload.CommunicationActivity -> {
                payload.acceptIntent?.send()
            }
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