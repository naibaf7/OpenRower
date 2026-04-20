package com.naibaf7.openrower.ui.settings

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
import com.naibaf7.openrower.db.RowingMachine
import com.naibaf7.openrower.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var rowingDeviceAdapter: ArrayAdapter<String>
    private var rowingSelectionInitialized = false

    private lateinit var machineAdapter: ArrayAdapter<String>
    private var machineSelectionInitialized = false
    private var machineList: List<RowingMachine> = emptyList()

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

        // ── Machine profiles ──────────────────────────────────────────────
        setupMachineSpinner()

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

        // ── Machine profile buttons ───────────────────────────────────────
        binding.btnAddMachine.setOnClickListener { showNameDialog(null) }
        binding.btnRenameMachine.setOnClickListener {
            val selectedId = viewModel.selectedMachineId.value ?: 0
            val machine = machineList.firstOrNull { it.id == selectedId }
            if (machine == null) {
                Toast.makeText(requireContext(), "Select a machine first", Toast.LENGTH_SHORT).show()
            } else {
                showNameDialog(machine)
            }
        }
        binding.btnDeleteMachine.setOnClickListener {
            val selectedId = viewModel.selectedMachineId.value ?: 0
            val machine = machineList.firstOrNull { it.id == selectedId }
            if (machine == null) {
                Toast.makeText(requireContext(), "Select a machine first", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(requireContext())
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
            val names = ArrayList(listOf("None (manual)") + machines.map { it.name })
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
