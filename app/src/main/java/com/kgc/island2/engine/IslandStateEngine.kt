package com.kgc.island2.engine

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.IntOffset
import com.kgc.island2.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

object IslandStateEngine {

    private const val PREFS_NAME = "kgc_island2_prefs"
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _displayMode = MutableStateFlow(IslandDisplayMode.IDLE)
    val displayMode: StateFlow<IslandDisplayMode> = _displayMode.asStateFlow()

    private val _primarySlot = MutableStateFlow<IslandPayload>(IslandPayload.Idle)
    val primarySlot: StateFlow<IslandPayload> = _primarySlot.asStateFlow()

    private val _secondarySlot = MutableStateFlow<IslandPayload?>(null)
    val secondarySlot: StateFlow<IslandPayload?> = _secondarySlot.asStateFlow()

    private val _privacyState = MutableStateFlow(PrivacyIndicatorState())
    val privacyState: StateFlow<PrivacyIndicatorState> = _privacyState.asStateFlow()

    private val _quickTileStates = MutableStateFlow(QuickTileStates())
    val quickTileStates: StateFlow<QuickTileStates> = _quickTileStates.asStateFlow()

    private val _cutoutConfig = MutableStateFlow(CutoutConfig())
    val cutoutConfig: StateFlow<CutoutConfig> = _cutoutConfig.asStateFlow()

    private val _burnInOffset = MutableStateFlow(IntOffset.Zero)
    val burnInOffset: StateFlow<IntOffset> = _burnInOffset.asStateFlow()

    private val _clipboardHistory = MutableStateFlow<List<String>>(emptyList())
    val clipboardHistory: StateFlow<List<String>> = _clipboardHistory.asStateFlow()

    private val _stashItems = MutableStateFlow<List<StashItem>>(emptyList())
    val stashItems: StateFlow<List<StashItem>> = _stashItems.asStateFlow()

    private val _dragTargetApps = MutableStateFlow<List<TargetAppInfo>>(emptyList())
    val dragTargetApps: StateFlow<List<TargetAppInfo>> = _dragTargetApps.asStateFlow()

    private val _pinnedShortcutPackages = MutableStateFlow<Set<String>>(emptySet())
    val pinnedShortcutPackages: StateFlow<Set<String>> = _pinnedShortcutPackages.asStateFlow()

    private val _pinnedShortcuts = MutableStateFlow<List<TargetAppInfo>>(emptyList())
    val pinnedShortcuts: StateFlow<List<TargetAppInfo>> = _pinnedShortcuts.asStateFlow()

    private val ongoingTasks = ConcurrentHashMap<String, IslandPayload>()
    private var transientAlertJob: Job? = null
    private var burnInJob: Job? = null
    private var isUserMinimized: Boolean = false
    private var lastKnownTrackTitle: String? = null

    init {
        startBurnInProtectionLoop()
    }

    fun loadPersistedPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val styleName = prefs.getString("circle_style", CircleAnimationStyle.CYBER_SWEEP.name)
                ?: CircleAnimationStyle.CYBER_SWEEP.name
            val style = try {
                CircleAnimationStyle.valueOf(styleName)
            } catch (e: Exception) {
                CircleAnimationStyle.CYBER_SWEEP
            }
            val sizeDp = prefs.getInt("circle_size_dp", 34)
            _cutoutConfig.update {
                it.copy(
                    baseWidthDp = sizeDp,
                    baseHeightDp = sizeDp,
                    idleCircleSizeDp = sizeDp,
                    offsetX = prefs.getInt("port_x", 0),
                    offsetY = prefs.getInt("port_y", 10),
                    landscapeCutoutX = prefs.getInt("land_x", 14),
                    landscapeCutoutY = prefs.getInt("land_y", 0),
                    hasCustomPortraitPos = prefs.getBoolean("has_custom_port", false),
                    hasCustomLandscapePos = prefs.getBoolean("has_custom_land", false),
                    isHoldAndDragEnabled = prefs.getBoolean("hold_drag_enabled", true),
                    chargingDurationMs = prefs.getLong("charging_duration_ms", 6500L),
                    circleAnimationStyle = style
                )
            }

