package com.kgc.island2.model

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class IslandDisplayMode {
    IDLE,               // Circular ring around the camera punch-hole when not in use
    COMPACT_SINGLE,     // Brief system alerts
    COMPACT_SPLIT,      // Primary Pill + Detached Side Circle (Spotify + Side Options/2nd Task)
    EXPANDED_CARD,      // Interactive expanded widget
    ORIGIN_DRAG_WHEEL,  // Real Drag & Go installed apps dock
    QUICK_TOOLS         // Notification-bar quick toggles (Torch, BT, Hotspot, Rotate) + Clipboard
}

enum class TaskPriority(val level: Int) {
    CRITICAL(1),
    SYSTEM_ALERT(2),
    ORIGIN_AI(2),
    LIVE_ACTIVITY(3),
    IDLE(4)
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
    val isAutoRotateOn: Boolean = false
)

@Immutable
data class CutoutConfig(
    val offsetX: Int = 0,
    val offsetY: Int = 10,
    val baseWidthDp: Int = 28,
    val baseHeightDp: Int = 28,
    val idleCircleSizeDp: Int = 28
)

@Immutable
data class TargetAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
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
    val timestamp: Long

    data object Idle : IslandPayload {
        override val id = "idle"
        override val priority = TaskPriority.IDLE
        override val timestamp = 0L
    }

    data class SystemAlert(
        override val id: String,
        val title: String,
        val subtitle: String,
        val accentColor: Color,
        val iconType: AlertIconType,
        val autoDismissMs: Long = 2400L,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = TaskPriority.SYSTEM_ALERT
    }

    data class MediaActivity(
        override val id: String = "media_session",
        val trackTitle: String,
        val artist: String,
        val albumArt: Bitmap? = null,
        val accentColor: Color = Color(0xFF1DB954),
        val isPlaying: Boolean = true,
        val progress: Float = 0f,
        val packageName: String = "",
        val contentIntent: PendingIntent? = null,
        val onPlayPause: (() -> Unit)? = null,
        val onNext: (() -> Unit)? = null,
        val onPrevious: (() -> Unit)? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = TaskPriority.LIVE_ACTIVITY
    }

    data class CommunicationActivity(
        override val id: String,
        val senderName: String,
        val messageOrStatus: String,
        val isOngoingCall: Boolean,
        val avatar: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        val acceptIntent: PendingIntent? = null,
        val declineIntent: PendingIntent? = null,
        val replyActionIntent: PendingIntent? = null,
        val remoteInput: RemoteInput? = null,
        val burstCount: Int = 1,
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = if (isOngoingCall) TaskPriority.CRITICAL else TaskPriority.SYSTEM_ALERT
    }

    data class LiveProgressActivity(
        override val id: String,
        val title: String,
        val etaOrTimerText: String,
        val progressFraction: Float,
        val isIndeterminate: Boolean = false,
        val accentColor: Color = Color(0xFF38BDF8),
        val appIcon: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
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
        override val timestamp: Long = System.currentTimeMillis()
    ) : IslandPayload {
        override val priority = TaskPriority.ORIGIN_AI
    }
}

enum class AlertIconType {
    CHARGING, LOW_BATTERY, RINGER_SILENT, RINGER_VIBRATE, RINGER_NORMAL,
    BLUETOOTH, TORCH, BIOMETRIC
}