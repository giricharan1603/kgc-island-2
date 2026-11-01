package com.kgc.island2.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.Color
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.IslandPayload

class IslandNotificationService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private val activeCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindMediaControllers(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = ComponentName(this, IslandNotificationService::class.java)
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            bindMediaControllers(mediaSessionManager?.getActiveSessions(component).orEmpty())
        } catch (_: SecurityException) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun bindMediaControllers(controllers: List<MediaController>) {
        activeCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        activeCallbacks.clear()

        if (controllers.isEmpty()) {
            IslandStateEngine.removePayload("media_session")
            return
        }

        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    publishMediaState(controller)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishMediaState(controller)
                }

                override fun onSessionDestroyed() {
                    IslandStateEngine.removePayload("media_session")
                }
            }
            controller.registerCallback(callback)
            activeCallbacks[controller] = callback
            publishMediaState(controller)
        }
    }

    private fun publishMediaState(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val state = controller.playbackState
        val pbState = state?.state ?: PlaybackState.STATE_NONE

        if (pbState == PlaybackState.STATE_STOPPED || pbState == PlaybackState.STATE_NONE) {
            IslandStateEngine.removePayload("media_session")
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(1L)
        val position = (state?.position ?: 0L).coerceIn(0L, duration)
        val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val isPlaying = pbState == PlaybackState.STATE_PLAYING || pbState == PlaybackState.STATE_BUFFERING

        IslandStateEngine.submitPayload(
            IslandPayload.MediaActivity(
                id = "media_session",
                trackTitle = title,
                artist = artist,
                albumArt = art,
                accentColor = extractDominantColor(art),
                isPlaying = isPlaying,
                progress = progress,
                packageName = controller.packageName,
                contentIntent = controller.sessionActivity,
                onPlayPause = {
                    val currentPb = controller.playbackState?.state
                    if (currentPb == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                },
                onNext = { controller.transportControls.skipToNext() },
                onPrevious = { controller.transportControls.skipToPrevious() }
            )
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val category = notification.category

        if (category == Notification.CATEGORY_CALL) {
            val actions = notification.actions.orEmpty()
            val acceptIntent = actions.firstOrNull {
                it.title.toString().contains("Answer", true) ||
                        it.title.toString().contains("Accept", true)
            }?.actionIntent
            val declineIntent = actions.firstOrNull {
                it.title.toString().contains("Decline", true) ||
                        it.title.toString().contains("Hang", true) ||
                        it.title.toString().contains("Reject", true)
            }?.actionIntent

            IslandStateEngine.submitPayload(
                IslandPayload.CommunicationActivity(
                    id = sbn.key,
                    senderName = title,
                    messageOrStatus = text,
                    isOngoingCall = sbn.isOngoing,
                    contentIntent = notification.contentIntent,
                    acceptIntent = acceptIntent,
                    declineIntent = declineIntent
                )
            )
            return
        }

        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progressCurr = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        if (sbn.isOngoing && (progressMax > 0 || category == Notification.CATEGORY_NAVIGATION || category == Notification.CATEGORY_STOPWATCH)) {
            val fraction = if (progressMax > 0) (progressCurr.toFloat() / progressMax).coerceIn(0f, 1f) else 0.5f
            IslandStateEngine.submitPayload(
                IslandPayload.LiveProgressActivity(
                    id = sbn.key,
                    title = title,
                    etaOrTimerText = text,
                    progressFraction = fraction,
                    appIcon = getSmallAppIconBitmap(sbn.packageName),
                    contentIntent = notification.contentIntent
                )
            )
            return
        }

        var foundRemoteInput: RemoteInput? = null
        var replyPendingIntent = notification.contentIntent
        notification.actions?.forEach { action ->
            action.remoteInputs?.firstOrNull()?.let { remoteInput ->
                foundRemoteInput = remoteInput
                replyPendingIntent = action.actionIntent
            }
        }

        if (foundRemoteInput != null || category == Notification.CATEGORY_MESSAGE) {
            IslandStateEngine.submitPayload(
                IslandPayload.CommunicationActivity(
                    id = sbn.key,
                    senderName = title,
                    messageOrStatus = text,
                    isOngoingCall = false,
                    contentIntent = notification.contentIntent,
                    remoteInput = foundRemoteInput,
                    replyActionIntent = replyPendingIntent
                )
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        IslandStateEngine.removePayload(key)
    }

    private fun getSmallAppIconBitmap(pkg: String): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            (drawable as? BitmapDrawable)?.bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDominantColor(bitmap: Bitmap?): Color {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return Color(0xFF22C55E)
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val r = ((pixel shr 16) and 0xFF).coerceAtLeast(80)
        val g = ((pixel shr 8) and 0xFF).coerceAtLeast(80)
        val b = (pixel and 0xFF).coerceAtLeast(80)
        return Color(r, g, b)
    }
}