package com.naibaf7.openrower.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.naibaf7.openrower.R
import com.naibaf7.openrower.databinding.FragmentSetupBinding
import com.naibaf7.openrower.db.RowingMachine
import com.naibaf7.openrower.model.WorkoutMode

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SetupViewModel by viewModels()

    private val btPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.scanDevices()
        } else {
            Toast.makeText(requireContext(),
                "Bluetooth permissions are required to connect devices",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMachineSpinner()
        setupModeSpinner()
        setupDeviceSpinners()
        observeViewModel()
        setupListeners()
        requestBtPermissionsIfNeeded()
    }

    private fun setupModeSpinner() {
        val modes = listOf("Free Rowing", "Set Distance", "Set Time", "Target Pace")
        binding.spinnerMode.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, modes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val mode = WorkoutMode.entries[pos]
                viewModel.setMode(mode)
                updateModeVisibility(mode)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateModeVisibility(mode: WorkoutMode) {
        binding.layoutDistance.visibility  = if (mode == WorkoutMode.DISTANCE) View.VISIBLE else View.GONE
        binding.layoutTime.visibility      = if (mode == WorkoutMode.TIME)     View.VISIBLE else View.GONE
        binding.layoutTarget.visibility    = if (mode == WorkoutMode.TARGET)   View.VISIBLE else View.GONE
        binding.layoutWindow.visibility    = if (mode == WorkoutMode.FREE)     View.VISIBLE else View.GONE
        binding.layoutSplitMeters.visibility = if (mode == WorkoutMode.DISTANCE) View.VISIBLE else View.GONE
        binding.layoutSplitTime.visibility   = if (mode == WorkoutMode.TIME)     View.VISIBLE else View.GONE
        updateSplitDefaults(mode)
    }

    private fun updateSplitDefaults(mode: WorkoutMode) {
        when (mode) {
            WorkoutMode.DISTANCE -> {
                val dist = binding.editTargetDistance.text.toString().toIntOrNull() ?: 2000
                val split = if (dist == 2000) 500 else (dist / 5).coerceAtLeast(100)
                binding.editSplitMeters.setText(split.toString())
                viewModel.setSplitMeters(split)
            }
            WorkoutMode.TIME -> {
                val totalSec = (binding.editTargetMinutes.text.toString().toIntOrNull() ?: 20) * 60 +
                    (binding.editTargetSeconds.text.toString().toIntOrNull() ?: 0)
                val split = (totalSec / 5).coerceAtLeast(30)
                binding.editSplitSeconds.setText(split.toString())
                viewModel.setSplitSeconds(split)
            }
            else -> {}
        }
    }

    // Adapters are created once; contents updated in-place so the open dropdown never closes.
    private lateinit var machineAdapter: ArrayAdapter<String>
    private var machineSelectionInitialized = false
    private var machineList: List<RowingMachine> = emptyList()

    private lateinit var rowingAdapter: ArrayAdapter<String>
    private lateinit var hrAdapter: ArrayAdapter<String>
    private var rowingSelectionInitialized = false
    private var hrSelectionInitialized = false

    private fun setupMachineSpinner() {
        machineAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, ArrayList<String>()).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerMachine.adapter = machineAdapter

        binding.spinnerMachine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val machineId = if (pos == 0) 0 else machineList.getOrNull(pos - 1)?.id ?: 0
                if (machineId == viewModel.selectedMachineId.value) return
                viewModel.selectMachine(machineId)
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

    /** Both spinners share the unified BLE device list. */
    private fun setupDeviceSpinners() {
        rowingAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, ArrayList<String>()).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        hrAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, ArrayList<String>()).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerRowingDevice.adapter = rowingAdapter
        binding.spinnerHrDevice.adapter = hrAdapter

        binding.spinnerRowingDevice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val devices = viewModel.bleDevices.value ?: return
                    val addr = if (pos == 0) "" else devices.getOrNull(pos - 1)?.second ?: return
                    if (addr == viewModel.selectedRowingDevice.value) return
                    viewModel.selectRowingDevice(addr)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        binding.spinnerHrDevice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val devices = viewModel.bleDevices.value ?: return
                    val addr = if (pos == 0) "" else devices.getOrNull(pos - 1)?.second ?: return
                    if (addr == viewModel.selectedHrDevice.value) return
                    viewModel.selectHrDevice(addr)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        viewModel.bleDevices.observe(viewLifecycleOwner) { devices ->
            val names = ArrayList(listOf("None") + devices.map { "${it.first}  (${it.second})" })

            rowingAdapter.setNotifyOnChange(false)
            rowingAdapter.clear()
            rowingAdapter.addAll(names)
            rowingAdapter.notifyDataSetChanged()

            hrAdapter.setNotifyOnChange(false)
            hrAdapter.clear()
            hrAdapter.addAll(names)
            hrAdapter.notifyDataSetChanged()

            // Restore saved selections only once — subsequent updates only append new
            // devices at the end, so existing positions are stable.
            if (!rowingSelectionInitialized) {
                rowingSelectionInitialized = true
                val idx = devices.indexOfFirst { it.second == viewModel.selectedRowingDevice.value }
                if (idx >= 0) binding.spinnerRowingDevice.setSelection(idx + 1)
            }
            if (!hrSelectionInitialized) {
                hrSelectionInitialized = true
                val idx = devices.indexOfFirst { it.second == viewModel.selectedHrDevice.value }
                if (idx >= 0) binding.spinnerHrDevice.setSelection(idx + 1)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.useTestMode.observe(viewLifecycleOwner) { binding.switchTestMode.isChecked = it }
        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            binding.btnRefreshDevices.text = if (scanning) "Scanning…" else "Scan BLE"
            binding.btnRefreshDevices.isEnabled = !scanning
        }
    }

    private fun setupListeners() {
        binding.switchTestMode.setOnCheckedChangeListener { _, checked ->
            viewModel.setTestMode(checked)
        }

        binding.btnStart.setOnClickListener {
            // Flush any uncommitted edit-field values before building the config.
            // setOnFocusChangeListener only fires when focus leaves, so tapping Start
            // directly after typing would otherwise leave the old value in the ViewModel.
            viewModel.setSplitMeters(binding.editSplitMeters.text.toString().toIntOrNull() ?: 500)
            viewModel.setSplitSeconds(binding.editSplitSeconds.text.toString().toIntOrNull() ?: 60)
            viewModel.setTargetDistance(binding.editTargetDistance.text.toString().toIntOrNull() ?: 2000)
            viewModel.setTargetMinutes(binding.editTargetMinutes.text.toString().toIntOrNull() ?: 20)
            viewModel.setTargetSeconds(binding.editTargetSeconds.text.toString().toIntOrNull() ?: 0)
            viewModel.setWindowSeconds(binding.editWindowSeconds.text.toString().toIntOrNull() ?: 300)
            val config = viewModel.buildConfig()
            if (!config.useTestMode && config.rowingDeviceAddress.isEmpty()) {
                Toast.makeText(requireContext(),
                    "Select a rowing device or enable test mode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bundle = bundleOf("config" to config)
            findNavController().navigate(R.id.action_setup_to_workout, bundle)
        }

        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_setup_to_settings)
        }

        binding.btnHistory.setOnClickListener {
            findNavController().navigate(R.id.action_setup_to_history)
        }

        binding.btnRefreshDevices.setOnClickListener {
            viewModel.scanDevices()
        }

        binding.editSplitMeters.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setSplitMeters(
                binding.editSplitMeters.text.toString().toIntOrNull() ?: 500)
        }
        binding.editSplitSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setSplitSeconds(
                binding.editSplitSeconds.text.toString().toIntOrNull() ?: 60)
        }
        binding.editTargetDistance.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setTargetDistance(
                binding.editTargetDistance.text.toString().toIntOrNull() ?: 2000)
        }
        binding.editTargetMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setTargetMinutes(
                binding.editTargetMinutes.text.toString().toIntOrNull() ?: 20)
        }
        binding.editTargetSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setTargetSeconds(
                binding.editTargetSeconds.text.toString().toIntOrNull() ?: 0)
        }
        binding.editWindowSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setWindowSeconds(
                binding.editWindowSeconds.text.toString().toIntOrNull() ?: 300)
        }
    }

    private fun requestBtPermissionsIfNeeded() {
        val missing = btPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.scanDevices()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
