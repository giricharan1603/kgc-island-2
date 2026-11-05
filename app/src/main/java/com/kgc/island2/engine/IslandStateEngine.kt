package com.kgc.island2.engine

import android.content.Context
import android.view.Surface
import androidx.compose.ui.unit.IntOffset
import com.kgc.island2.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

object IslandStateEngine {

    private const val PREFS_NAME = "kgc_island2_prefs"
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _primarySlot = MutableStateFlow<IslandPayload>(IslandPayload.Idle)
    val primarySlot: StateFlow<IslandPayload> = _primarySlot.asStateFlow()

    private val _secondarySlot = MutableStateFlow<IslandPayload?>(null)
    val secondarySlot: StateFlow<IslandPayload?> = _secondarySlot.asStateFlow()

    private val _displayMode = MutableStateFlow(IslandDisplayMode.IDLE)
    val displayMode: StateFlow<IslandDisplayMode> = _displayMode.asStateFlow()

    private val _privacyState = MutableStateFlow(PrivacyIndicatorState())
    val privacyState: StateFlow<PrivacyIndicatorState> = _privacyState.asStateFlow()

    private val _quickTileStates = MutableStateFlow(QuickTileStates())
    val quickTileStates: StateFlow<QuickTileStates> = _quickTileStates.asStateFlow()

    private val _cutoutConfig = MutableStateFlow(CutoutConfig())
    val cutoutConfig: StateFlow<CutoutConfig> = _cutoutConfig.asStateFlow()

    private val _burnInOffset = MutableStateFlow(IntOffset(0, 0))
    val burnInOffset: StateFlow<IntOffset> = _burnInOffset.asStateFlow()

    private val _stashItems = MutableStateFlow<List<StashItem>>(emptyList())
    val stashItems: StateFlow<List<StashItem>> = _stashItems.asStateFlow()

    private val _clipboardHistory = MutableStateFlow<List<String>>(emptyList())
    val clipboardHistory: StateFlow<List<String>> = _clipboardHistory.asStateFlow()

    private val _dragTargetApps = MutableStateFlow<List<TargetAppInfo>>(emptyList())
    val dragTargetApps: StateFlow<List<TargetAppInfo>> = _dragTargetApps.asStateFlow()

    private val _pinnedShortcutPackages = MutableStateFlow<Set<String>>(
        setOf("com.spotify.music", "com.whatsapp", "com.google.android.youtube", "com.android.chrome")
    )
    val pinnedShortcutPackages: StateFlow<Set<String>> = _pinnedShortcutPackages.asStateFlow()

    private val _pinnedShortcuts = MutableStateFlow<List<TargetAppInfo>>(emptyList())
    val pinnedShortcuts: StateFlow<List<TargetAppInfo>> = _pinnedShortcuts.asStateFlow()

    private val _isScreenInteractive = MutableStateFlow(true)
    val isScreenInteractive: StateFlow<Boolean> = _isScreenInteractive.asStateFlow()

    private val _isFullscreenImmersive = MutableStateFlow(false)
    val isFullscreenImmersive: StateFlow<Boolean> = _isFullscreenImmersive.asStateFlow()

    private val ongoingTasks = ConcurrentHashMap<String, IslandPayload>()
    private val notificationBurstTracker = ConcurrentHashMap<String, MutableList<Long>>()
    private var autoDismissJob: Job? = null
    private var burnInJob: Job? = null
    private var isUserMinimized = false
    private var lastTrackTitle: String? = null
    private var hasUserCustomPortraitOffset = false
    private var hasUserCustomLandscapeOffset = false

    init {
        startBurnInProtectionLoop()
    }

    fun loadPersistedPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            hasUserCustomPortraitOffset = prefs.getBoolean("has_custom_port", false)
            hasUserCustomLandscapeOffset = prefs.getBoolean("has_custom_land", false)
            val styleName = prefs.getString("circle_style", CircleAnimationStyle.CYBER_SWEEP.name)
                ?: CircleAnimationStyle.CYBER_SWEEP.name
            val style = try {
                CircleAnimationStyle.valueOf(styleName)
            } catch (_: Exception) {
                CircleAnimationStyle.CYBER_SWEEP
            }
            val sizeDp = prefs.getInt("circle_size_dp", 30)
            val savedPackages = prefs.getStringSet("pinned_pkgs", null)
            if (savedPackages != null) {
                _pinnedShortcutPackages.value = savedPackages
            }

