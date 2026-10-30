package com.kgc.island2.service

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.IslandPayload
import kotlinx.coroutines.*

class IslandNotificationService : NotificationListenerService() {

    private val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            activeMediaController?.let { publishMediaControllerState(it) }
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            activeMediaController?.let { publishMediaControllerState(it) }
        }
        override fun onSessionDestroyed() {
            IslandStateEngine.removePayload("media_session")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        setupMediaSessionTracking()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, IslandNotificationService::class.java))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep listener bound when user swipes away apps from Recents
        requestRebind(ComponentName(this, IslandNotificationService::class.java))
    }

    private fun setupMediaSessionTracking() {
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, IslandNotificationService::class.java)
        try {
            val controllers = mediaSessionManager?.getActiveSessions(component).orEmpty()
            attachMediaControllers(controllers)
            mediaSessionManager?.addOnActiveSessionsChangedListener({ list ->
                attachMediaControllers(list.orEmpty())
            }, component)
        } catch (_: SecurityException) {
        }
    }

    private fun attachMediaControllers(controllers: List<MediaController>) {
        activeMediaController?.unregisterCallback(mediaCallback)
        // Prefer a controller that is actively playing, or fall back to the first active session
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        activeMediaController = active
        if (active == null) {
            IslandStateEngine.removePayload("media_session")
            return
        }
        active.registerCallback(mediaCallback)
        publishMediaControllerState(active)
    }

    private fun publishMediaControllerState(controller: MediaController) {
        parserScope.launch {
            val meta = controller.metadata ?: return@launch
            val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE

            // Keep Spotify/music alive during track skips, buffering, and pause
            val isActiveState = state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_PAUSED ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                    state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
                    state == PlaybackState.STATE_CONNECTING

            if (!isActiveState) {
                IslandStateEngine.removePayload("media_session")
                return@launch
            }

            val isPlaying = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            if (title.isBlank() && artist.isBlank()) return@launch

            val rawArt = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)

            val scaledArt = rawArt?.let { Bitmap.createScaledBitmap(it, 128, 128, true) }
            val dominantColor = scaledArt?.let {
                val palette = Palette.from(it).generate()
                Color(palette.getVibrantColor(0xFF1DB954.toInt()))
            } ?: Color(0xFF1DB954)

            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(1L)
            val pos = controller.playbackState?.position ?: 0L
            val progress = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

            val launchIntent = controller.sessionActivity
            val pkg = controller.packageName

            IslandStateEngine.submitPayload(
                IslandPayload.MediaActivity(
                    trackTitle = title,
                    artist = artist,
                    albumArt = scaledArt,
                    accentColor = dominantColor,
                    isPlaying = isPlaying,
                    progress = progress,
                    packageName = pkg,
                    contentIntent = launchIntent,
                    onPlayPause = {
                        val currentState = controller.playbackState?.state
                        if (currentState == PlaybackState.STATE_PLAYING) {
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        // Ignore media-style notifications here since MediaController handles them with full transport controls
        if (sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            setupMediaSessionTracking()
            return
        }

        parserScope.launch {
            val notif = sbn.notification
            val extras = notif.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return@launch

            val category = notif.category
            val largeIconBitmap = notif.getLargeIcon()?.loadDrawable(this@IslandNotificationService)?.toBitmap(96)

            if (category == Notification.CATEGORY_CALL) {
                val actions = notif.actions.orEmpty()
                val acceptIntent = actions.firstOrNull()?.actionIntent
                val declineIntent = actions.lastOrNull()?.takeIf { actions.size > 1 }?.actionIntent

                IslandStateEngine.submitPayload(
                    IslandPayload.CommunicationActivity(
                        id = sbn.key,
                        senderName = title,
                        messageOrStatus = text.ifEmpty { subText },
                        isOngoingCall = true,
                        avatar = largeIconBitmap,
                        contentIntent = notif.contentIntent,
                        acceptIntent = acceptIntent,
                        declineIntent = declineIntent
                    )
                )
                return@launch
            }

            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val progressCur = extras.getInt(Notification.EXTRA_PROGRESS, 0)
            val isIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

            if (progressMax > 0 || category == Notification.CATEGORY_NAVIGATION || category == Notification.CATEGORY_PROGRESS || category == Notification.CATEGORY_ALARM || category == Notification.CATEGORY_STOPWATCH) {
                val fraction = if (progressMax > 0) {
                    (progressCur.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val rightText = subText.ifEmpty {
                    if (progressMax > 0) "${(fraction * 100).toInt()}%" else text
                }

                IslandStateEngine.submitPayload(
                    IslandPayload.LiveProgressActivity(
                        id = sbn.key,
                        title = title.ifEmpty { text },
                        etaOrTimerText = rightText,
                        progressFraction = fraction,
                        isIndeterminate = isIndeterminate,
                        accentColor = Color(0xFF38BDF8),
                        appIcon = largeIconBitmap,
                        contentIntent = notif.contentIntent
                    )
                )
                return@launch
            }

            val replyAction = notif.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            if (category == Notification.CATEGORY_MESSAGE || replyAction != null || sbn.isClearable) {
                IslandStateEngine.submitPayload(
                    IslandPayload.CommunicationActivity(
                        id = sbn.key,
                        senderName = title,
                        messageOrStatus = text,
                        isOngoingCall = false,
                        avatar = largeIconBitmap,
                        contentIntent = notif.contentIntent,
                        replyActionIntent = replyAction?.actionIntent,
                        remoteInput = replyAction?.remoteInputs?.firstOrNull()
                    )
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        IslandStateEngine.removePayload(key)
    }

    private fun Drawable.toBitmap(maxSize: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return Bitmap.createScaledBitmap(bitmap, maxSize, maxSize, true)
        }
        val bmp = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp
    }
}