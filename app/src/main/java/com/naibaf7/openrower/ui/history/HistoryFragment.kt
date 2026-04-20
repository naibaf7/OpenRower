package com.naibaf7.openrower.ui.history

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.naibaf7.openrower.R
import com.naibaf7.openrower.databinding.FragmentHistoryBinding
import java.util.Calendar
import java.util.TimeZone

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: WorkoutListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply system bar insets: toolbar absorbs status bar at top,
        // RecyclerView absorbs navigation bar at bottom.
        val basePadH = (12 * resources.displayMetrics.density).toInt()
        val baseRecyclerPadBottom = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutToolbar.setPadding(basePadH, bars.top, basePadH, 0)
            binding.recyclerWorkouts.setPadding(0, 0, 0, bars.bottom + baseRecyclerPadBottom)
            insets
        }

        adapter = WorkoutListAdapter { workout ->
            findNavController().navigate(
                R.id.action_history_to_detail,
                bundleOf("workoutId" to workout.id)
            )
        }

        binding.recyclerWorkouts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter  = this@HistoryFragment.adapter
        }

        binding.btnPrevMonth.setOnClickListener { viewModel.prevMonth() }
        binding.btnNextMonth.setOnClickListener { viewModel.nextMonth() }

        viewModel.workouts.observe(viewLifecycleOwner) { workouts ->
            adapter.submitWorkouts(workouts)
        }

        viewModel.calendarMonth.observe(viewLifecycleOwner) { (month, year) ->
            val cal = Calendar.getInstance()
            cal.set(year, month, 1)
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                                 "Jul","Aug","Sep","Oct","Nov","Dec")
            binding.txtCalendarMonth.text = "${months[month]} $year"

            val days = viewModel.workoutDays.value ?: emptySet()
            buildCalendarGrid(month, year, days)
        }

        viewModel.workoutDays.observe(viewLifecycleOwner) { days ->
            val (month, year) = viewModel.calendarMonth.value ?: return@observe
            buildCalendarGrid(month, year, days)
        }
    }

    private fun buildCalendarGrid(month: Int, year: Int, workoutDays: Set<Long>) {
        val grid = binding.gridCalendar
        grid.removeAllViews()
        grid.columnCount = 7

        val dayNames = arrayOf("Su","Mo","Tu","We","Th","Fr","Sa")
        val dp = resources.displayMetrics.density

        // Header row
        dayNames.forEach { name ->
            val tv = TextView(requireContext()).apply {
                text = name
                setTextColor(Color.parseColor("#546E7A"))
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(1, 1, 1, 4)
                }
            }
            grid.addView(tv)
        }

        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(year, month, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1  // 0=Sun
        val daysInMonth    = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val todayCal = Calendar.getInstance(TimeZone.getDefault())
        val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)
        val todayMon = todayCal.get(Calendar.MONTH)
        val todayYr  = todayCal.get(Calendar.YEAR)

        // Empty cells before day 1
        repeat(firstDayOfWeek) {
            val spacer = View(requireContext()).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    height = (32 * dp).toInt()
                }
            }
            grid.addView(spacer)
        }

        for (day in 1..daysInMonth) {
            cal.set(year, month, day)
            val dayEpoch = cal.timeInMillis / 86_400_000L
            val hasWorkout = dayEpoch in workoutDays
            val isToday = (day == todayDay && month == todayMon && year == todayYr)

            val tv = TextView(requireContext()).apply {
                text = day.toString()
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(when {
                    isToday      -> Color.parseColor("#4FC3F7")
                    hasWorkout   -> Color.parseColor("#ECEFF1")
                    else         -> Color.parseColor("#546E7A")
                })
                setBackgroundColor(when {
                    isToday    -> Color.parseColor("#1A2E45")
                    hasWorkout -> Color.parseColor("#0F2030")
                    else       -> Color.TRANSPARENT
                })
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    height = (32 * dp).toInt()
                    setMargins(1, 1, 1, 1)
                }
                if (hasWorkout) {
                    // Add dot indicator via foreground drawable substitute — use a simple tag
                    tag = "has_workout"
                }
                setOnClickListener {
                    // Scroll to first workout on/after this day
                    val pos = adapter.positionForDay(dayEpoch)
                    (binding.recyclerWorkouts.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(pos, 0)
                }
            }
            grid.addView(tv)
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