            _cutoutConfig.update { current ->
                current.copy(
                    offsetX = prefs.getInt("port_x", current.offsetX),
                    offsetY = prefs.getInt("port_y", current.offsetY),
                    landscapeCutoutX = prefs.getInt("land_x", current.landscapeCutoutX),
                    landscapeCutoutY = prefs.getInt("land_y", current.landscapeCutoutY),
                    baseWidthDp = sizeDp,
                    baseHeightDp = sizeDp,
                    idleCircleSizeDp = sizeDp,
                    isHoldAndDragEnabled = prefs.getBoolean("hold_drag_enabled", true),
                    chargingDurationMs = prefs.getLong("charging_duration_ms", 6500L),
                    circleAnimationStyle = style
                )
            }
        } catch (_: Exception) {
        }
    }

    fun saveCutoutConfigToPrefs(
        context: Context,
        config: CutoutConfig,
        isCustomPort: Boolean = hasUserCustomPortraitOffset,
        isCustomLand: Boolean = hasUserCustomLandscapeOffset
    ) {
        hasUserCustomPortraitOffset = isCustomPort
        hasUserCustomLandscapeOffset = isCustomLand
        _cutoutConfig.value = config
        commitCutoutConfigToDisk(context)
    }

    /**
     * Senior Optimization: Updates coordinates purely in RAM during 120Hz touch drag so there is ZERO disk I/O lag!
     */
    fun updateDraggedOffsetInMemory(deltaX: Int, deltaY: Int) {
        val cur = _cutoutConfig.value
        if (!cur.isHoldAndDragEnabled) return
        val updated = if (cur.isLandscape) {
            hasUserCustomLandscapeOffset = true
            val nextX = if (cur.landscapeRotation == Surface.ROTATION_270) {
                (cur.landscapeCutoutX - deltaX).coerceIn(-600, 1200)
            } else {
                (cur.landscapeCutoutX + deltaX).coerceIn(-600, 1200)
            }
            val nextY = (cur.landscapeCutoutY + deltaY).coerceIn(-500, 500)
            cur.copy(landscapeCutoutX = nextX, landscapeCutoutY = nextY)
        } else {
            hasUserCustomPortraitOffset = true
            val nextX = (cur.offsetX + deltaX).coerceIn(-500, 500)
            val nextY = (cur.offsetY + deltaY).coerceIn(-40, 800)
            cur.copy(offsetX = nextX, offsetY = nextY)
        }
        _cutoutConfig.value = updated
    }

    fun commitCutoutConfigToDisk(context: Context) {
        val config = _cutoutConfig.value
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putInt("port_x", config.offsetX)
                putInt("port_y", config.offsetY)
                putInt("land_x", config.landscapeCutoutX)
                putInt("land_y", config.landscapeCutoutY)
                putInt("circle_size_dp", config.idleCircleSizeDp)
                putBoolean("hold_drag_enabled", config.isHoldAndDragEnabled)
                putLong("charging_duration_ms", config.chargingDurationMs)
                putString("circle_style", config.circleAnimationStyle.name)
                putBoolean("has_custom_port", hasUserCustomPortraitOffset)
                putBoolean("has_custom_land", hasUserCustomLandscapeOffset)
                apply()
            }
        } catch (_: Exception) {
        }
    }

    fun updateDraggedOffset(context: Context, deltaX: Int, deltaY: Int) {
        updateDraggedOffsetInMemory(deltaX, deltaY)
        commitCutoutConfigToDisk(context)
    }

    fun resetOffsetsToHardwareCutout(context: Context, portX: Int, portY: Int, landX: Int, landY: Int) {
        val updated = _cutoutConfig.value.copy(
            offsetX = portX,
            offsetY = portY,
            landscapeCutoutX = landX,
            landscapeCutoutY = landY
        )
        saveCutoutConfigToPrefs(context, updated, isCustomPort = false, isCustomLand = false)
    }

    fun hasCustomPortraitPosition(): Boolean = hasUserCustomPortraitOffset
    fun hasCustomLandscapePosition(): Boolean = hasUserCustomLandscapeOffset

    fun setFullscreenImmersive(immersive: Boolean) {
        _isFullscreenImmersive.value = immersive
    }

    fun togglePinnedPackage(context: Context, packageName: String) {
        val current = _pinnedShortcutPackages.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else if (current.size < 8) {
            current.add(packageName)
        }
        _pinnedShortcutPackages.value = current
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet("pinned_pkgs", current)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun updatePinnedShortcutsList(apps: List<TargetAppInfo>) {
        _pinnedShortcuts.value = apps
    }

    fun submitPayload(payload: IslandPayload) {
        engineScope.launch {
            when (payload) {
                is IslandPayload.SystemAlert,
                is IslandPayload.OriginAiSuggestion -> {
                    if (payload.isOngoing) {
                        val existing = ongoingTasks[payload.id]
                        if (existing != null && existing is IslandPayload.SystemAlert &&
                            existing.title == (payload as? IslandPayload.SystemAlert)?.title
                        ) {
                            return@launch
                        }
                        isUserMinimized = false
                        ongoingTasks[payload.id] = payload
                        recalculateSlots()
                    } else {
                        val previousPrimary = _primarySlot.value
                        _primarySlot.value = payload
                        _displayMode.value = IslandDisplayMode.COMPACT_SINGLE

                        autoDismissJob?.cancel()
                        val timeout = payload.autoDismissMs ?: 2800L
                        autoDismissJob = launch {
                            delay(timeout)
                            restoreOngoingState(previousPrimary)
                        }
                    }
                }

                is IslandPayload.CommunicationActivity -> {
                    isUserMinimized = false
                    if (payload.isOngoingCall) {
                        ongoingTasks[payload.id] = payload
                        recalculateSlots()
                    } else {
                        val cooledPayload = applyNotificationCooldown(payload)
                        _primarySlot.value = cooledPayload
                        _displayMode.value = IslandDisplayMode.COMPACT_SINGLE
                        autoDismissJob?.cancel()
                        autoDismissJob = launch {
                            delay(4000L)
                            recalculateSlots()
                        }
                    }
                }

                is IslandPayload.MediaActivity -> {
                    if (lastTrackTitle != payload.trackTitle) {
                        lastTrackTitle = payload.trackTitle
                        isUserMinimized = false
                    }
                    ongoingTasks[payload.id] = payload
                    recalculateSlots()
                }

                is IslandPayload.LiveProgressActivity -> {
                    val isNewTask = !ongoingTasks.containsKey(payload.id)
                    if (isNewTask) isUserMinimized = false
                    ongoingTasks[payload.id] = payload
                    recalculateSlots()
                }

                IslandPayload.Idle -> {
                    recalculateSlots()
                }
            }
        }
    }

    fun removePayload(id: String) {
        val removed = ongoingTasks.remove(id) ?: return
        if (id == "media_session") lastTrackTitle = null
        if (ongoingTasks.isEmpty()) isUserMinimized = false
        if (removed.id == id) {
            recalculateSlots()
        }
    }

    private fun restoreOngoingState(fallback: IslandPayload) {
        if (ongoingTasks.isNotEmpty()) {
            recalculateSlots()
        } else if (fallback !is IslandPayload.SystemAlert &&
            fallback !is IslandPayload.OriginAiSuggestion &&
            fallback !is IslandPayload.Idle
        ) {
            _primarySlot.value = fallback
            _displayMode.value = IslandDisplayMode.COMPACT_SPLIT
        } else {
            _primarySlot.value = IslandPayload.Idle
            _displayMode.value = IslandDisplayMode.IDLE
        }
    }

    private fun recalculateSlots() {
        val sorted = ongoingTasks.values.sortedWith(
            compareBy<IslandPayload> { it.priority.level }.thenByDescending { it.timestamp }
        )
        when {
            sorted.isEmpty() -> {
                _primarySlot.value = IslandPayload.Idle
                _secondarySlot.value = null
                if (_displayMode.value != IslandDisplayMode.QUICK_TOOLS &&
                    _displayMode.value != IslandDisplayMode.ORIGIN_DRAG_WHEEL
                ) {
                    _displayMode.value = IslandDisplayMode.IDLE
                }
            }
            sorted.size == 1 -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = null
                if (isUserMinimized) {
                    _displayMode.value = IslandDisplayMode.IDLE
                } else if (_displayMode.value != IslandDisplayMode.EXPANDED_CARD &&
                    _displayMode.value != IslandDisplayMode.QUICK_TOOLS &&
                    _displayMode.value != IslandDisplayMode.ORIGIN_DRAG_WHEEL
                ) {
                    _displayMode.value = if (sorted[0] is IslandPayload.MediaActivity) {
                        IslandDisplayMode.COMPACT_SPLIT
                    } else {
                        IslandDisplayMode.COMPACT_SINGLE
                    }
                }
            }
            else -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = sorted[1]
                if (isUserMinimized) {
                    _displayMode.value = IslandDisplayMode.IDLE
                } else if (_displayMode.value != IslandDisplayMode.EXPANDED_CARD &&
                    _displayMode.value != IslandDisplayMode.QUICK_TOOLS &&
                    _displayMode.value != IslandDisplayMode.ORIGIN_DRAG_WHEEL
                ) {
                    _displayMode.value = IslandDisplayMode.COMPACT_SPLIT
                }
            }
        }
    }

    fun swapPrimaryAndSecondary() {
        val currentPrimary = _primarySlot.value
        val currentSecondary = _secondarySlot.value
        if (currentSecondary != null && currentPrimary !is IslandPayload.Idle) {
            val now = System.currentTimeMillis()
            val updatedSecondary = when (currentSecondary) {
                is IslandPayload.MediaActivity -> currentSecondary.copy(timestamp = now + 10)
                is IslandPayload.LiveProgressActivity -> currentSecondary.copy(timestamp = now + 10)
                is IslandPayload.CommunicationActivity -> currentSecondary.copy(timestamp = now + 10)
                is IslandPayload.SystemAlert -> currentSecondary.copy(timestamp = now + 10)
                else -> currentSecondary
            }
            ongoingTasks[updatedSecondary.id] = updatedSecondary
            recalculateSlots()
        } else {
            _displayMode.value = IslandDisplayMode.QUICK_TOOLS
        }
    }

    private fun applyNotificationCooldown(incoming: IslandPayload.CommunicationActivity): IslandPayload.CommunicationActivity {
        val now = System.currentTimeMillis()
        val history = notificationBurstTracker.getOrPut(incoming.id) { mutableListOf() }
        history.removeAll { now - it > 10_000L }
        history.add(now)

        return if (history.size >= 3) {
            incoming.copy(
                messageOrStatus = "(${history.size}) ${incoming.messageOrStatus}",
                burstCount = history.size
            )
        } else {
            incoming
        }
    }

    fun onIdleCircleTapped() {
        if (ongoingTasks.isNotEmpty()) {
            isUserMinimized = false
            recalculateSlots()
        } else {
            _displayMode.value = IslandDisplayMode.QUICK_TOOLS
        }
    }

    fun openQuickTools() {
        _displayMode.value = IslandDisplayMode.QUICK_TOOLS
    }

    fun expandIsland() {
        isUserMinimized = false
        if (_primarySlot.value !is IslandPayload.Idle) {
            _displayMode.value = IslandDisplayMode.EXPANDED_CARD
        } else {
            _displayMode.value = IslandDisplayMode.QUICK_TOOLS
        }
    }

    fun collapseIsland() {
        if (ongoingTasks.isEmpty()) {
            _displayMode.value = IslandDisplayMode.IDLE
        } else {
            recalculateSlots()
        }
    }

    fun minimizeToIdle() {
        isUserMinimized = true
        _displayMode.value = IslandDisplayMode.IDLE
    }

    fun handleDismissSwipe() {
        if (_cutoutConfig.value.isLandscape) {
            minimizeToIdle()
            return
        }
        when (_displayMode.value) {
            IslandDisplayMode.EXPANDED_CARD,
            IslandDisplayMode.QUICK_TOOLS,
            IslandDisplayMode.ORIGIN_DRAG_WHEEL -> {
                if (ongoingTasks.isEmpty()) {
                    minimizeToIdle()
                } else {
                    collapseIsland()
                }
            }
            IslandDisplayMode.COMPACT_SINGLE,
            IslandDisplayMode.COMPACT_SPLIT -> {
                minimizeToIdle()
            }
            IslandDisplayMode.IDLE -> {}
        }
    }

    fun setDragWheelActive(active: Boolean, apps: List<TargetAppInfo> = emptyList()) {
        if (active) {
            _dragTargetApps.value = apps
            _displayMode.value = IslandDisplayMode.ORIGIN_DRAG_WHEEL
        } else {
            recalculateSlots()
        }
    }

    fun updatePrivacyDots(camera: Boolean? = null, mic: Boolean? = null) {
        _privacyState.update {
            it.copy(
                isCameraActive = camera ?: it.isCameraActive,
                isMicActive = mic ?: it.isMicActive
            )
        }
    }

    fun updatePrivacyState(camera: Boolean? = null, mic: Boolean? = null) =
        updatePrivacyDots(camera, mic)

    fun updateQuickTileState(
        torch: Boolean? = null,
        bluetooth: Boolean? = null,
        hotspot: Boolean? = null,
        autoRotate: Boolean? = null,
        ringerMode: Int? = null,
        volumeFraction: Float? = null,
        batteryPct: Int? = null
    ) {
        _quickTileStates.update {
            it.copy(
                isTorchOn = torch ?: it.isTorchOn,
                isBluetoothOn = bluetooth ?: it.isBluetoothOn,
                isHotspotOn = hotspot ?: it.isHotspotOn,
                isAutoRotateOn = autoRotate ?: it.isAutoRotateOn,
                ringerMode = ringerMode ?: it.ringerMode,
                volumeFraction = volumeFraction ?: it.volumeFraction,
                batteryPct = batteryPct ?: it.batteryPct
            )
        }
    }

    fun updateQuickTileStates(
        torch: Boolean? = null,
        bluetooth: Boolean? = null,
        hotspot: Boolean? = null,
        autoRotate: Boolean? = null,
        ringerMode: Int? = null,
        volumeFraction: Float? = null,
        batteryPct: Int? = null
    ) = updateQuickTileState(torch, bluetooth, hotspot, autoRotate, ringerMode, volumeFraction, batteryPct)

    fun addToStash(item: StashItem) {
        _stashItems.update { (listOf(item) + it).take(10) }
    }

    fun addStashItem(item: StashItem) = addToStash(item)

    fun recordClipboard(text: String) {
        if (text.isBlank()) return
        _clipboardHistory.update { current ->
            (listOf(text.trim()) + current.filterNot { it == text.trim() }).take(8)
        }
    }

    fun addClipboardEntry(text: String) = recordClipboard(text)

    fun updateCutoutConfig(config: CutoutConfig) {
        _cutoutConfig.value = config
    }

    fun updateBurnInOffset(offset: IntOffset) {
        _burnInOffset.value = offset
    }

    fun onScreenPowerStateChanged(isInteractive: Boolean) {
        _isScreenInteractive.value = isInteractive
        if (!isInteractive) {
            burnInJob?.cancel()
        } else {
            startBurnInProtectionLoop()
        }
    }

    private fun startBurnInProtectionLoop() {
        burnInJob?.cancel()
        burnInJob = engineScope.launch {
            var step = 0.0
            while (isActive) {
                delay(180_000L)
                step += 0.8
                val dx = (cos(step) * 2).toInt()
                val dy = (sin(step) * 2).toInt()
                _burnInOffset.value = IntOffset(dx, dy)
            }
        }
    }
}