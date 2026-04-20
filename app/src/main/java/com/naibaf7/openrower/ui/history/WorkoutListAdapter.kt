package com.naibaf7.openrower.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.naibaf7.openrower.R
import com.naibaf7.openrower.db.WorkoutEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Multi-type RecyclerView adapter: month headers + workout rows. */
class WorkoutListAdapter(
    private val onWorkoutClick: (WorkoutEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val label: String) : Item()
        data class Row(val workout: WorkoutEntity) : Item()
    }

    private var items: List<Item> = emptyList()

    fun submitWorkouts(workouts: List<WorkoutEntity>) {
        val monthFmt  = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val grouped   = workouts.groupBy { monthFmt.format(Date(it.dateMs)) }
        val newItems  = mutableListOf<Item>()
        // workouts already sorted DESC; preserve order
        for ((month, rows) in grouped) {
            newItems.add(Item.Header(month))
            rows.forEach { newItems.add(Item.Row(it)) }
        }
        items = newItems
        notifyDataSetChanged()
    }

    /** Returns the RecyclerView position of the first workout on or after the given day. */
    fun positionForDay(dayEpoch: Long): Int {
        val dayMs = dayEpoch * 86_400_000L
        items.forEachIndexed { i, item ->
            if (item is Item.Row && item.workout.dateMs >= dayMs) return i
        }
        return 0
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Item.Header -> 0
        is Item.Row    -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val v = inflater.inflate(R.layout.item_month_header, parent, false)
            HeaderVH(v)
        } else {
            val v = inflater.inflate(R.layout.item_workout, parent, false)
            WorkoutVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderVH).bind(item.label)
            is Item.Row    -> (holder as WorkoutVH).bind(item.workout, onWorkoutClick)
        }
    }

    override fun getItemCount() = items.size

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val txt = view.findViewById<TextView>(R.id.txt_month_header)
        fun bind(label: String) { txt.text = label }
    }

    class WorkoutVH(view: View) : RecyclerView.ViewHolder(view) {
        private val txtDate     = view.findViewById<TextView>(R.id.txt_workout_date)
        private val txtDuration = view.findViewById<TextView>(R.id.txt_workout_duration)
        private val txtDist     = view.findViewById<TextView>(R.id.txt_workout_dist)
        private val txtMode     = view.findViewById<TextView>(R.id.txt_workout_mode)

        fun bind(w: WorkoutEntity, onClick: (WorkoutEntity) -> Unit) {
            val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            txtDate.text     = dateFmt.format(Date(w.dateMs))
            txtDuration.text = formatTime(w.totalDurationSec)
            txtDist.text     = "%.0f m".format(w.totalMeters)
            txtMode.text     = w.modeName.lowercase().replaceFirstChar { it.uppercase() }
            itemView.setOnClickListener { onClick(w) }
        }

        private fun formatTime(sec: Double): String {
            val t = sec.toLong().coerceAtLeast(0L)
            val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
            return if (h > 0) "$h:%02d:%02d".format(m, s) else "$m:%02d".format(s)
        }
    }
}
