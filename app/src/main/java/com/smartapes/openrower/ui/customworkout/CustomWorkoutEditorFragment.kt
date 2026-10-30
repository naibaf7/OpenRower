package com.smartapes.openrower.ui.customworkout

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.smartapes.openrower.R
import com.smartapes.openrower.audio.SongInfo
import com.smartapes.openrower.databinding.FragmentCustomWorkoutEditorBinding
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.WorkoutSegment

class CustomWorkoutEditorFragment : Fragment() {

    private var _binding: FragmentCustomWorkoutEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CustomWorkoutEditorViewModel by viewModels()

    // Suppresses reactive listeners while updateEditorPanel is repopulating fields,
    // preventing the checkTimeless listener from overwriting segments on selection change.
    private var isUpdatingUi = false

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Convert content URI to file path (best-effort for primary storage)
            val path = uri.path?.replace("/tree/primary:", "/storage/emulated/0/") ?: return@registerForActivityResult
            viewModel.setMediaFolder(path)
            binding.editMediaFolder.setText(path)
        }
    }

    private val playlistPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val path = uri.path?.replace("/document/primary:", "/storage/emulated/0/") ?: return@registerForActivityResult
            viewModel.loadFromPlaylist(path)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCustomWorkoutEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load existing workout if id was passed
        val loadId = arguments?.getLong("customWorkoutId", 0L) ?: 0L
        if (loadId > 0L) viewModel.load(loadId)
        else {
            val savedFolder = viewModel.getSavedMediaFolder()
            if (savedFolder.isNotBlank()) {
                binding.editMediaFolder.setText(savedFolder)
                viewModel.setMediaFolder(savedFolder)
            }
        }

        setupToolbar()
        setupTimeline()
        setupAddButtons()
        setupSegmentEditor()
        setupMusicControls()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.btnSaveWorkout.setOnClickListener {
            val name = binding.editWorkoutName.text.toString().ifBlank { "Custom Workout" }
            viewModel.setName(name)
            viewModel.save { _ ->
                Toast.makeText(requireContext(), "Workout saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLoadWorkout.setOnClickListener { showLoadDialog() }
    }

    private fun setupTimeline() {
        binding.timelineView.onSegmentClick = { idx ->
            viewModel.selectSegment(idx)
        }
    }

    private fun setupAddButtons() {
        binding.btnAddWorkout.setOnClickListener { viewModel.addWorkoutSegment() }
        binding.btnAddRecovery.setOnClickListener { viewModel.addRecoverySegment() }
        binding.btnRemoveSegment.setOnClickListener {
            val idx = viewModel.selectedIndex.value ?: -1
            if (idx >= 0) viewModel.removeSegment(idx)
        }
        binding.btnMoveLeft.setOnClickListener {
            val idx = viewModel.selectedIndex.value ?: return@setOnClickListener
            if (idx > 0) viewModel.moveSegment(idx, idx - 1)
        }
        binding.btnMoveRight.setOnClickListener {
            val idx = viewModel.selectedIndex.value ?: return@setOnClickListener
            val size = viewModel.workout.value?.segments?.size ?: 0
            if (idx < size - 1) viewModel.moveSegment(idx, idx + 1)
        }
    }

    private fun setupSegmentEditor() {
        // Commit on every keystroke so no explicit focus-change is needed.
        binding.editSegDuration.doAfterTextChanged { text ->
            if (isUpdatingUi) return@doAfterTextChanged
            val v = text?.toString()?.toDoubleOrNull()
            if (v != null && v > 0) viewModel.setSegmentDuration(v)
        }
        binding.editSegDistance.doAfterTextChanged { text ->
            if (isUpdatingUi) return@doAfterTextChanged
            val v = text?.toString()?.toDoubleOrNull()
            if (v != null && v > 0) viewModel.setSegmentDistance(v)
        }
        binding.checkTimeless.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            viewModel.setSegmentTimeless(checked)
            binding.editSegDuration.isEnabled = !checked
        }
        binding.btnAddPaceStep.setOnClickListener { viewModel.addPaceStep() }
    }

    private fun setupMusicControls() {
        binding.editMediaFolder.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val path = binding.editMediaFolder.text.toString()
                if (path.isNotBlank()) viewModel.setMediaFolder(path)
            }
        }
        binding.btnBrowseFolder.setOnClickListener { folderPicker.launch(null) }
        binding.btnLoadPlaylist.setOnClickListener {
            playlistPicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "*/*"))
        }
        binding.btnAutoAssign.setOnClickListener { viewModel.autoAssignMusic() }
        binding.btnPreview.setOnClickListener {
            if (viewModel.previewSegment.value != -1) viewModel.stopPreview()
            else viewModel.startPreview()
        }
        binding.btnAssignSong.setOnClickListener { showSongPickerDialog() }
    }

    private fun observeViewModel() {
        viewModel.workout.observe(viewLifecycleOwner) { w ->
            binding.editWorkoutName.setText(w.name)
            binding.timelineView.segments = w.segments
            val idx = viewModel.selectedIndex.value ?: -1
            if (idx >= 0) updateEditorPanel(idx)
        }

        viewModel.selectedIndex.observe(viewLifecycleOwner) { idx ->
            binding.timelineView.selectedIndex = idx
            updateEditorPanel(idx)
        }

        viewModel.previewSegment.observe(viewLifecycleOwner) { idx ->
            binding.timelineView.activeSegmentIndex = idx
            binding.btnPreview.text = if (idx >= 0) "Stop Preview" else "Preview"
        }

        viewModel.isBusy.observe(viewLifecycleOwner) { busy ->
            binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        }

        viewModel.message.observe(viewLifecycleOwner) { msg ->
            if (msg != null) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateEditorPanel(idx: Int) {
        val w   = viewModel.workout.value ?: return
        val seg = w.segments.getOrNull(idx)

        binding.layoutSegmentEditor.visibility = if (seg != null) View.VISIBLE else View.GONE
        if (seg == null) return

        isUpdatingUi = true
        try {
            binding.txtSegType.text = if (seg.type == SegmentType.WORKOUT) "WORKOUT SEGMENT" else "RECOVERY SEGMENT"

            // Duration / distance / timeless fields
            binding.layoutDuration.visibility = View.VISIBLE
            binding.layoutDistance.visibility = if (seg.type == SegmentType.WORKOUT) View.VISIBLE else View.GONE
            binding.checkTimeless.visibility  = if (seg.type == SegmentType.RECOVERY) View.VISIBLE else View.GONE

            binding.checkTimeless.isChecked   = seg.isTimeless
            binding.editSegDuration.isEnabled = !seg.isTimeless
            // Skip setText when the field is focused to avoid resetting the cursor mid-type.
            if (!binding.editSegDuration.hasFocus())
                binding.editSegDuration.setText(seg.durationSec?.toLong()?.toString() ?: "")
            if (!binding.editSegDistance.hasFocus())
                binding.editSegDistance.setText(seg.distanceMeters?.toLong()?.toString() ?: "")

            // Pace steps (only for WORKOUT)
            binding.layoutPaceSteps.visibility = if (seg.type == SegmentType.WORKOUT) View.VISIBLE else View.GONE
            refreshPaceSteps(seg)

            // Music assignment
            val songName = seg.musicSongPath?.substringAfterLast('/') ?: "None"
            binding.txtAssignedSong.text = "Song: $songName"
        } finally {
            isUpdatingUi = false
        }
    }

    private fun refreshPaceSteps(seg: WorkoutSegment) {
        binding.containerPaceSteps.removeAllViews()
        seg.targetPaceSteps.forEachIndexed { i, step ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }

            val durEdit = EditText(requireContext()).apply {
                hint = "Duration(s)"
                setText(step.durationSec.toLong().toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFFECEFF1.toInt())
                setBackgroundResource(R.drawable.edit_bg)
            }
            val spmEdit = EditText(requireContext()).apply {
                hint = "SPM"
                setText("%.0f".format(step.strokesPerMin))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFFECEFF1.toInt())
                setBackgroundResource(R.drawable.edit_bg)
            }
            val delBtn = Button(requireContext()).apply {
                text = "✕"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { viewModel.removePaceStep(i) }
            }
            durEdit.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val d = durEdit.text.toString().toDoubleOrNull() ?: return@setOnFocusChangeListener
                    val s = spmEdit.text.toString().toDoubleOrNull() ?: step.strokesPerMin
                    viewModel.updatePaceStep(i, d, s)
                }
            }
            spmEdit.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val d = durEdit.text.toString().toDoubleOrNull() ?: step.durationSec
                    val s = spmEdit.text.toString().toDoubleOrNull() ?: return@setOnFocusChangeListener
                    viewModel.updatePaceStep(i, d, s)
                }
            }
            row.addView(durEdit)
            row.addView(spmEdit)
            row.addView(delBtn)
            binding.containerPaceSteps.addView(row)
        }
    }

    private fun showSongPickerDialog() {
        val songs = viewModel.songs.value ?: emptyList()
        if (songs.isEmpty()) {
            Toast.makeText(requireContext(), "No songs loaded. Set media folder first.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = songs.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Song")
            .setItems(names) { _, which ->
                val song = songs[which]
                viewModel.assignSongToSelected(song)
                // Use full song duration as end if no explicit cut
                if (song.durationMs > 0) viewModel.assignSongTimingToSelected(0L, song.durationMs)
            }
            .show()
    }

    private fun showLoadDialog() {
        viewModel.loadSavedWorkouts { workouts ->
            if (_binding == null) return@loadSavedWorkouts
            if (workouts.isEmpty()) {
                Toast.makeText(requireContext(), "No saved workouts", Toast.LENGTH_SHORT).show()
                return@loadSavedWorkouts
            }
            val names = workouts.map { it.name }.toTypedArray()
            var selectedIdx = 0
            AlertDialog.Builder(requireContext())
                .setTitle("Saved Workouts")
                .setSingleChoiceItems(names, 0) { _, which -> selectedIdx = which }
                .setPositiveButton("Load") { _, _ ->
                    viewModel.load(workouts[selectedIdx].id)
                }
                .setNeutralButton("Delete") { _, _ ->
                    val name = workouts[selectedIdx].name
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete \"$name\"?")
                        .setPositiveButton("Delete") { _, _ ->
                            viewModel.deleteWorkout(workouts[selectedIdx].id)
                            Toast.makeText(requireContext(), "\"$name\" deleted", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
