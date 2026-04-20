package com.naibaf7.openrower.ui.setup

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
import kotlinx.coroutines.launch
import com.naibaf7.openrower.db.RowingMachine
import com.naibaf7.openrower.db.RowingMachineRepository
import com.naibaf7.openrower.model.EnergyUnit
import com.naibaf7.openrower.model.WorkoutConfig
import com.naibaf7.openrower.model.WorkoutMode

class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("openrower", Context.MODE_PRIVATE)

    // ── Workout config fields ──────────────────────────────────────────────
    private val _mode = MutableLiveData(WorkoutMode.FREE)
    val mode: LiveData<WorkoutMode> = _mode

    private val _targetDistance = MutableLiveData(2000)
    val targetDistance: LiveData<Int> = _targetDistance

    private val _targetMinutes = MutableLiveData(20)
    val targetMinutes: LiveData<Int> = _targetMinutes

    private val _targetSeconds = MutableLiveData(0)
    val targetSeconds: LiveData<Int> = _targetSeconds

    private val _splitMeters = MutableLiveData(500)
    val splitMeters: LiveData<Int> = _splitMeters

    private val _splitSeconds = MutableLiveData(60)
    val splitSeconds: LiveData<Int> = _splitSeconds

    private val _windowSeconds = MutableLiveData(300)
    val windowSeconds: LiveData<Int> = _windowSeconds

    private val _useTestMode = MutableLiveData(prefs.getBoolean("test_mode", false))
    val useTestMode: LiveData<Boolean> = _useTestMode

    // ── Device lists ──────────────────────────────────────────────────────
    // Single unified BLE scan — both spinners observe the same list.
    // Entry: Pair(displayName, macAddress)
    private val _bleDevices = MutableLiveData<List<Pair<String, String>>>(emptyList())
    val bleDevices: LiveData<List<Pair<String, String>>> = _bleDevices

    // Saved selections (address persisted to prefs)
    private val _selectedRowingDevice = MutableLiveData(prefs.getString("rowing_device", "") ?: "")
    val selectedRowingDevice: LiveData<String> = _selectedRowingDevice

    private val _selectedHrDevice = MutableLiveData(prefs.getString("hr_device", "") ?: "")
    val selectedHrDevice: LiveData<String> = _selectedHrDevice

    // BLE scan state
    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val btAdapter by lazy {
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_DURATION_MS = 10_000L

    // All discovered devices (address → display name), seeded with saved + bonded devices
    private val discovered = mutableMapOf<String, String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = result.device.name?.takeIf { it.isNotBlank() }
                ?: discovered[address]                          // keep cached name if already known
                ?: "Unknown (${address.takeLast(5)})"
            discovered[address] = name
            _bleDevices.postValue(discovered.entries.map { it.value to it.key })
        }
    }

    init {
        seedSavedDevices()
    }

    // ── Seed the device list immediately from prefs + bonded ──────────────
    private fun seedSavedDevices() {
        // USB serial is always available as the first entry (no scan required)
        discovered["USB_SERIAL"] = "USB Serial (OTG)"

        // Bonded devices (covers Classic BT and previously bonded BLE)
        try {
            btAdapter?.bondedDevices?.forEach { dev ->
                discovered[dev.address] = dev.name ?: "Unknown"
            }
        } catch (_: SecurityException) {}

        // Always include the saved rowing device so it's selectable without a fresh scan
        val savedRowingAddr = prefs.getString("rowing_device", "") ?: ""
        val savedRowingName = prefs.getString("rowing_device_name", "") ?: ""
        if (savedRowingAddr.isNotEmpty() && savedRowingAddr != "USB_SERIAL") {
            discovered.putIfAbsent(savedRowingAddr,
                savedRowingName.ifEmpty { savedRowingAddr.takeLast(5) })
        }

        // Always include the saved HR device
        val savedHrAddr = prefs.getString("hr_device", "") ?: ""
        val savedHrName = prefs.getString("hr_device_name", "") ?: ""
        if (savedHrAddr.isNotEmpty()) {
            discovered.putIfAbsent(savedHrAddr,
                savedHrName.ifEmpty { savedHrAddr.takeLast(5) })
        }

        _bleDevices.value = discovered.entries.map { it.value to it.key }
    }

    fun setMode(mode: WorkoutMode) { _mode.value = mode }
    fun setTargetDistance(m: Int) {
        _targetDistance.value = m
        _splitMeters.value = if (m == 2000) 500 else (m / 5).coerceAtLeast(100)
    }
    fun setTargetMinutes(min: Int) {
        _targetMinutes.value = min
        val totalSec = min * 60 + (_targetSeconds.value ?: 0)
        _splitSeconds.value = (totalSec / 5).coerceAtLeast(30)
    }
    fun setTargetSeconds(s: Int) {
        _targetSeconds.value = s
        val totalSec = (_targetMinutes.value ?: 0) * 60 + s
        _splitSeconds.value = (totalSec / 5).coerceAtLeast(30)
    }
    fun setSplitMeters(m: Int)  { _splitMeters.value = m }
    fun setSplitSeconds(s: Int) { _splitSeconds.value = s }
    fun setWindowSeconds(s: Int){ _windowSeconds.value = s }
    fun setTestMode(enabled: Boolean) {
        _useTestMode.value = enabled
        prefs.edit().putBoolean("test_mode", enabled).apply()
    }
    fun selectRowingDevice(address: String) {
        _selectedRowingDevice.value = address
        val name = discovered[address] ?: ""
        prefs.edit()
            .putString("rowing_device", address)
            .putString("rowing_device_name", name)
            .apply()
    }
    fun selectHrDevice(address: String) {
        _selectedHrDevice.value = address
        val name = discovered[address] ?: ""
        prefs.edit()
            .putString("hr_device", address)
            .putString("hr_device_name", name)
            .apply()
    }

    // ── BLE scan (unified — no service-UUID filter) ───────────────────────
    fun scanDevices() {
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

        scanHandler.postDelayed({ stopScan() }, SCAN_DURATION_MS)
    }

    fun stopScan() {
        if (_isScanning.value != true) return
        try { btAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
        _isScanning.value = false
    }

    // ── Machine profiles ──────────────────────────────────────────────────────

    private val machineRepo = RowingMachineRepository(application)
    val machines: LiveData<List<RowingMachine>> = machineRepo.allMachines

    private val _selectedMachineId = MutableLiveData(prefs.getInt("selected_machine_id", 0))
    val selectedMachineId: LiveData<Int> = _selectedMachineId

    fun selectMachine(id: Int) {
        _selectedMachineId.value = id
        prefs.edit().putInt("selected_machine_id", id).apply()
        if (id == 0) return
        viewModelScope.launch {
            val m = machineRepo.getById(id) ?: return@launch
            // Load machine calibration values into prefs so WorkoutViewModel picks them up
            prefs.edit()
                .putFloat("dist_factor", m.distFactor)
                .putFloat("flywheel_inertia", m.flywheelInertia)
                .putFloat("drag_factor", m.dragFactor)
                .apply()
        }
    }

    fun buildConfig(): WorkoutConfig {
        val totalTargetSecs = (_targetMinutes.value ?: 0) * 60 + (_targetSeconds.value ?: 0)
        val mode = _mode.value ?: WorkoutMode.FREE
        val targetDist = _targetDistance.value ?: 0
        val splitM = when (mode) {
            WorkoutMode.DISTANCE -> (_splitMeters.value ?: 500).coerceAtLeast(1)
            else -> if (targetDist == 2000) 500 else (targetDist / 5).coerceAtLeast(100)
        }
        val splitS = when (mode) {
            WorkoutMode.TIME -> (_splitSeconds.value ?: 60).coerceAtLeast(1)
            else -> (totalTargetSecs / 5).coerceAtLeast(30)
        }
        return WorkoutConfig(
            mode                = mode,
            targetDistance      = targetDist,
            targetSeconds       = totalTargetSecs,
            splitMeters         = splitM,
            splitSeconds        = splitS,
            windowSeconds       = _windowSeconds.value ?: 300,
            energyUnit          = if (prefs.getBoolean("use_calories", false))
                EnergyUnit.CALORIES else EnergyUnit.WATTS,
            useTestMode         = _useTestMode.value ?: false,
            rowingDeviceAddress = _selectedRowingDevice.value ?: "",
            hrDeviceAddress     = _selectedHrDevice.value ?: ""
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
