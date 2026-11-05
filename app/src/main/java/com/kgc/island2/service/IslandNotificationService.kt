package com.kgc.island2.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.Color
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.IslandDisplayMode
import com.kgc.island2.model.IslandPayload
import kotlinx.coroutines.*

class IslandNotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeControllers = listOf<MediaController>()
    private var progressTickerJob: Job? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindMediaControllers(controllers ?: emptyList())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, IslandNotificationService::class.java)
            val initial = mediaSessionManager?.getActiveSessions(comp).orEmpty()
            bindMediaControllers(initial)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, comp)
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            requestRebind(ComponentName(this, IslandNotificationService::class.java))
        } catch (e: Exception) {
        }
    }

    private fun bindMediaControllers(controllers: List<MediaController>) {
        activeControllers = controllers
        val primary = controllers.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_PAUSED ||
                    state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                    state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
        } ?: controllers.firstOrNull()

        if (primary == null) {
            progressTickerJob?.cancel()
            IslandStateEngine.removePayload("media_session")
            return
        }

        publishMediaState(primary)
        primary.registerCallback(object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_NONE) {
                    IslandStateEngine.removePayload("media_session")
                } else {
                    publishMediaState(primary)
                }
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                publishMediaState(primary)
            }
        })

        progressTickerJob?.cancel()
        progressTickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (IslandStateEngine.displayMode.value == IslandDisplayMode.EXPANDED_CARD) {
                    val state = primary.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        publishMediaState(primary)
                    }
                }
            }
        }
    }

    private fun publishMediaState(controller: MediaController) {
        val meta = controller.metadata ?: return
        val state = controller.playbackState
        val pbState = state?.state ?: PlaybackState.STATE_NONE

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(1L)
        val rawArt = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val art = rawArt?.let { downscaleBitmap(it, 128) }

        val basePos = state?.position ?: 0L
        val lastUpdate = state?.lastPositionUpdateTime ?: 0L
        val currentPos = if (pbState == PlaybackState.STATE_PLAYING && lastUpdate > 0L) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastUpdate
            (basePos + (elapsed * (state?.playbackSpeed ?: 1f)).toLong()).coerceIn(0L, duration)
        } else {
            basePos.coerceIn(0L, duration)
        }

        val progressFraction = if (duration > 0L) {
            (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else 0f

        IslandStateEngine.submitPayload(
            IslandPayload.MediaActivity(
                id = "media_session",
                packageName = controller.packageName,
                trackTitle = title,
                artist = artist,
                albumArt = art,
                isPlaying = pbState == PlaybackState.STATE_PLAYING || pbState == PlaybackState.STATE_BUFFERING,
                progress = progressFraction,
                durationMs = duration,
                accentColor = extractDominantAccent(art),
                contentIntent = controller.sessionActivity,
                onPlayPause = {
                    val current = controller.playbackState?.state
                    if (current == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                },
                onNext = { controller.transportControls.skipToNext() },
                onPrevious = { controller.transportControls.skipToPrevious() },
                onSeekTo = { posMs -> controller.transportControls.seekTo(posMs) }
            )
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        if (notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val category = notification.category
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1)
        val hasProgress = progress >= 0 && progressMax > 0

        if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MESSAGE || findReplyAction(notification) != null) {
            val replyPair = findReplyAction(notification)
            val largeIconBmp = loadLargeIconBitmap(notification.getLargeIcon())

            val acceptIntent = notification.actions?.firstOrNull {
                val lbl = it.title?.toString()?.lowercase().orEmpty()
                lbl.contains("answer") || lbl.contains("accept")
            }?.actionIntent

            val declineIntent = notification.actions?.firstOrNull {
                val lbl = it.title?.toString()?.lowercase().orEmpty()
                lbl.contains("decline") || lbl.contains("reject") || lbl.contains("hang up") || lbl.contains("end")
            }?.actionIntent

            IslandStateEngine.submitPayload(
                IslandPayload.CommunicationActivity(
                    id = sbn.key,
                    senderName = title.ifEmpty { pkg },
                    messageOrStatus = text,
                    avatar = largeIconBmp,
                    isOngoingCall = category == Notification.CATEGORY_CALL && isOngoing,
                    acceptIntent = acceptIntent,
                    declineIntent = declineIntent,
                    replyActionIntent = replyPair?.first,
                    remoteInput = replyPair?.second,
                    contentIntent = notification.contentIntent
                )
            )
            return
        }

        if (hasProgress || (isOngoing && (category == Notification.CATEGORY_TRANSPORT || category == Notification.CATEGORY_NAVIGATION || category == Notification.CATEGORY_PROGRESS || category == Notification.CATEGORY_STOPWATCH || category == Notification.CATEGORY_ALARM))) {
            val fraction = if (hasProgress) (progress.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f) else 0.5f
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: if (hasProgress) "${(fraction * 100).toInt()}%" else text

            IslandStateEngine.submitPayload(
                IslandPayload.LiveProgressActivity(
                    id = sbn.key,
                    title = title,
                    etaOrTimerText = subText,
                    progressFraction = fraction,
                    appIcon = loadLargeIconBitmap(notification.getLargeIcon()),
                    accentColor = Color(0xFF38BDF8),
                    contentIntent = notification.contentIntent
                )
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        IslandStateEngine.removePayload(key)
    }

    private fun findReplyAction(notification: Notification): Pair<android.app.PendingIntent, RemoteInput>? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (input in remoteInputs) {
                if (input.allowFreeFormInput && action.actionIntent != null) {
                    return Pair(action.actionIntent, input)
                }
            }
        }
        return null
    }

    private fun loadLargeIconBitmap(icon: Icon?): Bitmap? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(this) ?: return null
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                downscaleBitmap(drawable.bitmap, 96)
            } else {
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(canvas)
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downscaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        if (src.width <= maxDim && src.height <= maxDim) return src
        return Bitmap.createScaledBitmap(src, maxDim, maxDim, true)
    }

    private fun extractDominantAccent(bmp: Bitmap?): Color {
        if (bmp == null) return Color(0xFF38BDF8)
        return try {
            val pixel = bmp.getPixel(bmp.width / 2, bmp.height / 2)
            val r = android.graphics.Color.red(pixel).coerceAtLeast(90)
            val g = android.graphics.Color.green(pixel).coerceAtLeast(90)
            val b = android.graphics.Color.blue(pixel).coerceAtLeast(90)
            Color(r, g, b)
        } catch (e: Exception) {
            Color(0xFF38BDF8)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressTickerJob?.cancel()
        serviceScope.cancel()
    }
}