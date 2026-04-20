package com.naibaf7.openrower.ui.workout

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.naibaf7.openrower.bluetooth.BleUartManager
import com.naibaf7.openrower.bluetooth.UsbSerialManager
import com.naibaf7.openrower.debug.PulseLogger
import com.naibaf7.openrower.bluetooth.FakeDataSource
import com.naibaf7.openrower.bluetooth.HeartRateManager
import com.naibaf7.openrower.bluetooth.RowingDataParser
import com.naibaf7.openrower.db.SplitEntity
import com.naibaf7.openrower.db.WorkoutEntity
import com.naibaf7.openrower.db.WorkoutRepository
import com.naibaf7.openrower.engine.RowingEngine
import com.naibaf7.openrower.model.WorkoutConfig
import com.naibaf7.openrower.model.WorkoutState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var pulseLogger: PulseLogger? = null

    private var rowingJob: Job? = null
    private var hrJob: Job? = null
    private var tickerJob: Job? = null

    private val btAdapter: BluetoothAdapter? by lazy {
        val mgr = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private val repository = WorkoutRepository(application)

    // Pane visibility
    private val _showForceCurve = MutableLiveData(true)
    val showForceCurve: LiveData<Boolean> = _showForceCurve

    private val _showBarChart = MutableLiveData(true)
    val showBarChart: LiveData<Boolean> = _showBarChart

    private val _showMetrics = MutableLiveData(true)
    val showMetrics: LiveData<Boolean> = _showMetrics

    /** Max heart rate read from settings (used by BarChartView for zone colouring). */
    val maxHr: Int by lazy {
        getApplication<Application>()
            .getSharedPreferences("openrower", Context.MODE_PRIVATE)
            .getInt("max_hr", 190)
    }

    // True once the workout has been saved to the DB (avoid double-saves)
    private var workoutSaved = false

    fun startWorkout(cfg: WorkoutConfig) {
        config = cfg
        val loggingEnabled = getApplication<Application>()
            .getSharedPreferences("openrower", Context.MODE_PRIVATE)
            .getBoolean("enable_pulse_log", false)
        pulseLogger = if (loggingEnabled) PulseLogger(getApplication()) else null
        engine = RowingEngine(cfg, pulseLogger).also { e ->
            val prefs = getApplication<Application>()
                .getSharedPreferences("openrower", Context.MODE_PRIVATE)
            // drag_factor stored in 100-150 scale; divide by 1e6 for physics
            val dfRaw = prefs.getFloat("drag_factor", 105f)
            val dfMigrated = if (dfRaw < 1f) dfRaw * 1_000_000f else dfRaw  // migrate old scale
            e.dragFactor       = dfMigrated / 1_000_000.0
            e.distFactor       = prefs.getFloat("dist_factor", 0.035f).toDouble()
            e.flywheelInertia  = prefs.getFloat("flywheel_inertia", 0.1f).toDouble()
        }
        engine.start()

        when {
            cfg.useTestMode -> startFakeMode()
            cfg.rowingDeviceAddress == "USB_SERIAL" -> startUsbSerialMode()
            else -> startBtMode()
        }
        startHrConnection()
        startTicker()
    }

    private fun startBtMode() {
        val adapter = btAdapter ?: run {
            _error.value = "Bluetooth not available"
            return
        }
        val address = config.rowingDeviceAddress
        if (address.isEmpty()) {
            _error.value = "No rowing device selected"
            return
        }
        val mgr = BleUartManager(getApplication(), adapter)
        rowingJob = viewModelScope.launch {
            try {
                mgr.connect(address).collect { msg ->
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
        val mgr = UsbSerialManager(getApplication())
        rowingJob = viewModelScope.launch {
            try {
                mgr.connect(onStatus = { msg -> _connectStatus.postValue(msg) }).collect { msg ->
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
        _isConnected.value = true   // test mode is always "connected"
        rowingJob = viewModelScope.launch {
            FakeDataSource.rowingMessages().collect { msg ->
                handleRowingMessage(msg)
            }
        }
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val newState = engine.tick()
                _state.postValue(newState)
                // Auto-save when workout finishes naturally
                if (newState.isFinished) saveWorkout(newState)
            }
        }
    }

    private fun startHrConnection() {
        val address = if (::config.isInitialized) config.hrDeviceAddress else ""
        val adapter = btAdapter ?: return
        if (address.isEmpty() && !config.useTestMode) return

        hrJob = viewModelScope.launch {
            if (config.useTestMode) {
                FakeDataSource.heartRateMessages().collect { bpm ->
                    engine.updateHeartRate(bpm)
                }
            } else {
                try {
                    val hrMgr = HeartRateManager(getApplication(), adapter)
                    hrMgr.connect(address).collect { bpm ->
                        engine.updateHeartRate(bpm)
                    }
                } catch (_: Exception) {
                    // HR is optional – ignore connection failures silently
                }
            }
        }
    }

    private fun handleRowingMessage(msg: RowingDataParser.Message) {
        when (msg) {
            is RowingDataParser.Message.Pulse -> {
                val newState = engine.processPulse(msg.intervalUs, msg.timestampUs)
                _state.postValue(newState)
                // Auto-save when workout finishes on a pulse boundary
                if (newState.isFinished) saveWorkout(newState)
            }
            is RowingDataParser.Message.Heartbeat -> {
                // Connection alive – no data to process
            }
        }
    }

    fun toggleForceCurve() { _showForceCurve.value = _showForceCurve.value?.not() }
    fun toggleBarChart()   { _showBarChart.value   = _showBarChart.value?.not() }
    fun toggleMetrics()    { _showMetrics.value     = _showMetrics.value?.not() }

    fun endWorkout() {
        // Save current state before stopping (handles "End" button and back navigation)
        _state.value?.let { saveWorkout(it) }
        rowingJob?.cancel()
        hrJob?.cancel()
        tickerJob?.cancel()
        pulseLogger?.close()
        pulseLogger = null
    }

    private fun saveWorkout(state: WorkoutState) {
        if (workoutSaved) return
        // Only save if the workout produced any meaningful data
        if (state.elapsedSeconds < 5.0 && state.elapsedMeters < 10.0) return
        workoutSaved = true

        val strokeHistory = state.strokeHistory
        val avgHr = strokeHistory.mapNotNull { it.heartRateBpm }
            .takeIf { it.isNotEmpty() }?.average()?.toInt()

        val workoutEntity = WorkoutEntity(
            dateMs            = System.currentTimeMillis(),
            modeName          = if (::config.isInitialized) config.mode.name else "FREE",
            totalDurationSec  = state.elapsedSeconds,
            totalMeters       = state.elapsedMeters,
            avgPowerWatts     = state.avgPowerWatts,
            totalCaloriesKcal = state.totalCaloriesKcal,
            avgHeartRate      = avgHr,
            strokeCount       = state.strokeCount
        )

        val splitEntities = state.splitHistory.map { split ->
            SplitEntity(
                workoutId      = 0L,  // filled in by insertWorkoutWithSplits
                splitNumber    = split.splitNumber,
                durationSec    = split.elapsedSeconds,
                distanceMeters = split.distanceMeters,
                avgPowerWatts  = split.avgPowerWatts,
                avgHeartRate   = split.avgHeartRate,
                avgStrokeRate  = split.avgStrokeRate
            )
        }

        viewModelScope.launch {
            repository.save(workoutEntity, splitEntities)
        }
    }

    /** Path of the current log file, for display to the user. */
    val logFilePath: String? get() = if (::engine.isInitialized) engine.logger?.filePath else null

    override fun onCleared() {
        super.onCleared()
        endWorkout()
        // Persist the latest auto-measured drag factor so Settings can display it.
        if (::engine.isInitialized && engine.latestMeasuredDf > 0.0) {
            getApplication<Application>()
                .getSharedPreferences("openrower", Context.MODE_PRIVATE)
                .edit()
                .putFloat("drag_factor", engine.latestMeasuredDf.toFloat())
                .apply()
        }
    }
}
