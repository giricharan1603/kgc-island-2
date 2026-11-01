package com.kgc.island2.model

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class IslandDisplayMode {
    IDLE,
    COMPACT_SINGLE,
    COMPACT_SPLIT,
    EXPANDED_CARD,
    ORIGIN_DRAG_WHEEL,
    QUICK_TOOLS
}

enum class TaskPriority(val level: Int, val weight: Int) {
    CRITICAL(1, 100),
    SYSTEM_ALERT(2, 85),
    ORIGIN_AI(2, 75),
    LIVE_ACTIVITY(3, 60),
    IDLE(4, 0)
}

enum class AlertIconType {
    CHARGING,
    LOW_BATTERY,
    RINGER_SILENT,
    RINGER_VIBRATE,
    RINGER_NORMAL,
    BLUETOOTH,
    TORCH,
    HOTSPOT,
    BIOMETRIC,
    NOTIFICATION
}

@Immutable
data class PrivacyIndicatorState(
    val isCameraActive: Boolean = false,
    val isMicActive: Boolean = false
)

@Immutable
data class QuickTileStates(
    val isTorchOn: Boolean = false,
    val isBluetoothOn: Boolean = false,
    val isHotspotOn: Boolean = false,
    val isAutoRotateOn: Boolean = false,
    val ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
    val volumeFraction: Float = 0.5f
)

@Immutable
data class CutoutConfig(
    val offsetX: Int = 0,
    val offsetY: Int = 10,
    val baseWidthDp: Int = 30,
    val baseHeightDp: Int = 30,
    val idleCircleSizeDp: Int = 30,
    val isLandscape: Boolean = false,
    val landscapeCutoutX: Int = 0,
    val landscapeCutoutY: Int = 0
)

@Immutable
data class TargetAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String = "",
    val icon: Drawable,
    val launchIntent: Intent
)

@Immutable
data class StashItem(
    val id: String,
    val text: String? = null,
    val uri: Uri? = null,
    val mimeType: String = "text/plain",
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
sealed interface IslandPayload {
    val id: String
    val priority: TaskPriority
    val isOngoing: Boolean
    val autoDismissMs: Long?
    val timestamp: Long

    data object Idle : IslandPayload {
        override val id = "idle"
        override val priority = TaskPriority.IDLE
        override val isOngoing = false
        override val autoDismissMs: Long? = null
        override val timestamp = 0L
    }

    data class SystemAlert(
        override val id: String,
        val title: String,
        val subtitle: String = "",
        val accentColor: Color = Color(0xFF22C55E),
        val iconType: AlertIconType = AlertIconType.NOTIFICATION,
        override val isOngoing: Boolean = false,
        override val autoDismissMs: Long? = 2600L,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = if (isOngoing) TaskPriority.LIVE_ACTIVITY else TaskPriority.SYSTEM_ALERT
    }

    data class MediaActivity(
        override val id: String = "media_session",
        val trackTitle: String,
        val artist: String,
        val albumArt: Bitmap? = null,
        val accentColor: Color = Color(0xFF1DB954),
        val isPlaying: Boolean = true,
        val progress: Float = 0f,
        val durationMs: Long = 0L,
        val packageName: String = "",
        val contentIntent: PendingIntent? = null,
        val onPlayPause: (() -> Unit)? = null,
        val onNext: (() -> Unit)? = null,
        val onPrevious: (() -> Unit)? = null,
        val onSeekTo: ((Long) -> Unit)? = null,
        override val isOngoing: Boolean = true,
        override val autoDismissMs: Long? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = TaskPriority.LIVE_ACTIVITY
    }

    data class CommunicationActivity(
        override val id: String,
        val senderName: String,
        val messageOrStatus: String,
        val isOngoingCall: Boolean = false,
        val avatar: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        val acceptIntent: PendingIntent? = null,
        val declineIntent: PendingIntent? = null,
        val replyActionIntent: PendingIntent? = null,
        val remoteInput: RemoteInput? = null,
        val burstCount: Int = 1,
        override val isOngoing: Boolean = isOngoingCall,
        override val autoDismissMs: Long? = if (isOngoingCall) null else 4500L,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = if (isOngoingCall) TaskPriority.CRITICAL else TaskPriority.SYSTEM_ALERT
    }

    data class LiveProgressActivity(
        override val id: String,
        val title: String,
        val etaOrTimerText: String,
        val progressFraction: Float = 0f,
        val isIndeterminate: Boolean = false,
        val accentColor: Color = Color(0xFF38BDF8),
        val appIcon: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        override val isOngoing: Boolean = true,
        override val autoDismissMs: Long? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = TaskPriority.LIVE_ACTIVITY
    }

    data class OriginAiSuggestion(
        override val id: String = "origin_ai",
        val actionTitle: String,
        val extractedText: String,
        val targetApps: List<TargetAppInfo> = emptyList(),
        val primaryIntent: Intent? = null,
        override val isOngoing: Boolean = false,
        override val autoDismissMs: Long? = 5500L,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = TaskPriority.ORIGIN_AI
    }
}