            val savedPackages = prefs.getStringSet("pinned_packages", null)
            if (savedPackages != null) {
                _pinnedShortcutPackages.value = savedPackages
            }
            refreshPinnedAppShortcuts(context)
        } catch (e: Exception) {
        }
    }

    fun saveCutoutConfigToPrefs(
        context: Context,
        newConfig: CutoutConfig,
        isCustomPort: Boolean? = null,
        isCustomLand: Boolean? = null
    ) {
        val updated = newConfig.copy(
            hasCustomPortraitPos = isCustomPort ?: newConfig.hasCustomPortraitPos,
            hasCustomLandscapePos = isCustomLand ?: newConfig.hasCustomLandscapePos
        )
        _cutoutConfig.value = updated
        commitCutoutConfigToDisk(context)
    }

    fun updateDraggedOffsetInMemory(dx: Int, dy: Int) {
        _cutoutConfig.update { current ->
            if (current.isLandscape) {
                val nextX = if (current.landscapeRotation == android.view.Surface.ROTATION_270) {
                    (current.landscapeCutoutX - dx).coerceIn(-200, 400)
                } else {
                    (current.landscapeCutoutX + dx).coerceIn(-200, 400)
                }
                val nextY = (current.landscapeCutoutY + dy).coerceIn(-300, 300)
                current.copy(
                    landscapeCutoutX = nextX,
                    landscapeCutoutY = nextY,
                    hasCustomLandscapePos = true
                )
            } else {
                current.copy(
                    offsetX = (current.offsetX + dx).coerceIn(-300, 300),
                    offsetY = (current.offsetY + dy).coerceIn(-20, 250),
                    hasCustomPortraitPos = true
                )
            }
        }
    }

    fun commitCutoutConfigToDisk(context: Context) {
        val updated = _cutoutConfig.value
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putInt("circle_size_dp", updated.idleCircleSizeDp)
                putInt("port_x", updated.offsetX)
                putInt("port_y", updated.offsetY)
                putInt("land_x", updated.landscapeCutoutX)
                putInt("land_y", updated.landscapeCutoutY)
                putBoolean("has_custom_port", updated.hasCustomPortraitPos)
                putBoolean("has_custom_land", updated.hasCustomLandscapePos)
                putBoolean("hold_drag_enabled", updated.isHoldAndDragEnabled)
                putLong("charging_duration_ms", updated.chargingDurationMs)
                putString("circle_style", updated.circleAnimationStyle.name)
                apply()
            }
        } catch (e: Exception) {
        }
    }

    fun hasCustomPortraitPosition(): Boolean = _cutoutConfig.value.hasCustomPortraitPos

    fun hasCustomLandscapePosition(): Boolean = _cutoutConfig.value.hasCustomLandscapePos

    fun updatePinnedShortcutsList(apps: List<TargetAppInfo>) {
        if (apps.isNotEmpty()) {
            _pinnedShortcuts.value = apps
        }
    }

    fun resetOffsetsToHardwareCutout(
        context: Context,
        defaultPortX: Int,
        defaultPortY: Int,
        defaultLandX: Int,
        defaultLandY: Int
    ) {
        val reset = _cutoutConfig.value.copy(
            offsetX = defaultPortX,
            offsetY = defaultPortY,
            landscapeCutoutX = defaultLandX,
            landscapeCutoutY = defaultLandY,
            hasCustomPortraitPos = false,
            hasCustomLandscapePos = false
        )
        saveCutoutConfigToPrefs(context, reset, isCustomPort = false, isCustomLand = false)
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
                .putStringSet("pinned_packages", current)
                .apply()
        } catch (e: Exception) {
        }
        refreshPinnedAppShortcuts(context)
    }

    fun refreshPinnedAppShortcuts(context: Context) {
        engineScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val selectedSet = _pinnedShortcutPackages.value
                val apps = mutableListOf<TargetAppInfo>()

                if (selectedSet.isNotEmpty()) {
                    for (pkg in selectedSet) {
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        } ?: continue
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        apps.add(
                            TargetAppInfo(
                                label = pm.getApplicationLabel(appInfo).toString(),
                                packageName = pkg,
                                icon = pm.getApplicationIcon(appInfo),
                                launchIntent = launchIntent
                            )
                        )
                    }
                } else {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolved = pm.queryIntentActivities(mainIntent, 0)
                    for (info in resolved) {
                        val pkg = info.activityInfo.packageName
                        if (pkg == context.packageName) continue
                        val launch = pm.getLaunchIntentForPackage(pkg)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        } ?: continue
                        apps.add(
                            TargetAppInfo(
                                label = info.loadLabel(pm).toString(),
                                packageName = pkg,
                                activityName = info.activityInfo.name,
                                icon = info.loadIcon(pm),
                                launchIntent = launch
                            )
                        )
                        if (apps.size >= 6) break
                    }
                }
                _pinnedShortcuts.value = apps
            } catch (e: Exception) {
            }
        }
    }

    fun submitPayload(payload: IslandPayload) {
        when (payload) {
            is IslandPayload.SystemAlert -> {
                if (payload.isOngoing) {
                    ongoingTasks[payload.id] = payload
                    isUserMinimized = false
                    recomputeSlots()
                } else {
                    showTransientAlert(payload)
                }
            }
            is IslandPayload.OriginAiSuggestion -> {
                showTransientOriginSuggestion(payload)
            }
            is IslandPayload.MediaActivity -> {
                val isNewTrack = lastKnownTrackTitle != payload.trackTitle
                lastKnownTrackTitle = payload.trackTitle
                ongoingTasks[payload.id] = payload
                if (isNewTrack) {
                    isUserMinimized = false
                }
                recomputeSlots()
            }
            is IslandPayload.Idle -> {
                ongoingTasks.clear()
                recomputeSlots()
            }
            else -> {
                isUserMinimized = false
                ongoingTasks[payload.id] = payload
                recomputeSlots()
            }
        }
    }

    fun removePayload(id: String) {
        ongoingTasks.remove(id)
        if (_primarySlot.value.id == id) {
            transientAlertJob?.cancel()
        }
        recomputeSlots()
    }

    private fun showTransientAlert(alert: IslandPayload.SystemAlert) {
        transientAlertJob?.cancel()
        isUserMinimized = false
        _primarySlot.value = alert
        _displayMode.value = IslandDisplayMode.COMPACT_SINGLE

        transientAlertJob = engineScope.launch {
            delay(alert.autoDismissMs)
            recomputeSlots()
        }
    }

    private fun showTransientOriginSuggestion(suggestion: IslandPayload.OriginAiSuggestion) {
        transientAlertJob?.cancel()
        isUserMinimized = false
        _primarySlot.value = suggestion
        _displayMode.value = IslandDisplayMode.COMPACT_SINGLE

        transientAlertJob = engineScope.launch {
            delay(7500L)
            recomputeSlots()
        }
    }

    private fun recomputeSlots() {
        val sorted = ongoingTasks.values.sortedByDescending { it.priority }
        when {
            sorted.isEmpty() -> {
                isUserMinimized = false
                _primarySlot.value = IslandPayload.Idle
                _secondarySlot.value = null
                if (_displayMode.value != IslandDisplayMode.ORIGIN_DRAG_WHEEL &&
                    _displayMode.value != IslandDisplayMode.QUICK_TOOLS
                ) {
                    _displayMode.value = IslandDisplayMode.IDLE
                }
            }
            isUserMinimized -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = sorted.getOrNull(1)
                _displayMode.value = IslandDisplayMode.IDLE
            }
            sorted.size == 1 -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = null
                if (_displayMode.value != IslandDisplayMode.EXPANDED_CARD &&
                    _displayMode.value != IslandDisplayMode.QUICK_TOOLS
                ) {
                    _displayMode.value = IslandDisplayMode.COMPACT_SPLIT
                }
            }
            else -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = sorted[1]
                if (_displayMode.value != IslandDisplayMode.EXPANDED_CARD &&
                    _displayMode.value != IslandDisplayMode.QUICK_TOOLS
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
            _primarySlot.value = currentSecondary
            _secondarySlot.value = currentPrimary
        } else {
            openQuickTools()
        }
    }

    fun expandIsland() {
        if (_primarySlot.value !is IslandPayload.Idle) {
            isUserMinimized = false
            _displayMode.value = IslandDisplayMode.EXPANDED_CARD
        } else {
            openQuickTools()
        }
    }

    fun onIdleCircleTapped() {
        if (ongoingTasks.isNotEmpty()) {
            isUserMinimized = false
            recomputeSlots()
        } else {
            openQuickTools()
        }
    }

    fun openQuickTools() {
        _displayMode.value = IslandDisplayMode.QUICK_TOOLS
    }

    fun collapseIsland() {
        recomputeSlots()
        if (ongoingTasks.isEmpty()) {
            _displayMode.value = IslandDisplayMode.IDLE
        } else if (!isUserMinimized) {
            _displayMode.value = IslandDisplayMode.COMPACT_SPLIT
        } else {
            _displayMode.value = IslandDisplayMode.IDLE
        }
    }

    fun minimizeToIdle() {
        transientAlertJob?.cancel()
        isUserMinimized = true
        _displayMode.value = IslandDisplayMode.IDLE
    }

    fun handleDismissSwipe() {
        when (_displayMode.value) {
            IslandDisplayMode.EXPANDED_CARD -> {
                _displayMode.value = if (ongoingTasks.isNotEmpty()) {
                    IslandDisplayMode.COMPACT_SPLIT
                } else {
                    IslandDisplayMode.IDLE
                }
            }
            IslandDisplayMode.QUICK_TOOLS,
            IslandDisplayMode.ORIGIN_DRAG_WHEEL,
            IslandDisplayMode.COMPACT_SINGLE,
            IslandDisplayMode.COMPACT_SPLIT -> {
                minimizeToIdle()
            }
            IslandDisplayMode.IDLE -> {}
        }
    }

    fun setDragWheelActive(active: Boolean, targetApps: List<TargetAppInfo> = emptyList()) {
        if (active) {
            _dragTargetApps.value = targetApps
            _displayMode.value = IslandDisplayMode.ORIGIN_DRAG_WHEEL
        } else {
            collapseIsland()
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

    fun updatePrivacyState(camera: Boolean? = null, mic: Boolean? = null) {
        updatePrivacyDots(camera, mic)
    }

    fun updateQuickTileState(
        torch: Boolean? = null,
        bluetooth: Boolean? = null,
        hotspot: Boolean? = null,
        autoRotate: Boolean? = null,
        ringerMode: Int? = null,
        volumeFraction: Float? = null,
        brightnessFraction: Float? = null,
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
                brightnessFraction = brightnessFraction ?: it.brightnessFraction,
                batteryPct = batteryPct ?: it.batteryPct
            )
        }
    }

    fun updateQuickTileStates(
        torch: Boolean? = null,
        bluetooth: Boolean? = null,
        hotspot: Boolean? = null,
        autoRotate: Boolean? = null
    ) {
        updateQuickTileState(torch = torch, bluetooth = bluetooth, hotspot = hotspot, autoRotate = autoRotate)
    }

    fun updateCutoutConfig(config: CutoutConfig) {
        _cutoutConfig.value = config
    }

    fun recordClipboard(text: String) {
        if (text.isBlank()) return
        _clipboardHistory.update { current ->
            (listOf(text.trim()) + current.filterNot { it == text.trim() }).take(8)
        }
    }

    fun addClipboardEntry(text: String) {
        recordClipboard(text)
    }

    fun addToStash(item: StashItem) {
        _stashItems.update { (listOf(item) + it).take(10) }
    }

    fun addStashItem(item: StashItem) {
        addToStash(item)
    }

    fun onScreenPowerStateChanged(isInteractive: Boolean) {
        if (!isInteractive) {
            burnInJob?.cancel()
        } else {
            startBurnInProtectionLoop()
        }
    }

    private fun startBurnInProtectionLoop() {
        burnInJob?.cancel()
        burnInJob = engineScope.launch {
            var angle = 0.0
            while (isActive) {
                delay(60_000L)
                angle += 0.6
                val dx = (cos(angle) * 2.0).toInt()
                val dy = (sin(angle) * 2.0).toInt()
                _burnInOffset.value = IntOffset(dx, dy)
            }
        }
    }
}