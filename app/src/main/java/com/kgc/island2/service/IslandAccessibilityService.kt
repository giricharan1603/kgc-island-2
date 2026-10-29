package com.kgc.island2.service

import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
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
    private var currentTorchCameraId: String? = null
    private var isTorchOn = false
    private var lastProcessedClip: String = ""

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        setupOverlayWindow()
        registerHardwareAndSystemCallbacks()
        observeDisplayModeForWindowSizing()
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

            // Auto-align to physical camera cutout on first attach
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

            // Global Drag & Go Listener with Real Installed Apps
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
                        // Exact circle around camera punch-hole (widens slightly only if camera/mic dot is active)
                        val basePx = (config.idleCircleSizeDp * density).toInt()
                        val widthPx = if (privacy.isCameraActive || privacy.isMicActive) {
                            basePx + (18 * density).toInt()
                        } else {
                            basePx
                        }
                        Pair(widthPx, basePx)
                    }
                    IslandDisplayMode.COMPACT_SINGLE -> Pair((215 * density).toInt(), (38 * density).toInt())
                    IslandDisplayMode.COMPACT_SPLIT -> Pair((265 * density).toInt(), (38 * density).toInt())
                    IslandDisplayMode.EXPANDED_CARD,
                    IslandDisplayMode.ORIGIN_DRAG_WHEEL,
                    IslandDisplayMode.QUICK_TOOLS -> Pair((364 * density).toInt(), (210 * density).toInt())
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

    /**
     * Android 10+ blocks background clipboard access unless the overlay window briefly holds focus.
     * This acquires focus for 60ms on copy events to read the real clipboard and immediately releases it.
     */
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
                if (enabled) {
                    IslandStateEngine.submitPayload(
                        IslandPayload.SystemAlert(
                            id = "torch_alert",
                            title = "Torch",
                            subtitle = "ON",
                            accentColor = Color(0xFFFACC15),
                            iconType = AlertIconType.TORCH,
                            autoDismissMs = 3500L
                        )
                    )
                } else {
                    IslandStateEngine.removePayload("torch_alert")
                }
            }
        }, null)

        audioManager.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                IslandStateEngine.updatePrivacyDots(mic = !configs.isNullOrEmpty())
            }
        }, null)

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