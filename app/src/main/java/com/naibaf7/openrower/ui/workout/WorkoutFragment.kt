package com.naibaf7.openrower.ui.workout

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.naibaf7.openrower.databinding.FragmentWorkoutBinding
import com.naibaf7.openrower.model.EnergyUnit
import com.naibaf7.openrower.model.WorkoutConfig
import com.naibaf7.openrower.model.WorkoutMode
import com.naibaf7.openrower.model.WorkoutState

class WorkoutFragment : Fragment() {

    private var _binding: FragmentWorkoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WorkoutViewModel by viewModels()


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("DEPRECATION")
        val config = arguments?.getSerializable("config") as? WorkoutConfig ?: WorkoutConfig()

        viewModel.startWorkout(config)
        observeViewModel(config)
        setupToggleButtons()
        setupConnectingOverlay(config)
    }

    private fun observeViewModel(config: WorkoutConfig) {
        viewModel.state.observe(viewLifecycleOwner) { s -> updateUi(s, config) }

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }

        viewModel.showForceCurve.observe(viewLifecycleOwner) { show ->
            binding.forceCurveView.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnToggleForceCurve.alpha  = if (show) 1f else 0.4f
        }
        viewModel.showBarChart.observe(viewLifecycleOwner) { show ->
            binding.barChartView.visibility   = if (show) View.VISIBLE else View.GONE
            binding.btnToggleBarChart.alpha    = if (show) 1f else 0.4f
        }
        viewModel.showMetrics.observe(viewLifecycleOwner) { show ->
            binding.metricsPanel.visibility   = if (show) View.VISIBLE else View.GONE
            binding.btnToggleMetrics.alpha     = if (show) 1f else 0.4f
        }
    }

    private fun updateUi(s: WorkoutState, config: WorkoutConfig) {
        // ── Stroke rate ───────────────────────────────────────────────────
        binding.txtStrokeRate.text = if (s.strokeRate > 0) "%.0f".format(s.strokeRate) else "0"

        // ── Time ──────────────────────────────────────────────────────────
        binding.txtElapsedTime.text   = formatTime(s.elapsedSeconds)
        binding.txtRemainingTime.text = s.remainingSeconds
            ?.let { "rem ${formatTime(it)}" } ?: ""

        // ── Distance ──────────────────────────────────────────────────────
        binding.txtElapsedDist.text   = "%.0f m".format(s.elapsedMeters)
        binding.txtRemainingDist.text = s.remainingMeters
            ?.let { "rem %.0f m".format(it) } ?: ""

        // ── Pace ──────────────────────────────────────────────────────────
        binding.txtCurrentPace.text = formatPace(s.currentPaceSec500)
        binding.txtAvgPace.text     = if (s.avgPaceSec500 < 900)
            "avg ${formatPace(s.avgPaceSec500)}" else ""

        // ── Energy ────────────────────────────────────────────────────────
        if (config.energyUnit == EnergyUnit.WATTS) {
            binding.txtEnergyCurrent.text = "%.0f W".format(s.currentPowerWatts)
            binding.txtEnergyAvg.text     = "avg %.0f W".format(s.avgPowerWatts)
        } else {
            val kcalH = s.currentPowerWatts * 3.6 + 300.0
            binding.txtEnergyCurrent.text = "%.0f kcal/h".format(kcalH)
            binding.txtEnergyAvg.text     = "%.1f kcal".format(s.totalCaloriesKcal)
        }

        // ── Heart rate ────────────────────────────────────────────────────
        binding.txtHeartRate.text = s.heartRateBpm?.let { "$it bpm" } ?: "-- bpm"
        binding.txtHeartRate.setTextColor(hrZoneColor(s.heartRateBpm, viewModel.maxHr))

        // ── Split ─────────────────────────────────────────────────────────
        binding.txtSplitNumber.text = "S${s.currentSplitNumber}"
        binding.txtSplitTime.text   = formatTime(s.splitElapsedSeconds)
        binding.txtSplitDist.text   = "%.0f m".format(s.splitElapsedMeters)

        // ── Projection ───────────────────────────────────────────────────
        binding.txtProjection.text = when {
            s.projectedTotalMeters  != null -> "→ %.0f m total".format(s.projectedTotalMeters)
            s.projectedTotalSeconds != null -> "→ ${formatTime(s.projectedTotalSeconds)} total"
            else -> ""
        }

        // ── Connection ───────────────────────────────────────────────────
        binding.txtConnectionStatus.text = if (s.isConnected) "●" else "○ connecting…"

        // ── Charts ───────────────────────────────────────────────────────
        binding.forceCurveView.samples   = s.forceCurveSamples
        binding.forceCurveView.windowSec = s.forceCurveWindowSec

        binding.barChartView.apply {
            strokes            = s.strokeHistory
            windowStartSec     = s.chartWindowStartSec
            windowEndSec       = s.chartWindowEndSec
            windowStartMeters  = s.chartWindowStartMeters
            windowEndMeters    = s.chartWindowEndMeters
            workoutMode        = config.mode
            maxHr              = viewModel.maxHr
            energyUnit         = config.energyUnit
        }

        // ── Finished ─────────────────────────────────────────────────────
        if (s.isFinished && binding.btnEnd.text != "Done") {
            binding.btnEnd.text = "Done"
        }
    }

    private fun setupConnectingOverlay(config: WorkoutConfig) {
        // In test mode isConnected is set immediately, overlay stays gone.
        // In real BLE mode, show overlay until first message arrives.
        if (!config.useTestMode) {
            binding.layoutConnecting.visibility = View.VISIBLE
            // Personalise the label with the saved device name from prefs
            val prefs = requireContext().getSharedPreferences("openrower",
                android.content.Context.MODE_PRIVATE)
            val name = prefs.getString("rowing_device_name", "")
                ?.takeIf { it.isNotEmpty() } ?: config.rowingDeviceAddress
            binding.txtConnectingLabel.text = "Waiting for $name to connect…"
        }

        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.layoutConnecting.visibility = if (connected) View.GONE else View.VISIBLE
        }

        viewModel.connectStatus.observe(viewLifecycleOwner) { status ->
            binding.txtConnectingLabel.text = status
        }

        binding.btnCancelConnect.setOnClickListener {
            viewModel.endWorkout()
            findNavController().navigateUp()
        }
    }

    private fun setupToggleButtons() {
        binding.btnToggleForceCurve.setOnClickListener { viewModel.toggleForceCurve() }
        binding.btnToggleBarChart.setOnClickListener   { viewModel.toggleBarChart() }
        binding.btnToggleMetrics.setOnClickListener    { viewModel.toggleMetrics() }
        binding.btnEnd.setOnClickListener {
            viewModel.endWorkout()
            findNavController().navigateUp()
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Immersive fullscreen: hide status bar + navigation bar for the workout screen only.
        requireActivity().window.insetsController?.let { ctrl ->
            ctrl.hide(WindowInsets.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // Restore system bars for all other screens.
        requireActivity().window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun formatTime(totalSeconds: Double): String {
        val t = totalSeconds.toLong().coerceAtLeast(0L)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "$h:%02d:%02d".format(m, s) else "$m:%02d".format(s)
    }

    private fun hrZoneColor(bpm: Int?, maxHr: Int): Int {
        if (bpm == null) return Color.parseColor("#EF5350")
        val pct = bpm.toFloat() / maxHr
        return when {
            pct < 0.57f -> Color.parseColor("#B0BEC5")  // Zone 1 – light grey
            pct < 0.63f -> Color.parseColor("#26C6DA")  // Zone 2 – cyan
            pct < 0.73f -> Color.parseColor("#66BB6A")  // Zone 3 – green
            pct < 0.83f -> Color.parseColor("#FFA726")  // Zone 4 – orange
            else         -> Color.parseColor("#EF5350")  // Zone 5 – red
        }
    }

    private fun formatPace(secPer500: Double): String {
        if (secPer500 >= 900) return "--:--"
        val t = secPer500.toLong().coerceIn(0L, 899L)
        return "${t / 60}:%02d".format(t % 60)
    }
}
