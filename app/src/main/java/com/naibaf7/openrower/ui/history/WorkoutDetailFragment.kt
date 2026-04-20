package com.naibaf7.openrower.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.naibaf7.openrower.databinding.FragmentWorkoutDetailBinding
import com.naibaf7.openrower.db.SplitEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkoutDetailFragment : Fragment() {

    private var _binding: FragmentWorkoutDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WorkoutDetailViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val basePadH = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutToolbarDetail.setPadding(basePadH, bars.top, basePadH, 0)
            binding.scrollView.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        val workoutId = arguments?.getLong("workoutId") ?: return
        viewModel.load(workoutId)

        val splitAdapter = SplitAdapter()
        binding.recyclerSplits.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter        = splitAdapter
        }

        viewModel.workout.observe(viewLifecycleOwner) { w ->
            if (w == null) return@observe
            val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            binding.txtDetailDate.text     = dateFmt.format(Date(w.dateMs))
            binding.txtDetailMode.text     = w.modeName.lowercase().replaceFirstChar { it.uppercase() }
            binding.txtDetailDuration.text = formatTime(w.totalDurationSec)
            binding.txtDetailDist.text     = "%.0f m".format(w.totalMeters)
            binding.txtDetailPace.text     = formatPace(w.totalDurationSec, w.totalMeters)
            binding.txtDetailPower.text    = "%.0f W avg".format(w.avgPowerWatts)
            binding.txtDetailCals.text     = "%.1f kcal".format(w.totalCaloriesKcal)
            binding.txtDetailHr.text       = w.avgHeartRate?.let { "$it bpm avg" } ?: "—"
            binding.txtDetailStrokes.text  = "${w.strokeCount} strokes"
        }

        viewModel.splits.observe(viewLifecycleOwner) { splits ->
            splitAdapter.submitList(splits)
            binding.txtSplitsHeader.text = "Splits (${splits.size})"
        }

        viewModel.deleted.observe(viewLifecycleOwner) { deleted ->
            if (deleted) findNavController().navigateUp()
        }

        binding.btnDeleteWorkout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete workout?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> viewModel.deleteWorkout() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun formatTime(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0L)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "$h:%02d:%02d".format(m, s) else "$m:%02d".format(s)
    }

    private fun formatPace(durationSec: Double, distanceMeters: Double): String {
        if (distanceMeters < 1.0) return "--:--"
        val secPer500 = 500.0 * durationSec / distanceMeters
        val t = secPer500.toLong().coerceIn(0L, 899L)
        return "${t / 60}:%02d".format(t % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ── Inline split adapter ───────────────────────────────────────────────────

private class SplitAdapter : androidx.recyclerview.widget.ListAdapter<SplitEntity,
        SplitAdapter.SplitVH>(object : androidx.recyclerview.widget.DiffUtil.ItemCallback<SplitEntity>() {
    override fun areItemsTheSame(a: SplitEntity, b: SplitEntity) = a.id == b.id
    override fun areContentsTheSame(a: SplitEntity, b: SplitEntity) = a == b
}) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SplitVH {
        val v = LayoutInflater.from(parent.context).inflate(com.naibaf7.openrower.R.layout.item_split, parent, false)
        return SplitVH(v)
    }

    override fun onBindViewHolder(holder: SplitVH, position: Int) = holder.bind(getItem(position))

    class SplitVH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        private val txtNum   = view.findViewById<android.widget.TextView>(com.naibaf7.openrower.R.id.txt_split_num)
        private val txtDur   = view.findViewById<android.widget.TextView>(com.naibaf7.openrower.R.id.txt_split_duration)
        private val txtDist  = view.findViewById<android.widget.TextView>(com.naibaf7.openrower.R.id.txt_split_distance)
        private val txtPace  = view.findViewById<android.widget.TextView>(com.naibaf7.openrower.R.id.txt_split_pace)
        private val txtPower = view.findViewById<android.widget.TextView>(com.naibaf7.openrower.R.id.txt_split_power)
        private val txtHr    = view.findViewById<android.widget.TextView>(com.naibaf7.openrower.R.id.txt_split_hr)

        fun bind(s: SplitEntity) {
            txtNum.text   = "S${s.splitNumber}"
            txtDur.text   = formatTime(s.durationSec)
            txtDist.text  = "%.0f m".format(s.distanceMeters)
            txtPace.text  = formatPace(s.durationSec, s.distanceMeters)
            txtPower.text = "%.0f W".format(s.avgPowerWatts)
            txtHr.text    = s.avgHeartRate?.let { "$it bpm" } ?: "—"
        }

        private fun formatTime(sec: Double): String {
            val t = sec.toLong().coerceAtLeast(0L)
            val m = t / 60; val s = t % 60
            return "$m:%02d".format(s)
        }

        private fun formatPace(durationSec: Double, distanceMeters: Double): String {
            if (distanceMeters < 1.0) return "--:--"
            val secPer500 = 500.0 * durationSec / distanceMeters
            val t = secPer500.toLong().coerceIn(0L, 899L)
            return "${t / 60}:%02d".format(t % 60)
        }
    }
}
