package com.naibaf7.openrower.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.naibaf7.openrower.db.WorkoutEntity
import com.naibaf7.openrower.db.WorkoutRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WorkoutRepository(application)

    private val _workouts = MutableLiveData<List<WorkoutEntity>>(emptyList())
    val workouts: LiveData<List<WorkoutEntity>> = _workouts

    // Days-since-epoch that have at least one workout (for calendar markers)
    private val _workoutDays = MutableLiveData<Set<Long>>(emptySet())
    val workoutDays: LiveData<Set<Long>> = _workoutDays

    // Currently displayed month/year in the calendar
    private val _calendarMonth = MutableLiveData<Pair<Int, Int>>()   // month (0-based), year
    val calendarMonth: LiveData<Pair<Int, Int>> = _calendarMonth

    init {
        val cal = Calendar.getInstance()
        _calendarMonth.value = Pair(cal.get(Calendar.MONTH), cal.get(Calendar.YEAR))
        viewModelScope.launch {
            repository.getAllWorkouts().collectLatest { list ->
                _workouts.postValue(list)
                val days = list.map { it.dateMs / 86_400_000L }.toHashSet()
                _workoutDays.postValue(days)
            }
        }
    }

    fun prevMonth() {
        val (m, y) = _calendarMonth.value ?: return
        if (m == 0) _calendarMonth.value = Pair(11, y - 1)
        else        _calendarMonth.value = Pair(m - 1, y)
    }

    fun nextMonth() {
        val (m, y) = _calendarMonth.value ?: return
        if (m == 11) _calendarMonth.value = Pair(0, y + 1)
        else         _calendarMonth.value = Pair(m + 1, y)
    }
}
