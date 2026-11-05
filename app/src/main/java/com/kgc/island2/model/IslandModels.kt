package com.kgc.island2.model

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import androidx.compose.ui.graphics.Color

enum class IslandDisplayMode {
    IDLE,
    COMPACT_SINGLE,
    COMPACT_SPLIT,
    EXPANDED_CARD,
    ORIGIN_DRAG_WHEEL,
    QUICK_TOOLS
}

enum class AlertIconType {
    CHARGING,
    LOW_BATTERY,
    RINGER_SILENT,
    RINGER_VIBRATE,
    RINGER_NORMAL,
    TORCH,
    HOTSPOT,
    BLUETOOTH,
    NOTIFICATION
}

enum class CircleAnimationStyle(val label: String) {
    CYBER_SWEEP("Cyber Sweep (Neon Gradient)"),
    PULSE_GLOW("Pulse Breathing Glow"),
    ORBIT_DOTS("Dual Orbiting Satellites"),
    BATTERY_RING("Live Battery Level Arc"),
    STEALTH_STATIC("Stealth Minimal Ring")
}

data class CutoutConfig(
    val baseWidthDp: Int = 34,
    val baseHeightDp: Int = 34,
    val idleCircleSizeDp: Int = 34,
    val offsetX: Int = 0,
    val offsetY: Int = 10,
    val isLandscape: Boolean = false,
    val landscapeRotation: Int = 0,
    val landscapeCutoutX: Int = 14,
    val landscapeCutoutY: Int = 0,
    val hasCustomPortraitPos: Boolean = false,
    val hasCustomLandscapePos: Boolean = false,
    val isHoldAndDragEnabled: Boolean = true,
    val chargingDurationMs: Long = 6500L,
    val circleAnimationStyle: CircleAnimationStyle = CircleAnimationStyle.CYBER_SWEEP
)

data class PrivacyIndicatorState(
    val isCameraActive: Boolean = false,
    val isMicActive: Boolean = false
)

data class QuickTileStates(
    val isTorchOn: Boolean = false,
    val isBluetoothOn: Boolean = false,
    val isHotspotOn: Boolean = false,
    val isAutoRotateOn: Boolean = false,
    val ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
    val volumeFraction: Float = 0.5f,
    val brightnessFraction: Float = 0.5f,
    val batteryPct: Int = 100
)

data class TargetAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String = "",
    val icon: Drawable,
    val launchIntent: Intent
)

data class StashItem(
    val id: String,
    val text: String? = null,
    val imageUri: Uri? = null,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class IslandPayload(open val id: String, open val priority: Int) {

    data object Idle : IslandPayload("idle", 0)

    data class SystemAlert(
        override val id: String,
        val title: String,
        val subtitle: String = "",
        val accentColor: Color = Color(0xFF38BDF8),
        val iconType: AlertIconType = AlertIconType.NOTIFICATION,
        val autoDismissMs: Long = 3000L,
        val isOngoing: Boolean = false
    ) : IslandPayload(id, if (isOngoing) 75 else 95)

    data class MediaActivity(
        override val id: String = "media_session",
        val packageName: String = "",
        val trackTitle: String,
        val artist: String,
        val albumArt: Bitmap? = null,
        val isPlaying: Boolean = true,
        val progress: Float = 0f,
        val durationMs: Long = 0L,
        val accentColor: Color = Color(0xFF38BDF8),
        val contentIntent: PendingIntent? = null,
        val onPlayPause: (() -> Unit)? = null,
        val onNext: (() -> Unit)? = null,
        val onPrevious: (() -> Unit)? = null,
        val onSeekTo: ((Long) -> Unit)? = null
    ) : IslandPayload(id, 65)

    data class LiveProgressActivity(
        override val id: String,
        val title: String,
        val etaOrTimerText: String,
        val progressFraction: Float = 0.5f,
        val appIcon: Bitmap? = null,
        val accentColor: Color = Color(0xFF38BDF8),
        val contentIntent: PendingIntent? = null
    ) : IslandPayload(id, 80)

    data class CommunicationActivity(
        override val id: String,
        val senderName: String,
        val messageOrStatus: String,
        val avatar: Bitmap? = null,
        val isOngoingCall: Boolean = false,
        val acceptIntent: PendingIntent? = null,
        val declineIntent: PendingIntent? = null,
        val replyActionIntent: PendingIntent? = null,
        val remoteInput: RemoteInput? = null,
        val contentIntent: PendingIntent? = null
    ) : IslandPayload(id, 100)

    data class OriginAiSuggestion(
        override val id: String = "origin_ai",
        val extractedText: String,
        val actionTitle: String,
        val targetApps: List<TargetAppInfo> = emptyList(),
        val primaryIntent: Intent? = null
    ) : IslandPayload(id, 90)
}