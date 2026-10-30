package com.smartapes.openrower.ui.settings

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.smartapes.openrower.R
import com.smartapes.openrower.bluetooth.RowingDataParser
import com.smartapes.openrower.db.RowingMachine
import com.smartapes.openrower.databinding.FragmentSettingsBinding
import com.smartapes.openrower.model.SensorMode

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var rowingDeviceAdapter: ArrayAdapter<String>
    private var rowingSelectionInitialized = false

    private lateinit var machineAdapter: ArrayAdapter<String>
    private var machineSelectionInitialized = false
    private var machineList: List<RowingMachine> = emptyList()

    private var sensorSpinnerUpdating = false

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.scanForDevice()
        } else {
            Toast.makeText(requireContext(), "Bluetooth permission required for scan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pad the ScrollView so content clears the status/nav bars.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.getChildAt(0)?.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // ── Calibration values ────────────────────────────────────────────
        viewModel.dragFactor.observe(viewLifecycleOwner) {
            binding.txtDragFactor.text = "%.1f".format(it)
        }
        viewModel.distFactor.observe(viewLifecycleOwner) {
            if (!binding.editDistFactor.isFocused)
                binding.editDistFactor.setText("%.4f".format(it))
        }
        viewModel.flywheelInertia.observe(viewLifecycleOwner) {
            if (!binding.editFlywheelInertia.isFocused)
                binding.editFlywheelInertia.setText("%.3f".format(it))
        }
        viewModel.useCalories.observe(viewLifecycleOwner) {
            binding.switchUseCalories.isChecked = it
        }
        viewModel.enablePulseLog.observe(viewLifecycleOwner) {
            binding.switchEnableLog.isChecked = it
        }
        viewModel.maxHr.observe(viewLifecycleOwner) {
            if (!binding.editMaxHr.isFocused) binding.editMaxHr.setText(it.toString())
        }
        viewModel.pulsesPerRevolution.observe(viewLifecycleOwner) {
            if (!binding.editPulsesPerRevolution.isFocused)
                binding.editPulsesPerRevolution.setText(it.toString())
        }

        // ── Machine profiles ──────────────────────────────────────────────
        setupMachineSpinner()
        setupSensorModeSpinner()

        // ── Device spinner ────────────────────────────────────────────────
        setupDeviceSpinner()
        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            binding.btnScanDevice.text = if (scanning) "Scanning…" else "Scan"
            binding.btnScanDevice.isEnabled = !scanning
        }

        // ── c_dist calibration ────────────────────────────────────────────
        viewModel.calibDistState.observe(viewLifecycleOwner) { state ->
            when (state) {
                SettingsViewModel.CalibDistState.IDLE -> {
                    binding.layoutCalibDistProgress.visibility = View.GONE
                    binding.btnCalibDist.isEnabled = true
                }
                SettingsViewModel.CalibDistState.DONE -> {
                    binding.layoutCalibDistProgress.visibility = View.GONE
                    binding.btnCalibDist.isEnabled = true
                    val cDist = viewModel.calibDistResult.value ?: 0f
                    if (cDist > 0f) {
                        val j = viewModel.flywheelInertia.value ?: 0f
                        val df = viewModel.dragFactor.value ?: 0f
                        Toast.makeText(requireContext(),
                            "c_dist = ${"%.5f".format(cDist)}\n" +
                            "df = ${"%.1f".format(df)}\n" +
                            "J = ${"%.4f".format(j)} kg·m²",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(),
                            "No rotations counted — start rowing",
                            Toast.LENGTH_LONG).show()
                    }
                }
                SettingsViewModel.CalibDistState.CONNECTING -> {
                    binding.layoutCalibDistProgress.visibility = View.VISIBLE
                    binding.txtCalibDistStatus.text = "Connecting to device… Start rowing when ready."
                    binding.btnCalibDist.isEnabled = false
                }
                SettingsViewModel.CalibDistState.RUNNING -> {
                    binding.layoutCalibDistProgress.visibility = View.VISIBLE
                    binding.txtCalibDistStatus.text =
                        "Counting rotations — row your target distance, then stop."
                    binding.btnCalibDist.isEnabled = false
                }
                SettingsViewModel.CalibDistState.ERROR -> {
                    binding.layoutCalibDistProgress.visibility = View.GONE
                    binding.btnCalibDist.isEnabled = true
                    Toast.makeText(requireContext(),
                        "Calibration failed — check device selection", Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewModel.calibDistRevolutions.observe(viewLifecycleOwner) { rev ->
            binding.txtCalibDistRevolutions.text = "%.1f".format(rev)
        }
        viewModel.calibDistCountdown.observe(viewLifecycleOwner) { sec ->
            binding.txtCalibDistCountdown.text = "$sec s"
        }

        // ── Save on focus-lost ────────────────────────────────────────────
        binding.editMaxHr.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = binding.editMaxHr.text.toString().toIntOrNull()?.coerceIn(100, 220)
                    ?: return@setOnFocusChangeListener
                viewModel.setMaxHr(v)
            }
        }
        binding.editPulsesPerRevolution.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = binding.editPulsesPerRevolution.text.toString().toIntOrNull()
                    ?.coerceIn(1, 12) ?: return@setOnFocusChangeListener
                viewModel.setPulsesPerRevolution(v)
            }
        }
        binding.editDistFactor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = binding.editDistFactor.text.toString().toFloatOrNull()
                    ?: return@setOnFocusChangeListener
                viewModel.setDistFactor(v)
            }
        }
        binding.editFlywheelInertia.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = binding.editFlywheelInertia.text.toString().toFloatOrNull()
                    ?.coerceIn(0.001f, 1.0f) ?: return@setOnFocusChangeListener
                viewModel.setFlywheelInertia(v)
            }
        }
        binding.switchUseCalories.setOnCheckedChangeListener { _, checked ->
            viewModel.setUseCalories(checked)
        }
        binding.switchEnableLog.setOnCheckedChangeListener { _, checked ->
            viewModel.setEnablePulseLog(checked)
        }

        // ── Device scan button ────────────────────────────────────────────
        binding.btnScanDevice.setOnClickListener {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                viewModel.scanForDevice()
            } else {
                btPermissionLauncher.launch(missing.toTypedArray())
            }
        }

        // ── Clear training logs ───────────────────────────────────────────
        binding.btnClearLogs.setOnClickListener {
            showClearLogsDialog()
        }

        // ── About / open-source licenses ──────────────────────────────────
        val ctx = requireContext()
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        binding.txtAbout.text = "OpenRower $version · MIT License\nhttps://github.com/naibaf7/OpenRower"
        binding.btnLicenses.setOnClickListener { showLicensesDialog() }

        // ── c_dist calibration buttons ────────────────────────────────────
        binding.btnCalibDist.setOnClickListener {
            val addr = viewModel.rowingDeviceAddress.value ?: ""
            if (addr.isEmpty()) {
                Toast.makeText(requireContext(), "Select a rowing device first", Toast.LENGTH_SHORT).show()
            } else {
                // Flush target distance field before starting
                val targetM = binding.editCalibDistTarget.text.toString().toFloatOrNull() ?: 500f
                viewModel.startDistCalibration(targetM)
            }
        }
        binding.btnCalibDistCancel.setOnClickListener {
            viewModel.cancelDistCalibration()
        }

        // ── Media folder & TTS ───────────────────────────────────────────
        val prefs = requireContext().getSharedPreferences("openrower", android.content.Context.MODE_PRIVATE)
        binding.editMediaFolder.setText(prefs.getString("media_folder", "") ?: "")
        binding.editMediaFolder.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val path = binding.editMediaFolder.text.toString().trim()
                prefs.edit().putString("media_folder", path).apply()
            }
        }
        binding.btnBrowseMediaFolder.setOnClickListener {
            Toast.makeText(requireContext(), "Enter path manually or use Custom Workout Editor > Browse", Toast.LENGTH_SHORT).show()
        }

        val ttsEnabled = prefs.getBoolean("tts_enabled", false)
        binding.switchTtsEnabled.isChecked = ttsEnabled
        binding.switchTtsEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("tts_enabled", checked).apply()
        }

        val ttsLangs = listOf("en" to "English", "de" to "Deutsch", "fr" to "Français",
            "es" to "Español", "it" to "Italiano", "ja" to "日本語", "zh" to "中文")
        val ttsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
            ttsLangs.map { it.second }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerTtsLanguage.adapter = ttsAdapter
        val savedLang = prefs.getString("tts_language", "en") ?: "en"
        val langIdx = ttsLangs.indexOfFirst { it.first == savedLang }.coerceAtLeast(0)
        binding.spinnerTtsLanguage.setSelection(langIdx)
        binding.spinnerTtsLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("tts_language", ttsLangs[pos].first).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // ── Machine profile buttons ───────────────────────────────────────
        binding.btnAddMachine.setOnClickListener { showNameDialog(null) }
        binding.btnRenameMachine.setOnClickListener {
            val selectedId = viewModel.selectedMachineId.value ?: 0
            val machine = machineList.firstOrNull { it.id == selectedId }
            when {
                machine == null  -> Toast.makeText(requireContext(), "Select a machine first", Toast.LENGTH_SHORT).show()
                machine.id < 0   -> Toast.makeText(requireContext(), "Built-in profiles cannot be modified", Toast.LENGTH_SHORT).show()
                else             -> showNameDialog(machine)
            }
        }
        binding.btnDeleteMachine.setOnClickListener {
            val selectedId = viewModel.selectedMachineId.value ?: 0
            val machine = machineList.firstOrNull { it.id == selectedId }
            when {
                machine == null -> Toast.makeText(requireContext(), "Select a machine first", Toast.LENGTH_SHORT).show()
                machine.id < 0  -> Toast.makeText(requireContext(), "Built-in profiles cannot be deleted", Toast.LENGTH_SHORT).show()
                else -> AlertDialog.Builder(requireContext())
                    .setTitle("Delete machine")
                    .setMessage("Delete '${machine.name}'?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteMachine(machine) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun setupMachineSpinner() {
        machineAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, ArrayList<String>()).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerMachine.adapter = machineAdapter

        binding.spinnerMachine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val machineId = if (pos == 0) 0 else machineList.getOrNull(pos - 1)?.id ?: 0
                if (machineId == viewModel.selectedMachineId.value) return
                viewModel.selectMachineById(machineId)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        viewModel.machines.observe(viewLifecycleOwner) { machines ->
            machineList = machines
            val names = ArrayList(listOf("None (manual)") + machines.map {
                if (it.id < 0) "${it.name} (built-in)" else it.name
            })
            machineAdapter.setNotifyOnChange(false)
            machineAdapter.clear()
            machineAdapter.addAll(names)
            machineAdapter.notifyDataSetChanged()

            if (!machineSelectionInitialized) {
                machineSelectionInitialized = true
                val savedId = viewModel.selectedMachineId.value ?: 0
                val idx = machines.indexOfFirst { it.id == savedId }
                if (idx >= 0) binding.spinnerMachine.setSelection(idx + 1)
            }
        }
    }

    // ── Sensor input (hall 3.3 V / hall 5 V / Concept2) and auto-detect ──────
    private fun setupSensorModeSpinner() {
        val modes = SensorMode.entries
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
            modes.map { it.label }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerSensorMode.adapter = adapter
        binding.spinnerSensorMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (sensorSpinnerUpdating) return
                val chosen = modes[pos]
                val current = viewModel.sensorMode.value ?: SensorMode.DEFAULT
                if (chosen == current) return
                if (chosen == SensorMode.HALL_5V) confirmFiveVolt(current) else viewModel.setSensorMode(chosen)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        viewModel.sensorMode.observe(viewLifecycleOwner) { mode -> showSensorMode(mode) }

        binding.btnDetectSensor.setOnClickListener {
            if ((viewModel.rowingDeviceAddress.value ?: "").isEmpty()) {
                Toast.makeText(requireContext(), "Select a rowing device first", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.detectSensor()
            }
        }
        viewModel.detectState.observe(viewLifecycleOwner) { state ->
            binding.btnDetectSensor.isEnabled = state != SettingsViewModel.DetectState.RUNNING
            binding.btnDetectSensor.text =
                if (state == SettingsViewModel.DetectState.RUNNING) "Detecting…" else "Detect"
            if (state == SettingsViewModel.DetectState.DONE) {
                showDetectResult(viewModel.detectResult.value)
                viewModel.clearDetect()
            }
        }
    }

    private fun showSensorMode(mode: SensorMode) {
        sensorSpinnerUpdating = true
        binding.spinnerSensorMode.setSelection(SensorMode.entries.indexOf(mode))
        binding.spinnerSensorMode.post { sensorSpinnerUpdating = false }
    }

    /** 5 V is opt-in: a sensor rated for 3.3 V only can be damaged by it. */
    private fun confirmFiveVolt(previous: SensorMode) {
        AlertDialog.Builder(requireContext())
            .setTitle("Supply the sensor with 5 V?")
            .setMessage("Only choose 5 V if your hall sensor is rated for it (e.g. A3144, OH3144, " +
                "most 4.5–24 V sensors). A sensor rated for 3.3 V only can be damaged by 5 V. " +
                "If unsure, keep 3.3 V.")
            .setPositiveButton("Use 5 V") { _, _ -> viewModel.setSensorMode(SensorMode.HALL_5V) }
            .setNegativeButton("Cancel") { _, _ -> showSensorMode(previous) }
            .setOnCancelListener { showSensorMode(previous) }
            .show()
    }

    private fun showDetectResult(r: RowingDataParser.Message.DetectResult?) {
        val volts = r?.let { "%.1f V".format(it.ringMv / 1000.0) } ?: ""
        val (message, suggestion) = when (r?.result) {
            "GENERATOR" -> "Concept2 Model D or E: the flywheel generator puts $volts on the ring contact. " +
                "Use a Concept2 Model D/E profile." to SensorMode.CONCEPT2
            "SHORT" -> "The ring contact is shorted to ground: a Concept2 Model C pickup (mono plug), " +
                "or a Model D/E whose flywheel is standing still — spin it and detect again to tell them apart." to
                SensorMode.CONCEPT2
            "POWERED" -> "The ring contact took the 3.3 V supply ($volts): a powered hall sensor, or nothing " +
                "plugged in. Keep a hall sensor input; use 5 V only if your sensor needs it." to SensorMode.HALL_3V3
            null -> "No answer from the board. Auto-detect needs the OpenRower ESP32 board — check the " +
                "device selection and that the board is powered." to null
            else -> "Unknown result '${r.result}' ($volts)." to null
        }
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Sensor detection")
            .setMessage(message)
            .setNegativeButton("Close", null)
        val current = viewModel.sensorMode.value ?: SensorMode.DEFAULT
        val keepHall = suggestion == SensorMode.HALL_3V3 && current != SensorMode.CONCEPT2
        if (suggestion != null && suggestion != current && !keepHall) {
            dlg.setPositiveButton("Use ${if (suggestion == SensorMode.CONCEPT2) "Concept2 input" else "hall 3.3 V"}") { _, _ ->
                viewModel.setSensorMode(suggestion)
            }
        }
        dlg.show()
    }

    private fun setupDeviceSpinner() {
        rowingDeviceAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, ArrayList<String>()).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerRowingDevice.adapter = rowingDeviceAdapter
        binding.spinnerRowingDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val devices = viewModel.bleDevices.value ?: return
                val addr = if (pos == 0) "" else devices.getOrNull(pos - 1)?.second ?: return
                if (addr == viewModel.rowingDeviceAddress.value) return
                viewModel.selectDevice(addr)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        viewModel.bleDevices.observe(viewLifecycleOwner) { devices ->
            val names = ArrayList(listOf("None") + devices.map { "${it.first}  (${it.second})" })
            rowingDeviceAdapter.setNotifyOnChange(false)
            rowingDeviceAdapter.clear()
            rowingDeviceAdapter.addAll(names)
            rowingDeviceAdapter.notifyDataSetChanged()

            if (!rowingSelectionInitialized) {
                rowingSelectionInitialized = true
                val savedAddr = viewModel.rowingDeviceAddress.value ?: ""
                val idx = devices.indexOfFirst { it.second == savedAddr }
                if (idx >= 0) binding.spinnerRowingDevice.setSelection(idx + 1)
            }
        }
    }

    private fun showNameDialog(existing: RowingMachine?) {
        val input = EditText(requireContext()).apply {
            hint = "Machine name"
            existing?.let { setText(it.name) }
            selectAll()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "New machine" else "Rename machine")
            .setView(input)
            .setPositiveButton(if (existing == null) "Create" else "Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (existing == null) viewModel.addMachine(name)
                else viewModel.renameMachine(existing, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearLogsDialog() {
        val calendar = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                calendar.set(year, month, day, 23, 59, 59)
                val cutoffMs = calendar.timeInMillis
                viewModel.clearLogsOlderThan(cutoffMs)
                Toast.makeText(requireContext(),
                    "Deleted workouts before ${day}/${month + 1}/$year",
                    Toast.LENGTH_SHORT).show()
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Licenses of the libraries bundled in the app (res/raw/open_source_licenses.txt). */
    private fun showLicensesDialog() {
        val text = resources.openRawResource(R.raw.open_source_licenses).bufferedReader().use { it.readText() }
        AlertDialog.Builder(requireContext())
            .setTitle("Open-Source Licenses")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
