package com.kgc.island2.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.Color
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.IslandPayload
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class IslandNotificationService : NotificationListenerService() {

    private data class LyricEntry(val timeMs: Long, val text: String)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var progressTickerJob: Job? = null

    private val lyricsMemoryCache = ConcurrentHashMap<String, List<LyricEntry>>()
    private var currentlyFetchingKey: String = ""

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
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            requestRebind(ComponentName(this, IslandNotificationService::class.java))
        } catch (e: Exception) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            requestRebind(ComponentName(this, IslandNotificationService::class.java))
        } catch (e: Exception) {
        }
    }

    private fun bindMediaControllers(controllers: List<MediaController>) {
        activeCallbacks.forEach { (controller, callback) ->
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {}
        }
        activeCallbacks.clear()
        progressTickerJob?.cancel()

        if (controllers.isEmpty()) {
            clearActiveLyricLine()
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
                    clearActiveLyricLine()
                    IslandStateEngine.removePayload("media_session")
                }
            }
            try {
                controller.registerCallback(callback)
                activeCallbacks[controller] = callback
                publishMediaState(controller)
            } catch (e: Exception) {}
        }

        progressTickerJob = serviceScope.launch {
            while (isActive) {
                delay(900L)
                val activeCtrl = activeCallbacks.keys.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                if (activeCtrl != null) {
                    publishMediaState(activeCtrl)
                }
            }
        }
    }

    private fun publishMediaState(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val state = controller.playbackState
        val pbState = state?.state ?: PlaybackState.STATE_NONE

        if (pbState == PlaybackState.STATE_STOPPED || pbState == PlaybackState.STATE_NONE) {
            clearActiveLyricLine()
            IslandStateEngine.removePayload("media_session")
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val art = downscaleBitmap(rawArt, 128)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(1L)

        val basePos = state?.position ?: 0L
        val lastUpdate = state?.lastPositionUpdateTime ?: 0L
        val speed = state?.playbackSpeed ?: 1f
        val currentPos = if (pbState == PlaybackState.STATE_PLAYING && lastUpdate > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - lastUpdate).coerceAtLeast(0L)
            (basePos + (elapsed * speed).toLong()).coerceIn(0L, duration)
        } else {
            basePos.coerceIn(0L, duration)
        }

        val progress = (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val isPlaying = pbState == PlaybackState.STATE_PLAYING || pbState == PlaybackState.STATE_BUFFERING

        val songKey = "$title|$artist".lowercase()
        val syncedEntries = lyricsMemoryCache[songKey]
        if (syncedEntries == null && currentlyFetchingKey != songKey) {
            currentlyFetchingKey = songKey
            clearActiveLyricLine()
            fetchSyncedLyricsAsync(songKey, title, artist)
        }
        val activeLyric = syncedEntries
            ?.lastOrNull { it.timeMs <= currentPos + 300L }
            ?.text
            .orEmpty()
        updateActiveLyricLine(activeLyric)

        IslandStateEngine.submitPayload(
            IslandPayload.MediaActivity(
                id = "media_session",
                trackTitle = title,
                artist = artist,
                albumArt = art,
                accentColor = extractDominantColor(art),
                isPlaying = isPlaying,
                progress = progress,
                durationMs = duration,
                packageName = controller.packageName,
                contentIntent = controller.sessionActivity,
                onPlayPause = {
                    try {
                        val currentPb = controller.playbackState?.state
                        if (currentPb == PlaybackState.STATE_PLAYING) {
                            controller.transportControls.pause()
                        } else {
                            controller.transportControls.play()
                        }
                    } catch (e: Exception) {}
                },
                onNext = {
                    try { controller.transportControls.skipToNext() } catch (e: Exception) {}
                },
                onPrevious = {
                    try { controller.transportControls.skipToPrevious() } catch (e: Exception) {}
                },
                onSeekTo = { seekPosMs ->
                    try { controller.transportControls.seekTo(seekPosMs) } catch (e: Exception) {}
                }
            )
        )
    }

    private fun fetchSyncedLyricsAsync(songKey: String, title: String, artist: String) {
        serviceScope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
                val qTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                val qArtist = URLEncoder.encode(artist, "UTF-8")
                val url = URL("https://lrclib.net/api/search?track_name=$qTitle&artist_name=$qArtist")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "KgcIsland2/1.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONArray(body)
                    if (arr.length() > 0) {
                        val first: JSONObject = arr.getJSONObject(0)
                        val syncedLrc = first.optString("syncedLyrics", "")
                        val plainLrc = first.optString("plainLyrics", "")
                        val parsed = if (syncedLrc.isNotBlank()) {
                            parseLrcString(syncedLrc)
                        } else if (plainLrc.isNotBlank()) {
                            plainLrc.lines()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .mapIndexed { idx, line -> LyricEntry(idx * 4000L, line) }
                        } else {
                            emptyList()
                        }
                        lyricsMemoryCache[songKey] = parsed
                    } else {
                        lyricsMemoryCache[songKey] = emptyList()
                    }
                } else {
                    lyricsMemoryCache[songKey] = emptyList()
                }
            } catch (e: Exception) {
                lyricsMemoryCache[songKey] = emptyList()
            } finally {
                try { conn?.disconnect() } catch (e: Exception) {}
            }
        }
    }

    private fun parseLrcString(lrc: String): List<LyricEntry> {
        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
        val list = mutableListOf<LyricEntry>()
        for (line in lrc.lines()) {
            val match = regex.find(line) ?: continue
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fracStr = match.groupValues[3]
            val ms = if (fracStr.length == 2) {
                (fracStr.toLongOrNull() ?: 0L) * 10L
            } else {
                fracStr.toLongOrNull() ?: 0L
            }
            val text = match.groupValues[4].trim()
            if (text.isNotEmpty()) {
                list.add(LyricEntry(min * 60_000L + sec * 1000L + ms, text))
            }
        }
        return list.sortedBy { it.timeMs }
    }

    private fun updateActiveLyricLine(lyric: String) {
        try {
            val prefs = getSharedPreferences("kgc_island2_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("current_lyric_line", "") != lyric) {
                prefs.edit().putString("current_lyric_line", lyric).apply()
            }
        } catch (e: Exception) {}
    }

    private fun clearActiveLyricLine() {
        updateActiveLyricLine("")
    }

    private fun triggerEdgeLightFlashForPackage(pkg: String) {
        val hexColor: Long = when {
            pkg.contains("whatsapp", true) -> 0xFF25D366L
            pkg.contains("telegram", true) || pkg.contains("messaging", true) || pkg.contains("mms", true) -> 0xFF38BDF8L
            pkg.contains("youtube", true) -> 0xFFEF4444L
            pkg.contains("instagram", true) -> 0xFFEC4899L
            pkg.contains("snapchat", true) -> 0xFFFACC15L
            else -> 0xFFA855F7L
        }
        try {
            getSharedPreferences("kgc_island2_prefs", Context.MODE_PRIVATE).edit()
                .putLong("edge_flash_color", hexColor)
                .putLong("edge_flash_timestamp", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        triggerEdgeLightFlashForPackage(sbn.packageName)

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

    override fun onDestroy() {
        super.onDestroy()
        progressTickerJob?.cancel()
        serviceScope.cancel()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {}
    }

    private fun downscaleBitmap(src: Bitmap?, maxDim: Int): Bitmap? {
        if (src == null || src.isRecycled) return null
        return try {
            if (src.width <= maxDim && src.height <= maxDim) return src
            Bitmap.createScaledBitmap(src, maxDim, maxDim, true)
        } catch (e: Exception) {
            src
        }
    }

    private fun getSmallAppIconBitmap(pkg: String): Bitmap? {
        return try {
            val drawable: Drawable = packageManager.getApplicationIcon(pkg)
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                downscaleBitmap(drawable.bitmap, 64)
            } else {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 64, 64)
                drawable.draw(canvas)
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractDominantColor(bitmap: Bitmap?): Color {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return Color(0xFF22C55E)
        return try {
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val r = ((pixel shr 16) and 0xFF).coerceAtLeast(80)
            val g = ((pixel shr 8) and 0xFF).coerceAtLeast(80)
            val b = (pixel and 0xFF).coerceAtLeast(80)
            Color(r, g, b)
        } catch (e: Exception) {
            Color(0xFF22C55E)
        }
    }
}