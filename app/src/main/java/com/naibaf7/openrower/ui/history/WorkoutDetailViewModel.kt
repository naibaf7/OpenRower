package com.naibaf7.openrower.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.naibaf7.openrower.db.SplitEntity
import com.naibaf7.openrower.db.WorkoutEntity
import com.naibaf7.openrower.db.WorkoutRepository
import kotlinx.coroutines.launch

class WorkoutDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WorkoutRepository(application)

    private val _workout = MutableLiveData<WorkoutEntity?>()
    val workout: LiveData<WorkoutEntity?> = _workout

    private val _splits = MutableLiveData<List<SplitEntity>>(emptyList())
    val splits: LiveData<List<SplitEntity>> = _splits

    private val _deleted = MutableLiveData(false)
    val deleted: LiveData<Boolean> = _deleted

    private var loaded = false

    fun load(workoutId: Long) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            repository.getAllWorkouts().collect { list ->
                val w = list.firstOrNull { it.id == workoutId }
                _workout.postValue(w)
                if (w != null && _splits.value.isNullOrEmpty()) {
                    _splits.postValue(repository.getSplits(w.id))
                }
            }
        }
    }

    fun deleteWorkout() {
        val w = _workout.value ?: return
        viewModelScope.launch {
            repository.deleteWorkout(w.id)
            _deleted.postValue(true)
        }
    }
}
