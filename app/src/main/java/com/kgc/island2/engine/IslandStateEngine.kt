package com.kgc.island2.engine

import androidx.compose.ui.unit.IntOffset
import com.kgc.island2.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

object IslandStateEngine {

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

    private val ongoingTasks = ConcurrentHashMap<String, IslandPayload>()
    private val notificationBurstTracker = ConcurrentHashMap<String, MutableList<Long>>()
    private var autoDismissJob: Job? = null
    private var burnInJob: Job? = null
    private var isUserMinimized = false
    private var lastTrackTitle: String? = null

    init {
        startBurnInProtectionLoop()
    }

    fun submitPayload(payload: IslandPayload) {
        engineScope.launch {
            when (payload) {
                is IslandPayload.SystemAlert,
                is IslandPayload.OriginAiSuggestion -> {
                    // Feature 4: Persistent Hotspot (isOngoing = true) stays in ongoingTasks until turned off!
                    if (payload.isOngoing) {
                        val isNew = !ongoingTasks.containsKey(payload.id)
                        if (isNew) isUserMinimized = false
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
                    // Feature 2: Only un-minimize when a new song starts, so progress ticks don't force it back open
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
        ongoingTasks.remove(id)
        if (id == "media_session") lastTrackTitle = null
        if (ongoingTasks.isEmpty()) isUserMinimized = false
        recalculateSlots()
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

    /**
     * Feature 2 & 5: Swipe Up / Dismiss Gesture
     * - In Horizontal (Landscape) mode: Any swipe up while in use immediately sends Kgc Island to the camera punch-hole!
     * - In Portrait mode: Collapses Expanded Card -> Compact Pill on 1st swipe up, and Compact Pill -> Camera Ring on 2nd swipe up.
     */
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
        brightnessFraction: Float? = null
    ) {
        _quickTileStates.update {
            it.copy(
                isTorchOn = torch ?: it.isTorchOn,
                isBluetoothOn = bluetooth ?: it.isBluetoothOn,
                isHotspotOn = hotspot ?: it.isHotspotOn,
                isAutoRotateOn = autoRotate ?: it.isAutoRotateOn,
                ringerMode = ringerMode ?: it.ringerMode,
                volumeFraction = volumeFraction ?: it.volumeFraction,
                brightnessFraction = brightnessFraction ?: it.brightnessFraction
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
        brightnessFraction: Float? = null
    ) = updateQuickTileState(torch, bluetooth, hotspot, autoRotate, ringerMode, volumeFraction, brightnessFraction)

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