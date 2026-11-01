package com.kgc.island2.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
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
import kotlin.math.roundToInt

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
    private var isLifecycleInitialized = false

    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private lateinit var clipboardManager: ClipboardManager
    private var bluetoothManager: BluetoothManager? = null
    private var currentTorchCameraId: String? = null
    private var isTorchOn = false
    private var isHotspotOn = false
    private var lastProcessedClip: String = ""

    private val rotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncQuickTileStates()
        }
    }

    override fun onCreate() {
        super.onCreate()
        initLifecycleIfNeeded()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private fun initLifecycleIfNeeded() {
        if (!isLifecycleInitialized) {
            isLifecycleInitialized = true
            try {
                savedStateRegistryController.performRestore(null)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            } catch (_: Exception) {
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            initLifecycleIfNeeded()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            setupOverlayWindow()
            registerHardwareAndSystemCallbacks()
            syncQuickTileStates()
            observeDisplayModeForWindowSizing()
        } catch (_: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        composeOverlayView?.post {
            detectCutoutAndOrientation()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isViewAttached) {
            setupOverlayWindow()
        }
    }

    private fun setupOverlayWindow() {
        if (isViewAttached) return

        val density = resources.displayMetrics.density
        val config = IslandStateEngine.cutoutConfig.value
        val circlePx = (config.idleCircleSizeDp * density).toInt().coerceAtLeast(48)

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
                    v.post { detectCutoutAndOrientation() }
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
                    onToggleTorch = { toggleFlashlightDirectly() },
                    onToggleBluetooth = { toggleBluetoothDirectly() },
                    onToggleHotspot = { toggleHotspotDirectly() },
                    onToggleAutoRotate = { toggleAutoRotateDirectly() },
                    onToggleRingerMode = { toggleRingerModeDirectly() },
                    onSetVolumeFraction = { fraction -> setMusicVolumeFraction(fraction) },
                    onSetBrightnessFraction = { fraction -> setScreenBrightnessFraction(fraction) },
                    onAdjustVolume = { delta ->
                        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                        syncQuickTileStates()
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

    /**
     * Safe for Android 13: Uses view.display or windowManager.defaultDisplay instead of Service.getDisplay()
     * so Android 13 never throws UnsupportedOperationException!
     */
    private fun detectCutoutAndOrientation() {
        try {
            val v = composeOverlayView ?: return
            val density = resources.displayMetrics.density
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            @Suppress("DEPRECATION")
            val rotation = v.display?.rotation ?: windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

            val current = IslandStateEngine.cutoutConfig.value
            var portX = current.offsetX
            var portY = current.offsetY
            var landX = current.landscapeCutoutX
            var landY = current.landscapeCutoutY
            var circleDp = current.idleCircleSizeDp

            val cutout = v.rootWindowInsets?.displayCutout
            val rect = cutout?.boundingRects?.firstOrNull()
            if (rect != null) {
                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels
                val detectedDp = ((rect.width().coerceAtLeast(rect.height())) / density).toInt() + 4
                circleDp = detectedDp.coerceIn(26, 48)
                val circlePx = (circleDp * density).roundToInt()

                if (isLandscape) {
                    landX = if (rotation == Surface.ROTATION_270) {
                        (screenW - rect.centerX() - (circlePx / 2)).coerceAtLeast(8)
                    } else {
                        (rect.centerX() - (circlePx / 2)).coerceAtLeast(8)
                    }
                    landY = rect.centerY() - (screenH / 2)
                } else {
                    portX = rect.centerX() - (screenW / 2)
                    portY = rect.top.coerceAtLeast(6)
                }
            }

            IslandStateEngine.updateCutoutConfig(
                current.copy(
                    offsetX = portX,
                    offsetY = portY,
                    baseWidthDp = circleDp,
                    baseHeightDp = circleDp,
                    idleCircleSizeDp = circleDp,
                    isLandscape = isLandscape,
                    landscapeRotation = rotation,
                    landscapeCutoutX = landX,
                    landscapeCutoutY = landY
                )
            )
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
                    IslandDisplayMode.QUICK_TOOLS -> Pair((364 * density).toInt(), (226 * density).toInt())
                }

                windowParams.width = targetW
                windowParams.height = targetH

                if (config.isLandscape && mode == IslandDisplayMode.IDLE) {
                    if (config.landscapeRotation == Surface.ROTATION_270) {
                        windowParams.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        windowParams.x = config.landscapeCutoutX
                        windowParams.y = config.landscapeCutoutY
                    } else {
                        windowParams.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        windowParams.x = config.landscapeCutoutX
                        windowParams.y = config.landscapeCutoutY
                    }
                } else if (config.isLandscape) {
                    windowParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    windowParams.x = 0
                    windowParams.y = (10 * density).toInt()
                } else {
                    windowParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    windowParams.x = config.offsetX
                    windowParams.y = config.offsetY
                }

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
            try {
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
            } catch (_: Exception) {
            } finally {
                setWindowKeyboardFocusable(false)
            }
        }
    }

    private fun syncQuickTileStates() {
        try {
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
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val curBright = try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            } catch (_: Exception) {
                128
            }

            IslandStateEngine.updateQuickTileState(
                torch = isTorchOn,
                bluetooth = btOn,
                hotspot = isHotspotOn,
                autoRotate = rotateOn,
                ringerMode = audioManager.ringerMode,
                volumeFraction = (curVol.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f),
                brightnessFraction = (curBright.toFloat() / 255f).coerceIn(0.05f, 1f)
            )
        } catch (_: Exception) {
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerHardwareAndSystemCallbacks() {
        val mainHandler = Handler(Looper.getMainLooper())

        try {
            cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    IslandStateEngine.updatePrivacyDots(camera = false)
                }
                override fun onCameraUnavailable(cameraId: String) {
                    IslandStateEngine.updatePrivacyDots(camera = true)
                }
            }, mainHandler)

            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    currentTorchCameraId = cameraId
                    isTorchOn = enabled
                    IslandStateEngine.updateQuickTileState(torch = enabled)
                }
            }, mainHandler)
        } catch (_: Exception) {
        }

        try {
            audioManager.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    IslandStateEngine.updatePrivacyDots(mic = !configs.isNullOrEmpty())
                }
            }, mainHandler)
        } catch (_: Exception) {
        }

        try {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                rotationObserver
            )
        } catch (_: Exception) {
        }

        try {
            clipboardManager.addPrimaryClipChangedListener {
                try {
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
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemEventReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(systemEventReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
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
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        val state = intent.getIntExtra("wifi_state", 0)
                        isHotspotOn = (state == 13 || state == 12)
                        IslandStateEngine.updateQuickTileState(hotspot = isHotspotOn)
                        if (state == 13) {
                            IslandStateEngine.submitPayload(
                                IslandPayload.SystemAlert(
                                    id = "hotspot_active",
                                    title = "Hotspot",
                                    subtitle = "Active",
                                    accentColor = Color(0xFF22C55E),
                                    iconType = AlertIconType.HOTSPOT,
                                    isOngoing = true
                                )
                            )
                        } else if (state == 11 || state == 14) {
                            IslandStateEngine.removePayload("hotspot_active")
                        }
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
                        syncQuickTileStates()
                        val vol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
                        val pct = (vol * 100) / max
                        val (label, color, icon) = when (audioManager.ringerMode) {
                            AudioManager.RINGER_MODE_SILENT -> Triple("Silent", Color(0xFFEF4444), AlertIconType.RINGER_SILENT)
                            AudioManager.RINGER_MODE_VIBRATE -> Triple("Vibrate", Color(0xFFF59E0B), AlertIconType.RINGER_VIBRATE)
                            else -> Triple("Ring $pct%", Color(0xFF4ADE80), AlertIconType.RINGER_NORMAL)
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
            } catch (_: Exception) {
            }
        }
    }

    private fun toggleFlashlightDirectly() {
        val camId = currentTorchCameraId ?: cameraManager.cameraIdList.firstOrNull() ?: return
        try {
            val next = !isTorchOn
            cameraManager.setTorchMode(camId, next)
            isTorchOn = next
            IslandStateEngine.updateQuickTileState(torch = next)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleBluetoothDirectly() {
        val adapter = bluetoothManager?.adapter ?: return
        val currentlyOn = try { adapter.isEnabled } catch (_: Exception) { false }
        val nextState = !currentlyOn

        var handledDirectly = false
        try {
            @Suppress("DEPRECATION")
            handledDirectly = if (nextState) adapter.enable() else adapter.disable()
        } catch (_: Exception) {
            handledDirectly = false
        }

        if (handledDirectly) {
            IslandStateEngine.updateQuickTileState(bluetooth = nextState)
        } else {
            toggleQuickSettingsTileDirectly(listOf("Bluetooth", "BT")) {
                IslandStateEngine.updateQuickTileState(bluetooth = nextState)
            }
        }
    }

    private fun toggleHotspotDirectly() {
        val nextHotspot = !isHotspotOn
        toggleQuickSettingsTileDirectly(listOf("Hotspot", "Wi-Fi hotspot", "Personal hotspot", "Tethering")) {
            isHotspotOn = nextHotspot
            IslandStateEngine.updateQuickTileState(hotspot = nextHotspot)
            if (nextHotspot) {
                IslandStateEngine.submitPayload(
                    IslandPayload.SystemAlert(
                        id = "hotspot_active",
                        title = "Hotspot",
                        subtitle = "Active",
                        accentColor = Color(0xFF22C55E),
                        iconType = AlertIconType.HOTSPOT,
                        isOngoing = true
                    )
                )
            } else {
                IslandStateEngine.removePayload("hotspot_active")
            }
        }
    }

    private fun toggleQuickSettingsTileDirectly(
        keywords: List<String>,
        onCompleted: () -> Unit
    ) {
        serviceScope.launch {
            try {
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                delay(280L)
                val root = rootInActiveWindow
                var clicked = false
                if (root != null) {
                    clicked = findAndClickTileNode(root, keywords)
                }
                delay(180L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (clicked) {
                    onCompleted()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun findAndClickTileNode(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val matches = keywords.any { kw ->
            text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true)
        }
        if (matches) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                current = current.parent
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickTileNode(child, keywords)) return true
        }
        return false
    }

    private fun toggleAutoRotateDirectly() {
        try {
            if (Settings.System.canWrite(this)) {
                val current = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                val next = if (current == 1) 0 else 1
                Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, next)
                IslandStateEngine.updateQuickTileState(autoRotate = next == 1)
            } else {
                val writeSettingsIntent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    "package:$packageName".toUri()
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(writeSettingsIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun toggleRingerModeDirectly() {
        val current = audioManager.ringerMode
        val next = when (current) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try {
            audioManager.ringerMode = next
            IslandStateEngine.updateQuickTileState(ringerMode = next)
        } catch (_: SecurityException) {
            val fallback = if (current == AudioManager.RINGER_MODE_NORMAL) {
                AudioManager.RINGER_MODE_VIBRATE
            } else {
                AudioManager.RINGER_MODE_NORMAL
            }
            try {
                audioManager.ringerMode = fallback
                IslandStateEngine.updateQuickTileState(ringerMode = fallback)
            } catch (_: Exception) {}
        }
    }

    private fun setMusicVolumeFraction(fraction: Float) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (fraction * max).roundToInt().coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            IslandStateEngine.updateQuickTileState(volumeFraction = fraction.coerceIn(0f, 1f))
        } catch (_: Exception) {
        }
    }

    private fun setScreenBrightnessFraction(fraction: Float) {
        try {
            if (Settings.System.canWrite(this)) {
                val target = (fraction * 255f).roundToInt().coerceIn(10, 255)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
                IslandStateEngine.updateQuickTileState(brightnessFraction = fraction.coerceIn(0.05f, 1f))
            } else {
                val writeSettingsIntent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    "package:$packageName".toUri()
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(writeSettingsIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun pasteIntoFocusedNode(text: String) {
        try {
            val root = rootInActiveWindow ?: return
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            val clip = ClipData.newPlainText("island_paste", text)
            clipboardManager.setPrimaryClip(clip)
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
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
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
            serviceScope.cancel()
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