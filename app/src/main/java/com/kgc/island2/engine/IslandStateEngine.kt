package com.kgc.island2.engine

import androidx.compose.ui.unit.IntOffset
import com.kgc.island2.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

object IslandStateEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _primarySlot = MutableStateFlow<IslandPayload>(IslandPayload.Idle)
    val primarySlot: StateFlow<IslandPayload> = _primarySlot.asStateFlow()

    private val _secondarySlot = MutableStateFlow<IslandPayload?>(null)
    val secondarySlot: StateFlow<IslandPayload?> = _secondarySlot.asStateFlow()

    private val _displayMode = MutableStateFlow(IslandDisplayMode.IDLE)
    val displayMode: StateFlow<IslandDisplayMode> = _displayMode.asStateFlow()

    private val _privacyState = MutableStateFlow(PrivacyIndicatorState())
    val privacyState: StateFlow<PrivacyIndicatorState> = _privacyState.asStateFlow()

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

    init {
        startBurnInProtectionLoop()
    }

    fun submitPayload(payload: IslandPayload) {
        engineScope.launch {
            when (payload) {
                is IslandPayload.SystemAlert,
                is IslandPayload.OriginAiSuggestion -> {
                    val previousPrimary = _primarySlot.value
                    _primarySlot.value = payload
                    _displayMode.value = IslandDisplayMode.COMPACT_SINGLE

                    autoDismissJob?.cancel()
                    val timeout = if (payload is IslandPayload.SystemAlert) payload.autoDismissMs else 5000L
                    autoDismissJob = launch {
                        delay(timeout)
                        restoreOngoingState(previousPrimary)
                    }
                }

                is IslandPayload.CommunicationActivity -> {
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

                is IslandPayload.MediaActivity,
                is IslandPayload.LiveProgressActivity -> {
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
            _displayMode.value = IslandDisplayMode.COMPACT_SINGLE
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
                _displayMode.value = IslandDisplayMode.IDLE
            }
            sorted.size == 1 -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = null
                if (_displayMode.value != IslandDisplayMode.EXPANDED_CARD) {
                    _displayMode.value = IslandDisplayMode.COMPACT_SINGLE
                }
            }
            else -> {
                _primarySlot.value = sorted[0]
                _secondarySlot.value = sorted[1]
                if (_displayMode.value != IslandDisplayMode.EXPANDED_CARD) {
                    _displayMode.value = IslandDisplayMode.COMPACT_SPLIT
                }
            }
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

    fun expandIsland() {
        if (_primarySlot.value !is IslandPayload.Idle) {
            _displayMode.value = IslandDisplayMode.EXPANDED_CARD
        } else {
            _displayMode.value = IslandDisplayMode.QUICK_TOOLS
        }
    }

    fun collapseIsland() {
        recalculateSlots()
    }

    fun minimizeToIdle() {
        _displayMode.value = IslandDisplayMode.IDLE
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

    fun addToStash(item: StashItem) {
        _stashItems.update { (listOf(item) + it).take(10) }
    }

    fun recordClipboard(text: String) {
        if (text.isBlank()) return
        _clipboardHistory.update { current ->
            (listOf(text.trim()) + current.filterNot { it == text.trim() }).take(8)
        }
    }

    fun updateCutoutConfig(config: CutoutConfig) {
        _cutoutConfig.value = config
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