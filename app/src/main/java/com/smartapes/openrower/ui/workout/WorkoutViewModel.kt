package com.smartapes.openrower.ui.workout

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.smartapes.openrower.audio.WorkoutMusicPlayer
import com.smartapes.openrower.bluetooth.SensorLink
import com.smartapes.openrower.debug.PulseLogger
import com.smartapes.openrower.bluetooth.FakeDataSource
import com.smartapes.openrower.bluetooth.HeartRateManager
import com.smartapes.openrower.bluetooth.RowingDataParser
import com.smartapes.openrower.db.CustomWorkoutRepository
import com.smartapes.openrower.db.MachineProfiles
import com.smartapes.openrower.db.SplitEntity
import com.smartapes.openrower.db.WorkoutEntity
import com.smartapes.openrower.db.WorkoutRepository
import com.smartapes.openrower.engine.CustomWorkoutController
import com.smartapes.openrower.engine.RowingEngine
import com.smartapes.openrower.model.CustomWorkout
import com.smartapes.openrower.model.WorkoutConfig
import com.smartapes.openrower.model.WorkoutMode
import com.smartapes.openrower.model.SensorMode
import com.smartapes.openrower.model.WorkoutState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableLiveData(WorkoutState())
    val state: LiveData<WorkoutState> = _state

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _connectStatus = MutableLiveData<String>()
    val connectStatus: LiveData<String> = _connectStatus

    private lateinit var engine: RowingEngine
    private lateinit var config: WorkoutConfig
    private var sensorMode: SensorMode = SensorMode.DEFAULT
    private var lastBoardState: String? = null
    private var pulseLogger: PulseLogger? = null

    private var rowingJob: Job? = null
    private var hrJob: Job? = null
    private var tickerJob: Job? = null

    private val btAdapter: BluetoothAdapter? by lazy {
        val mgr = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private val repository        = WorkoutRepository(application)
    private val customWorkoutRepo = CustomWorkoutRepository(application)

    // ── Custom workout ─────────────────────────────────────────────────────
    private var customController: CustomWorkoutController? = null
    private var customWorkout: CustomWorkout? = null
    private var lastStateMeters    = 0.0
    private var lastStrokeCount    = 0
    private var pendingNewMeters   = 0.0   // meters accumulated from pulses, drained each tick

    // ── Audio ──────────────────────────────────────────────────────────────
    private val musicPlayer = WorkoutMusicPlayer()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ── Pane visibility ────────────────────────────────────────────────────
    private val _showForceCurve = MutableLiveData(true)
    val showForceCurve: LiveData<Boolean> = _showForceCurve

    private val _showBarChart = MutableLiveData(true)
    val showBarChart: LiveData<Boolean> = _showBarChart

    private val _showMetrics = MutableLiveData(true)
    val showMetrics: LiveData<Boolean> = _showMetrics

    val maxHr: Int by lazy {
        getApplication<Application>()
            .getSharedPreferences("openrower", Context.MODE_PRIVATE)
            .getInt("max_hr", 190)
    }

    private var workoutSaved   = false
    private var workoutStarted = false

    private val _savedWorkoutId = MutableLiveData<Long>()
    val savedWorkoutId: LiveData<Long> = _savedWorkoutId

    // ── Startup ────────────────────────────────────────────────────────────

    fun startWorkout(cfg: WorkoutConfig) {
        if (workoutStarted) return   // guard against duplicate onViewCreated calls
        workoutStarted = true
        config = cfg
        val prefs = getApplication<Application>()
            .getSharedPreferences("openrower", Context.MODE_PRIVATE)

        val loggingEnabled = prefs.getBoolean("enable_pulse_log", false)
        pulseLogger = if (loggingEnabled) PulseLogger(getApplication()) else null

        engine = RowingEngine(cfg, pulseLogger).also { e ->
            val dfRaw = prefs.getFloat("drag_factor", 105f)
            val dfMigrated = if (dfRaw < 1f) dfRaw * 1_000_000f else dfRaw
            e.dragFactor            = dfMigrated / 1_000_000.0
            e.distFactor            = prefs.getFloat("dist_factor", 0.035f).toDouble()
            e.flywheelInertia       = prefs.getFloat("flywheel_inertia", 0.1f).toDouble()
            e.pulsesPerRevolution   = prefs.getInt("pulses_per_revolution", 3)
        }
        sensorMode = MachineProfiles.sensorMode(prefs)
        engine.start()

        // Custom workout setup
        if (cfg.mode == WorkoutMode.CUSTOM) {
            cfg.customWorkoutId?.let { id ->
                viewModelScope.launch {
                    val w = customWorkoutRepo.load(id) ?: return@launch
                    customWorkout = w
                    val ctrl = CustomWorkoutController(w)
                    setupControllerCallbacks(ctrl)
                    customController = ctrl
                    engine.pauseStats = w.segments.firstOrNull()?.type?.name == "RECOVERY"
                    // Start music for first segment
                    w.segments.firstOrNull()?.let { musicPlayer.playSegment(it) }
                }
            }
        }

        // TTS
        if (prefs.getBoolean("tts_enabled", false)) {
            val langTag = prefs.getString("tts_language", "en") ?: "en"
            tts = TextToSpeech(getApplication()) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.language = Locale.forLanguageTag(langTag)
                }
            }
        }

        when {
            cfg.useTestMode                                  -> startFakeMode()
            cfg.rowingDeviceAddress == SensorLink.USB_ADDRESS -> startUsbSerialMode()
            else                                             -> startBtMode()
        }
        startHrConnection()
        startTicker()
    }

    private fun setupControllerCallbacks(ctrl: CustomWorkoutController) {
        ctrl.onSegmentTransition = transition@{ from, to ->
            val segs    = customWorkout?.segments ?: return@transition
            val prevSeg = segs.getOrNull(from)
            val nextSeg = segs.getOrNull(to) ?: return@transition

            // Flush completed segment as split; recovery segments are not recorded
            engine.flushCustomSplit(record = prevSeg?.type?.name != "RECOVERY")
            // Suppress distance/power/calorie accumulation during recovery
            engine.pauseStats = nextSeg.type.name == "RECOVERY"

            // Coming out of a recovery segment: pause recovery music position
            if (prevSeg?.type?.name == "RECOVERY") musicPlayer.pauseRecovery()
            musicPlayer.playSegment(nextSeg)
        }

        ctrl.onCountdown = { seconds ->
            if (ttsReady) {
                val text = if (seconds == 0) "Go!" else seconds.toString()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "countdown_$seconds")
            }
            // State is posted by the normal advance→merge→post cycle; no extra post needed.
        }

        ctrl.onFinished = {
            val lastSeg = customWorkout?.segments?.lastOrNull()
            engine.flushCustomSplit(record = lastSeg?.type?.name != "RECOVERY")
        }
    }

    // ── Connection modes ───────────────────────────────────────────────────

    private fun startBtMode() {
        val adapter = btAdapter ?: run { _error.value = "Bluetooth not available"; return }
        val address = config.rowingDeviceAddress
        if (address.isEmpty()) { _error.value = "No rowing device selected"; return }
        val link = SensorLink(getApplication(), adapter)
        rowingJob = viewModelScope.launch {
            try {
                link.connect(address, sensorMode).collect { msg ->
                    if (_isConnected.value != true) _isConnected.postValue(true)
                    handleRowingMessage(msg)
                }
            } catch (e: Exception) {
                _error.value = "Connection lost: ${e.message}"
                _isConnected.postValue(false)
            }
        }
    }

    private fun startUsbSerialMode() {
        val link = SensorLink(getApplication(), btAdapter)
        rowingJob = viewModelScope.launch {
            try {
                link.connect(SensorLink.USB_ADDRESS, sensorMode,
                    onStatus = { msg -> _connectStatus.postValue(msg) }).collect { msg ->
                    if (_isConnected.value != true) _isConnected.postValue(true)
                    handleRowingMessage(msg)
                }
            } catch (e: Exception) {
                _error.postValue("USB ${e::class.simpleName}: ${e.message}")
                _isConnected.postValue(false)
            }
        }
    }

    private fun startFakeMode() {
        _isConnected.value = true
        rowingJob = viewModelScope.launch {
            FakeDataSource.rowingMessages().collect { msg -> handleRowingMessage(msg) }
        }
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val engineState = engine.tick()
                // Drain accumulated meters from pulses; controller advances only here (once/s)
                val meters = pendingNewMeters.also { pendingNewMeters = 0.0 }
                advanceCustomController(engineState, 1.0, meters)
                // Re-read after advancing: controller callbacks (onFinished, onSegmentTransition)
                // may have called flushCustomSplit, which mutates splitHistory in the engine.
                val merged = mergeCustomState(engine.currentState())
                _state.postValue(merged)
                if (merged.isFinished) { saveWorkout(merged); break }
            }
        }
    }

    private fun startHrConnection() {
        val address = if (::config.isInitialized) config.hrDeviceAddress else ""
        val adapter = btAdapter ?: return
        if (address.isEmpty() && !config.useTestMode) return
        hrJob = viewModelScope.launch {
            if (config.useTestMode) {
                FakeDataSource.heartRateMessages().collect { bpm -> engine.updateHeartRate(bpm) }
            } else {
                try {
                    HeartRateManager(getApplication(), adapter).connect(address).collect { bpm ->
                        engine.updateHeartRate(bpm)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleRowingMessage(msg: RowingDataParser.Message) {
        when (msg) {
            is RowingDataParser.Message.Pulse -> {
                val engineState = engine.processPulse(msg.intervalUs, msg.timestampUs)
                // Accumulate meters for the ticker; do NOT advance the controller here —
                // advancing from every pulse would double-count time vs. the 1-s ticker.
                pendingNewMeters += engineState.elapsedMeters - lastStateMeters
                lastStateMeters   = engineState.elapsedMeters
                val merged = mergeCustomState(engineState)
                _state.postValue(merged)
                if (merged.isFinished) saveWorkout(merged)
            }
            is RowingDataParser.Message.Heartbeat -> {}
            is RowingDataParser.Message.Status -> handleBoardStatus(msg)
            is RowingDataParser.Message.Info,
            is RowingDataParser.Message.DetectResult -> {}
        }
    }

    /** Tell the rower when the sensor board had to switch its sensor supply off (once per change). */
    private fun handleBoardStatus(s: RowingDataParser.Message.Status) {
        if (s.state == lastBoardState) return
        val previous = lastBoardState
        lastBoardState = s.state
        val volts = "%.1f V".format(s.ringMv / 1000.0)
        val notice = when {
            s.isHallMode && s.state == "SHORT" ->
                "Sensor supply switched off: the jack's ring contact is shorted ($volts). " +
                "A Concept2 Model C mono plug does this — pick a Concept2 profile, or check the sensor cable."
            s.isHallMode && s.state == "GENERATOR" ->
                "Concept2 generator detected on the sensor cable ($volts) — sensor supply kept off. " +
                "Select a Concept2 Model D/E profile."
            s.isHallMode && s.state == "OK" && previous != null && previous != "OK" ->
                "Sensor supply is on again."
            else -> null
        }
        notice?.let { _error.postValue(it) }
    }

    private fun advanceCustomController(engineState: WorkoutState, dtSec: Double, newMeters: Double = 0.0) {
        val ctrl = customController ?: return
        ctrl.advance(dtSec, newMeters, engineState.strokeCount)
        lastStrokeCount = engineState.strokeCount
    }

    /** Merges engine WorkoutState with current CustomWorkoutController state. */
    private fun mergeCustomState(engineState: WorkoutState): WorkoutState {
        val ctrl = customController ?: return engineState
        val cs   = ctrl.getState()

        // Check if custom workout is done
        val isFinished = engineState.isFinished || cs.isFinished

        return engineState.copy(
            isFinished                   = isFinished,
            customSegmentIndex           = cs.segmentIndex,
            customTotalSegments          = cs.totalSegments,
            customSegmentType            = cs.segmentType,
            customSegmentRemainingSeconds = cs.segmentRemainingSeconds,
            customCountdownSeconds       = cs.countdownSeconds,
            customTargetStrokeRate       = cs.targetStrokeRate,
            customIsWaitingForPull       = cs.isWaitingForPull
        )
    }

    // ── Controls ───────────────────────────────────────────────────────────

    fun toggleForceCurve() { _showForceCurve.value = _showForceCurve.value?.not() }
    fun toggleBarChart()   { _showBarChart.value   = _showBarChart.value?.not() }
    fun toggleMetrics()    { _showMetrics.value     = _showMetrics.value?.not() }

    fun endWorkout() {
        _state.value?.let { saveWorkout(it) }
        rowingJob?.cancel()
        hrJob?.cancel()
        tickerJob?.cancel()
        pulseLogger?.close()
        pulseLogger = null
        musicPlayer.stop()
        tts?.shutdown()
        tts = null
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private fun saveWorkout(state: WorkoutState) {
        if (workoutSaved) return
        if (state.elapsedSeconds < 5.0 && state.elapsedMeters < 10.0) {
            _savedWorkoutId.postValue(0L)
            return
        }
        workoutSaved = true

        val strokeHistory = state.strokeHistory
        val avgHr = strokeHistory.mapNotNull { it.heartRateBpm }
            .takeIf { it.isNotEmpty() }?.average()?.toInt()

        val modeName = if (::config.isInitialized) {
            if (config.mode == WorkoutMode.CUSTOM && config.customWorkoutName.isNotBlank())
                "CUSTOM:${config.customWorkoutName}"
            else config.mode.name
        } else "FREE"

        val workoutEntity = WorkoutEntity(
            dateMs            = System.currentTimeMillis(),
            modeName          = modeName,
            totalDurationSec  = state.elapsedSeconds,
            totalMeters       = state.elapsedMeters,
            avgPowerWatts     = state.avgPowerWatts,
            totalCaloriesKcal = state.totalCaloriesKcal,
            avgHeartRate      = avgHr,
            strokeCount       = state.strokeCount
        )

        val splitEntities = state.splitHistory.map { split ->
            SplitEntity(
                workoutId      = 0L,
                splitNumber    = split.splitNumber,
                durationSec    = split.elapsedSeconds,
                distanceMeters = split.distanceMeters,
                avgPowerWatts  = split.avgPowerWatts,
                avgHeartRate   = split.avgHeartRate,
                avgStrokeRate  = split.avgStrokeRate
            )
        }

        viewModelScope.launch {
            val id = repository.save(workoutEntity, splitEntities)
            _savedWorkoutId.postValue(id)
        }
    }

    val logFilePath: String? get() = if (::engine.isInitialized) engine.logger?.filePath else null

    override fun onCleared() {
        super.onCleared()
        endWorkout()
        if (::engine.isInitialized && engine.latestMeasuredDf > 0.0) {
            getApplication<Application>()
                .getSharedPreferences("openrower", Context.MODE_PRIVATE)
                .edit()
                .putFloat("drag_factor", engine.latestMeasuredDf.toFloat())
                .apply()
        }
    }
}
