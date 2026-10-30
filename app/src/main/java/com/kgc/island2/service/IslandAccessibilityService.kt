package com.kgc.island2.service

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kgc.island2.ai.OriginAiProcessor
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.*
import com.kgc.island2.ui.IslandOverlayScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

class IslandAccessibilityService : AccessibilityService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var composeOverlayView: ComposeView? = null
    private var isViewAttached = false

    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private lateinit var clipboardManager: ClipboardManager
    private var bluetoothManager: BluetoothManager? = null
    private var currentTorchCameraId: String? = null
    private var isTorchOn = false
    private var lastProcessedClip: String = ""

    private val rotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncQuickTileStates()
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        setupOverlayWindow()
        registerHardwareAndSystemCallbacks()
        syncQuickTileStates()
        observeDisplayModeForWindowSizing()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure overlay stays attached even when user closes all apps from Recents
        if (!isViewAttached) {
            setupOverlayWindow()
        }
    }

    private fun setupOverlayWindow() {
        if (isViewAttached) return

        val density = resources.displayMetrics.density
        val config = IslandStateEngine.cutoutConfig.value
        val circlePx = (config.idleCircleSizeDp * density).toInt()

        windowParams = WindowManager.LayoutParams(
            circlePx,
            circlePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = config.offsetX
            y = config.offsetY
        }

        composeOverlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandAccessibilityService)
            setViewTreeViewModelStoreOwner(this@IslandAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@IslandAccessibilityService)

            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val cutout = v.rootWindowInsets?.displayCutout
                    val rect = cutout?.boundingRects?.firstOrNull()
                    if (rect != null) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val cutoutCenterX = rect.centerX() - (screenWidth / 2)
                        val cutoutTopY = rect.top
                        val diameterDp = ((rect.width().coerceAtLeast(rect.height())) / density).toInt() + 4
                        IslandStateEngine.updateCutoutConfig(
                            CutoutConfig(
                                offsetX = cutoutCenterX,
                                offsetY = cutoutTopY,
                                idleCircleSizeDp = diameterDp.coerceIn(24, 48)
                            )
                        )
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })

            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        val mime = event.clipDescription?.getMimeType(0) ?: "text/plain"
                        val sendIntent = Intent(Intent.ACTION_SEND).apply { type = mime }
                        val apps = OriginAiProcessor.resolveInstalledTargetApps(
                            this@IslandAccessibilityService,
                            sendIntent,
                            maxCount = 5
                        )
                        IslandStateEngine.setDragWheelActive(true, apps)
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        val clipData = event.clipData
                        if (clipData != null && clipData.itemCount > 0) {
                            val item = clipData.getItemAt(0)
                            val text = item.text?.toString()
                            val uri = item.uri
                            IslandStateEngine.addToStash(
                                StashItem(
                                    id = System.currentTimeMillis().toString(),
                                    text = text,
                                    uri = uri
                                )
                            )
                            if (!text.isNullOrBlank()) {
                                serviceScope.launch {
                                    OriginAiProcessor.processCopiedText(this@IslandAccessibilityService, text)
                                }
                            } else if (uri != null) {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = event.clipDescription?.getMimeType(0) ?: "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(Intent.createChooser(shareIntent, null).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        }
                        IslandStateEngine.setDragWheelActive(false)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        IslandStateEngine.setDragWheelActive(false)
                        true
                    }
                    else -> true
                }
            }

            setContent {
                IslandOverlayScreen(
                    onStatusBarSwipeDown = {
                        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    },
                    onToggleTorch = { toggleFlashlight() },
                    onToggleBluetooth = { openOrToggleBluetooth() },
                    onToggleHotspot = { openHotspotSettings() },
                    onToggleAutoRotate = { toggleAutoRotate() },
                    onAdjustVolume = { delta ->
                        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                    },
                    onRequestKeyboardFocus = { needsFocus ->
                        setWindowKeyboardFocusable(needsFocus)
                    },
                    onPasteTextToFocusedApp = { textToPaste ->
                        pasteIntoFocusedNode(textToPaste)
                    }
                )
            }
        }

        try {
            windowManager.addView(composeOverlayView, windowParams)
            isViewAttached = true
        } catch (_: Exception) {
        }
    }

    private fun observeDisplayModeForWindowSizing() {
        serviceScope.launch {
            combine(
                IslandStateEngine.displayMode,
                IslandStateEngine.cutoutConfig,
                IslandStateEngine.privacyState
            ) { mode: IslandDisplayMode, config: CutoutConfig, privacy: PrivacyIndicatorState ->
                Triple(mode, config, privacy)
            }.collectLatest { (mode, config, privacy) ->
                if (!isViewAttached || composeOverlayView == null) return@collectLatest
                val density = resources.displayMetrics.density

                val (targetW, targetH) = when (mode) {
                    IslandDisplayMode.IDLE -> {
                        val basePx = (config.idleCircleSizeDp * density).toInt()
                        val widthPx = if (privacy.isCameraActive || privacy.isMicActive) {
                            basePx + (18 * density).toInt()
                        } else {
                            basePx
                        }
                        Pair(widthPx, basePx)
                    }
                    IslandDisplayMode.COMPACT_SINGLE -> Pair((215 * density).toInt(), (38 * density).toInt())
                    IslandDisplayMode.COMPACT_SPLIT -> Pair((268 * density).toInt(), (38 * density).toInt())
                    IslandDisplayMode.EXPANDED_CARD,
                    IslandDisplayMode.ORIGIN_DRAG_WHEEL,
                    IslandDisplayMode.QUICK_TOOLS -> Pair((364 * density).toInt(), (215 * density).toInt())
                }

                windowParams.width = targetW
                windowParams.height = targetH
                windowParams.x = config.offsetX
                windowParams.y = config.offsetY

                try {
                    windowManager.updateViewLayout(composeOverlayView, windowParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setWindowKeyboardFocusable(focusable: Boolean) {
        if (!isViewAttached || composeOverlayView == null) return
        windowParams.flags = if (focusable) {
            windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(composeOverlayView, windowParams)
        } catch (_: Exception) {
        }
    }

    private fun captureClipboardWithBriefFocus() {
        serviceScope.launch {
            setWindowKeyboardFocusable(true)
            delay(60L)
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val copied = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                if (copied.isNotEmpty() && copied != lastProcessedClip) {
                    lastProcessedClip = copied
                    OriginAiProcessor.processCopiedText(this@IslandAccessibilityService, copied)
                }
            }
            setWindowKeyboardFocusable(false)
        }
    }

    private fun syncQuickTileStates() {
        val btOn = try {
            bluetoothManager?.adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
        val rotateOn = try {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        } catch (_: Exception) {
            false
        }
        IslandStateEngine.updateQuickTileState(
            torch = isTorchOn,
            bluetooth = btOn,
            autoRotate = rotateOn
        )
    }

    private fun registerHardwareAndSystemCallbacks() {
        cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                IslandStateEngine.updatePrivacyDots(camera = false)
            }
            override fun onCameraUnavailable(cameraId: String) {
                IslandStateEngine.updatePrivacyDots(camera = true)
            }
        }, null)

        cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                currentTorchCameraId = cameraId
                isTorchOn = enabled
                IslandStateEngine.updateQuickTileState(torch = enabled)
            }
        }, null)

        audioManager.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                IslandStateEngine.updatePrivacyDots(mic = !configs.isNullOrEmpty())
            }
        }, null)

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            rotationObserver
        )

        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val copied = clip.getItemAt(0).text?.toString()?.trim() ?: return@addPrimaryClipChangedListener
                if (copied.isNotEmpty() && copied != lastProcessedClip) {
                    lastProcessedClip = copied
                    serviceScope.launch {
                        OriginAiProcessor.processCopiedText(this@IslandAccessibilityService, copied)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(systemEventReceiver, filter)
    }

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    composeOverlayView?.visibility = View.GONE
                    IslandStateEngine.onScreenPowerStateChanged(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    composeOverlayView?.visibility = View.VISIBLE
                    IslandStateEngine.onScreenPowerStateChanged(true)
                    syncQuickTileStates()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    syncQuickTileStates()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val currentNowMicroAmps = kotlin.math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                    val currentMa = (currentNowMicroAmps / 1000).toInt()
                    val sub = if (currentMa > 0) "$level% • ${currentMa}mA" else "$level%"

                    IslandStateEngine.submitPayload(
                        IslandPayload.SystemAlert(
                            id = "charging",
                            title = "$level%",
                            subtitle = sub,
                            accentColor = Color(0xFF4ADE80),
                            iconType = AlertIconType.CHARGING
                        )
                    )
                }
                Intent.ACTION_BATTERY_LOW -> {
                    val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    IslandStateEngine.submitPayload(
                        IslandPayload.SystemAlert(
                            id = "low_battery",
                            title = "$level%",
                            subtitle = "Battery Low",
                            accentColor = Color(0xFFEF4444),
                            iconType = AlertIconType.LOW_BATTERY
                        )
                    )
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val vol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
                    val pct = (vol * 100) / max
                    val (label, color, icon) = when (audioManager.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> Triple("0%", Color(0xFFEF4444), AlertIconType.RINGER_SILENT)
                        AudioManager.RINGER_MODE_VIBRATE -> Triple("Vibrate", Color(0xFFF59E0B), AlertIconType.RINGER_VIBRATE)
                        else -> Triple("$pct%", Color(0xFF4ADE80), AlertIconType.RINGER_NORMAL)
                    }
                    IslandStateEngine.submitPayload(
                        IslandPayload.SystemAlert(
                            id = "ringer_mode",
                            title = label,
                            subtitle = "",
                            accentColor = color,
                            iconType = icon
                        )
                    )
                }
            }
        }
    }

    private fun toggleFlashlight() {
        val camId = currentTorchCameraId ?: cameraManager.cameraIdList.firstOrNull() ?: return
        try {
            cameraManager.setTorchMode(camId, !isTorchOn)
        } catch (_: Exception) {
        }
    }

    private fun openOrToggleBluetooth() {
        try {
            val panelIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(panelIntent)
            IslandStateEngine.collapseIsland()
        } catch (_: Exception) {
        }
    }

    private fun openHotspotSettings() {
        try {
            val tetherIntent = Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(tetherIntent)
            IslandStateEngine.collapseIsland()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                IslandStateEngine.collapseIsland()
            } catch (_: Exception) {}
        }
    }

    private fun toggleAutoRotate() {
        if (Settings.System.canWrite(this)) {
            val current = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            val next = if (current == 1) 0 else 1
            Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, next)
            IslandStateEngine.updateQuickTileState(autoRotate = next == 1)
        } else {
            val writeSettingsIntent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(writeSettingsIntent)
            IslandStateEngine.collapseIsland()
        }
    }

    private fun pasteIntoFocusedNode(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val clip = ClipData.newPlainText("island_paste", text)
        clipboardManager.setPrimaryClip(clip)
        focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val textList = event.text.joinToString(" ").lowercase()
                if (textList.contains("copy") || textList.contains("copied")) {
                    captureClipboardWithBriefFocus()
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val toastText = event.text.joinToString(" ").lowercase()
                if (toastText.contains("copied")) {
                    captureClipboardWithBriefFocus()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        try {
            contentResolver.unregisterContentObserver(rotationObserver)
            unregisterReceiver(systemEventReceiver)
        } catch (_: Exception) {
        }
        if (isViewAttached && composeOverlayView != null) {
            try {
                windowManager.removeView(composeOverlayView)
            } catch (_: Exception) {
            } finally {
                isViewAttached = false
            }
        }
    }
}