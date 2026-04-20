package com.naibaf7.openrower.ui.settings

import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.naibaf7.openrower.bluetooth.BleUartManager
import com.naibaf7.openrower.bluetooth.RowingDataParser
import com.naibaf7.openrower.bluetooth.UsbSerialManager
import com.naibaf7.openrower.db.RowingMachine
import com.naibaf7.openrower.db.RowingMachineRepository
import com.naibaf7.openrower.db.WorkoutRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("openrower", Context.MODE_PRIVATE)

    // ── Drag factor (auto-measured during workouts; display-only in settings) ─
    // Stored in 100-150 scale (e.g. 105). Migrate old raw values (< 1.0) on first load.
    private val _dragFactor = MutableLiveData(run {
        val raw = prefs.getFloat("drag_factor", 105f)
        if (raw < 1f) (raw * 1_000_000f).also { prefs.edit().putFloat("drag_factor", it).apply() } else raw
    })
    val dragFactor: LiveData<Float> = _dragFactor

    private val _distFactor = MutableLiveData(prefs.getFloat("dist_factor", 0.035f))
    val distFactor: LiveData<Float> = _distFactor

    // Flywheel moment of inertia J (kg·m²), used to scale the per-stroke drag factor
    // measurement. Concept2 Model D ≈ 0.100. DIY: 0.5 × mass(kg) × radius²(m²) for
    // a solid disc, or mass × radius² for a rim/fan wheel.
    private val _flywheelInertia = MutableLiveData(prefs.getFloat("flywheel_inertia", 0.100f))
    val flywheelInertia: LiveData<Float> = _flywheelInertia

    private val _useCalories = MutableLiveData(prefs.getBoolean("use_calories", false))
    val useCalories: LiveData<Boolean> = _useCalories

    private val _enablePulseLog = MutableLiveData(prefs.getBoolean("enable_pulse_log", false))
    val enablePulseLog: LiveData<Boolean> = _enablePulseLog

    private val _maxHr = MutableLiveData(prefs.getInt("max_hr", 190))
    val maxHr: LiveData<Int> = _maxHr

    // ── Rowing device (for c_dist calibration) ───────────────────────────────
    private val _rowingDeviceAddress = MutableLiveData(prefs.getString("rowing_device", "") ?: "")
    val rowingDeviceAddress: LiveData<String> = _rowingDeviceAddress

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val btAdapter by lazy {
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val scanHandler = Handler(Looper.getMainLooper())
    private val discovered = mutableMapOf<String, String>() // address → name

    private val _bleDevices = MutableLiveData<List<Pair<String, String>>>(emptyList())
    val bleDevices: LiveData<List<Pair<String, String>>> = _bleDevices

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = result.device.name?.takeIf { it.isNotBlank() }
                ?: discovered[address]
                ?: "Unknown (${address.takeLast(5)})"
            if (discovered[address] == name) return
            discovered[address] = name
            _bleDevices.postValue(discovered.entries.map { it.value to it.key })
        }
    }

    init { seedSavedDevices() }

    private fun seedSavedDevices() {
        discovered["USB_SERIAL"] = "USB Serial (OTG)"
        try {
            btAdapter?.bondedDevices?.forEach { dev ->
                discovered[dev.address] = dev.name ?: "Unknown"
            }
        } catch (_: SecurityException) {}
        val savedAddr = prefs.getString("rowing_device", "") ?: ""
        val savedName = prefs.getString("rowing_device_name", "") ?: ""
        if (savedAddr.isNotEmpty() && savedAddr != "USB_SERIAL") {
            discovered.putIfAbsent(savedAddr, savedName.ifEmpty { savedAddr.takeLast(5) })
        }
        _bleDevices.value = discovered.entries.map { it.value to it.key }
    }

    // ── c_dist calibration ────────────────────────────────────────────────────
    // User rows a known distance (measured by the original monitor).
    // The app accumulates total flywheel radians; when rowing stops for 10 s
    // (= original monitor has reached the target distance), it computes:
    //   c_dist = target_distance / total_radians
    //   df = 2.80 × c_dist³ × 1e6
    enum class CalibDistState { IDLE, CONNECTING, RUNNING, DONE, ERROR }

    private val _calibDistState = MutableLiveData(CalibDistState.IDLE)
    val calibDistState: LiveData<CalibDistState> = _calibDistState

    // Live total revolutions (total_radians / 2π) shown during calibration
    private val _calibDistRevolutions = MutableLiveData(0.0)
    val calibDistRevolutions: LiveData<Double> = _calibDistRevolutions

    // Countdown until calibration auto-finishes (seconds of silence)
    private val _calibDistCountdown = MutableLiveData(10)
    val calibDistCountdown: LiveData<Int> = _calibDistCountdown

    // Computed c_dist result (shown after DONE)
    private val _calibDistResult = MutableLiveData(0f)
    val calibDistResult: LiveData<Float> = _calibDistResult

    private var calibDistJob: Job? = null
    private var calibDistCountdownJob: Job? = null
    private var calibDistTargetMeters = 500f
    private var calibDistTotalRadians = 0.0
    private var calibDistLastPulseMs = 0L
    private var calibDistStartMs = 0L

    // Per-magnet tracking for deceleration samples → J computation
    private val calibDistPrevOmegaPerMagnet     = DoubleArray(3) { 0.0 }
    private val calibDistPrevTimestampPerMagnet = LongArray(3)   { 0L  }
    private var calibDistPulseCount = 0
    private val calibDistDfSamples  = mutableListOf<Double>()

    fun startDistCalibration(targetMeters: Float) {
        val address = _rowingDeviceAddress.value ?: ""
        if (address.isEmpty()) {
            _calibDistState.value = CalibDistState.ERROR
            return
        }
        calibDistTargetMeters = targetMeters
        calibDistTotalRadians = 0.0
        calibDistLastPulseMs  = 0L
        calibDistStartMs      = System.currentTimeMillis()
        calibDistPrevOmegaPerMagnet.fill(0.0)
        calibDistPrevTimestampPerMagnet.fill(0L)
        calibDistPulseCount = 0
        calibDistDfSamples.clear()

        _calibDistState.value = CalibDistState.CONNECTING
        _calibDistCountdown.value = 10
        _calibDistRevolutions.value = 0.0

        calibDistJob = viewModelScope.launch {
            try {
                val flow = if (address == "USB_SERIAL") {
                    UsbSerialManager(getApplication()).connect()
                } else {
                    val ble = btAdapter ?: run {
                        _calibDistState.postValue(CalibDistState.ERROR)
                        return@launch
                    }
                    BleUartManager(getApplication(), ble).connect(address)
                }
                flow.collect { msg ->
                    if (msg is RowingDataParser.Message.Pulse) processDistCalibPulse(msg)
                }
            } catch (_: Exception) {
                if (_calibDistState.value != CalibDistState.DONE) {
                    _calibDistState.postValue(CalibDistState.ERROR)
                }
            }
        }

        calibDistCountdownJob?.cancel()
        calibDistCountdownJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val now = System.currentTimeMillis()
                when (_calibDistState.value) {
                    CalibDistState.CONNECTING -> {
                        if (now - calibDistStartMs > 30_000) {
                            _calibDistState.postValue(CalibDistState.ERROR)
                            calibDistJob?.cancel()
                            break
                        }
                    }
                    CalibDistState.RUNNING -> {
                        val msSinceLast = now - calibDistLastPulseMs
                        val remaining = ((10_000 - msSinceLast) / 1000).coerceIn(0, 10).toInt()
                        _calibDistCountdown.postValue(remaining)
                        if (msSinceLast >= 10_000) {
                            finishDistCalibration()
                            break
                        }
                    }
                    else -> break
                }
            }
        }
    }

    private fun processDistCalibPulse(msg: RowingDataParser.Message.Pulse) {
        if (msg.intervalUs !in 2_000L..200_000L) return

        if (_calibDistState.value == CalibDistState.CONNECTING) {
            _calibDistState.postValue(CalibDistState.RUNNING)
        }
        calibDistLastPulseMs = System.currentTimeMillis()

        // Each pulse = one magnet passing = 2π/3 radians
        calibDistTotalRadians += 2.0 * PI / 3.0
        _calibDistRevolutions.postValue(calibDistTotalRadians / (2.0 * PI))

        // Collect per-magnet deceleration samples during recovery phases.
        // These are used to compute J at the end so the per-stroke auto-measurement
        // produces the correct (c_dist-derived) drag factor at this resistance level.
        val dt = msg.intervalUs / 1_000_000.0
        val omega = (2.0 * PI / 3.0) / dt
        val magnetIdx = calibDistPulseCount % 3
        val prevOmega = calibDistPrevOmegaPerMagnet[magnetIdx]
        val prevTs    = calibDistPrevTimestampPerMagnet[magnetIdx]

        if (prevOmega > 0.0) {
            val dtRev = if (msg.timestampUs > 0L && prevTs > 0L)
                (msg.timestampUs - prevTs) / 1_000_000.0
            else dt * 3.0
            if (dtRev > 0.0) {
                val alpha = (omega - prevOmega) / dtRev
                if (alpha < -5.0) {   // deceleration only (recovery coast-down)
                    val sample = (1.0 / omega - 1.0 / prevOmega) / dtRev
                    if (sample > 0.0) calibDistDfSamples.add(sample)
                }
            }
        }
        calibDistPrevOmegaPerMagnet[magnetIdx]     = omega
        calibDistPrevTimestampPerMagnet[magnetIdx] = msg.timestampUs
        calibDistPulseCount++
    }

    private fun finishDistCalibration() {
        calibDistJob?.cancel()
        if (calibDistTotalRadians > 0.0) {
            val cDist = (calibDistTargetMeters / calibDistTotalRadians).toFloat()
                .coerceIn(0.005f, 0.5f)
            _calibDistResult.postValue(cDist)
            setDistFactor(cDist)  // also derives and sets df_target

            // Compute J so the per-stroke auto-measurement produces df_target
            // at this resistance level.
            //   sample_median = k_actual / J_actual
            //   df_target     = J_correct × sample_median × 1e6
            //   → J_correct   = df_target / (sample_median × 1e6)
            if (calibDistDfSamples.size >= 5) {
                val sorted = calibDistDfSamples.sorted()
                val median = if (sorted.size % 2 == 0)
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                else sorted[sorted.size / 2]
                val dfTarget = (_dragFactor.value ?: 105f).toDouble()
                val j = (dfTarget / (median * 1e6)).toFloat().coerceIn(0.001f, 1.0f)
                setFlywheelInertia(j)
            }
        }
        _calibDistState.postValue(CalibDistState.DONE)
    }

    fun cancelDistCalibration() {
        calibDistJob?.cancel()
        calibDistCountdownJob?.cancel()
        _calibDistState.value = CalibDistState.IDLE
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setMaxHr(value: Int) {
        _maxHr.value = value
        prefs.edit().putInt("max_hr", value).apply()
    }

    internal fun setDragFactor(value: Float) {
        _dragFactor.value = value
        prefs.edit().putFloat("drag_factor", value).apply()
    }

    fun setDistFactor(value: Float) {
        _distFactor.value = value
        prefs.edit().putFloat("dist_factor", value).apply()
        // Keep drag factor consistent: df = 2.80 × c_dist³ × 1e6
        deriveDfFromDistFactor()
        // Persist to selected machine if one is active
        updateSelectedMachine()
    }

    fun setFlywheelInertia(value: Float) {
        _flywheelInertia.value = value
        prefs.edit().putFloat("flywheel_inertia", value).apply()
        updateSelectedMachine()
    }

    /**
     * Derive drag factor from the already-calibrated distance factor using the
     * standard on-water rowing physics relationship:
     *   P = k × ω³  and  v = c_dist × ω
     *   → P = (k / c_dist³) × v³
     * Standard single-sculler on-water coefficient: P = 2.80 × v³  (W·s³/m³)
     * → k = 2.80 × c_dist³   →   df = k × 1e6
     */
    private fun deriveDfFromDistFactor() {
        val cDist = _distFactor.value ?: 0.035f
        val df = (2.80 * cDist.toDouble().pow(3) * 1e6).toFloat().coerceIn(5f, 500f)
        setDragFactor(df)
    }

    fun setUseCalories(value: Boolean) {
        _useCalories.value = value
        prefs.edit().putBoolean("use_calories", value).apply()
    }

    fun setEnablePulseLog(value: Boolean) {
        _enablePulseLog.value = value
        prefs.edit().putBoolean("enable_pulse_log", value).apply()
    }

    // ── Device scan ───────────────────────────────────────────────────────────

    fun scanForDevice() {
        val scanner = btAdapter?.bluetoothLeScanner ?: return
        if (_isScanning.value == true) return

        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            _isScanning.value = false
            return
        }

        scanHandler.postDelayed({ stopScan() }, 10_000L)
    }

    fun stopScan() {
        if (_isScanning.value != true) return
        try {
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
        _isScanning.value = false
    }

    fun selectDevice(address: String) {
        val name = discovered[address] ?: address
        _rowingDeviceAddress.value = address
        prefs.edit()
            .putString("rowing_device", address)
            .putString("rowing_device_name", name)
            .apply()
    }

    // ── Machine profiles ──────────────────────────────────────────────────────

    private val machineRepo = RowingMachineRepository(application)
    val machines: LiveData<List<RowingMachine>> = machineRepo.allMachines

    private val _selectedMachineId = MutableLiveData(prefs.getInt("selected_machine_id", 0))
    val selectedMachineId: LiveData<Int> = _selectedMachineId

    fun addMachine(name: String) {
        viewModelScope.launch {
            val id = machineRepo.insert(
                RowingMachine(
                    name           = name,
                    distFactor     = _distFactor.value ?: 0.035f,
                    dragFactor     = _dragFactor.value ?: 105f,
                    flywheelInertia = _flywheelInertia.value ?: 0.100f
                )
            )
            selectMachineById(id.toInt())
        }
    }

    fun renameMachine(machine: RowingMachine, newName: String) {
        viewModelScope.launch { machineRepo.update(machine.copy(name = newName)) }
    }

    fun deleteMachine(machine: RowingMachine) {
        viewModelScope.launch {
            machineRepo.delete(machine)
            if (_selectedMachineId.value == machine.id) {
                selectMachineById(0)
            }
        }
    }

    fun selectMachineById(id: Int) {
        _selectedMachineId.value = id
        prefs.edit().putInt("selected_machine_id", id).apply()
        if (id == 0) return
        viewModelScope.launch {
            val m = machineRepo.getById(id) ?: return@launch
            // Load machine values into prefs and LiveData
            _distFactor.postValue(m.distFactor)
            _flywheelInertia.postValue(m.flywheelInertia)
            _dragFactor.postValue(m.dragFactor)
            prefs.edit()
                .putFloat("dist_factor", m.distFactor)
                .putFloat("flywheel_inertia", m.flywheelInertia)
                .putFloat("drag_factor", m.dragFactor)
                .apply()
        }
    }

    /** Called after editing calibration values to keep the selected machine in sync. */
    private fun updateSelectedMachine() {
        val id = _selectedMachineId.value ?: 0
        if (id == 0) return
        viewModelScope.launch {
            val m = machineRepo.getById(id) ?: return@launch
            machineRepo.update(
                m.copy(
                    distFactor      = _distFactor.value ?: m.distFactor,
                    dragFactor      = _dragFactor.value ?: m.dragFactor,
                    flywheelInertia = _flywheelInertia.value ?: m.flywheelInertia
                )
            )
        }
    }

    // ── Training log management ───────────────────────────────────────────────

    private val workoutRepo = WorkoutRepository(application)

    /** Deletes all workouts whose date is strictly before [beforeMs]. */
    fun clearLogsOlderThan(beforeMs: Long) {
        viewModelScope.launch { workoutRepo.deleteOlderThan(beforeMs) }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        cancelDistCalibration()
    }
}
