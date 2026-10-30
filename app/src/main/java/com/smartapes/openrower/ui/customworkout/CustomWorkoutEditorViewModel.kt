package com.smartapes.openrower.ui.customworkout

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.smartapes.openrower.audio.MusicManager
import com.smartapes.openrower.audio.SongInfo
import com.smartapes.openrower.audio.WorkoutMusicPlayer
import com.smartapes.openrower.db.CustomWorkoutRepository
import com.smartapes.openrower.model.CustomWorkout
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.TargetPaceStep
import com.smartapes.openrower.model.WorkoutSegment
import kotlinx.coroutines.launch

class CustomWorkoutEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repo        = CustomWorkoutRepository(application)
    private val musicMgr    = MusicManager()
    val musicPlayer         = WorkoutMusicPlayer()

    private val _workout    = MutableLiveData(CustomWorkout())
    val workout: LiveData<CustomWorkout> = _workout

    private val _selectedIdx = MutableLiveData(-1)
    val selectedIndex: LiveData<Int> = _selectedIdx

    private val _savedWorkouts = MutableLiveData<List<CustomWorkout>>(emptyList())
    val savedWorkouts: LiveData<List<CustomWorkout>> = _savedWorkouts

    private val _songs      = MutableLiveData<List<SongInfo>>(emptyList())
    val songs: LiveData<List<SongInfo>> = _songs

    private val _isBusy     = MutableLiveData(false)
    val isBusy: LiveData<Boolean> = _isBusy

    private val _message    = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    private val _previewSegment = MutableLiveData(-1)
    val previewSegment: LiveData<Int> = _previewSegment

    init {
        loadSavedWorkouts()
    }

    // ── Workout meta ──────────────────────────────────────────────────────

    fun setName(name: String) {
        _workout.value = _workout.value!!.copy(name = name)
    }

    fun setMediaFolder(path: String) {
        _workout.value = _workout.value!!.copy(mediaFolderPath = path.ifBlank { null })
        loadLibrary()
    }

    fun setRecoveryMusicPath(path: String) {
        _workout.value = _workout.value!!.copy(recoveryMusicPath = path.ifBlank { null })
    }

    // ── Segment list operations ───────────────────────────────────────────

    fun addWorkoutSegment() {
        val seg = WorkoutSegment(type = SegmentType.WORKOUT, durationSec = 120.0)
        val segs = _workout.value!!.segments + seg
        _workout.value = _workout.value!!.copy(segments = segs)
        _selectedIdx.value = segs.size - 1
    }

    fun addRecoverySegment() {
        val seg = WorkoutSegment(type = SegmentType.RECOVERY, durationSec = 60.0)
        val segs = _workout.value!!.segments + seg
        _workout.value = _workout.value!!.copy(segments = segs)
        _selectedIdx.value = segs.size - 1
    }

    fun removeSegment(index: Int) {
        val segs = _workout.value!!.segments.toMutableList()
        if (index !in segs.indices) return
        segs.removeAt(index)
        _workout.value = _workout.value!!.copy(segments = segs)
        _selectedIdx.value = if (segs.isEmpty()) -1 else (index - 1).coerceAtLeast(0)
    }

    fun moveSegment(from: Int, to: Int) {
        val segs = _workout.value!!.segments.toMutableList()
        if (from !in segs.indices || to !in segs.indices) return
        val item = segs.removeAt(from)
        segs.add(to, item)
        _workout.value = _workout.value!!.copy(segments = segs)
        _selectedIdx.value = to
    }

    fun selectSegment(index: Int) { _selectedIdx.value = index }

    // ── Segment editing ───────────────────────────────────────────────────

    fun updateSelectedSegment(update: (WorkoutSegment) -> WorkoutSegment) {
        val idx = _selectedIdx.value ?: return
        val segs = _workout.value!!.segments.toMutableList()
        if (idx !in segs.indices) return
        segs[idx] = update(segs[idx])
        _workout.value = _workout.value!!.copy(segments = segs)
    }

    fun setSegmentDuration(durationSec: Double?) {
        updateSelectedSegment { it.copy(durationSec = durationSec, distanceMeters = null) }
    }

    fun setSegmentDistance(distanceMeters: Double?) {
        updateSelectedSegment { it.copy(distanceMeters = distanceMeters, durationSec = null) }
    }

    fun setSegmentTimeless(timeless: Boolean) {
        updateSelectedSegment {
            it.copy(durationSec = if (timeless) null else 60.0)
        }
    }

    fun addPaceStep() {
        updateSelectedSegment { seg ->
            val step = TargetPaceStep(durationSec = 60.0, strokesPerMin = 20.0)
            seg.copy(targetPaceSteps = seg.targetPaceSteps + step)
        }
    }

    fun removePaceStep(stepIndex: Int) {
        updateSelectedSegment { seg ->
            val steps = seg.targetPaceSteps.toMutableList()
            if (stepIndex in steps.indices) steps.removeAt(stepIndex)
            seg.copy(targetPaceSteps = steps)
        }
    }

    fun updatePaceStep(stepIndex: Int, durationSec: Double, spm: Double) {
        updateSelectedSegment { seg ->
            val steps = seg.targetPaceSteps.toMutableList()
            if (stepIndex in steps.indices)
                steps[stepIndex] = TargetPaceStep(durationSec, spm)
            seg.copy(targetPaceSteps = steps)
        }
    }

    fun assignSongToSelected(song: SongInfo) {
        updateSelectedSegment { it.copy(musicSongPath = song.path) }
    }

    fun assignSongTimingToSelected(startMs: Long, endMs: Long) {
        updateSelectedSegment { it.copy(musicStartMs = startMs, musicEndMs = endMs) }
    }

    fun createSegmentFromSong(song: SongInfo) {
        val seg = musicMgr.createSegmentFromSong(song)
        val segs = _workout.value!!.segments + seg
        _workout.value = _workout.value!!.copy(segments = segs)
        _selectedIdx.value = segs.size - 1
    }

    // ── Music library ─────────────────────────────────────────────────────

    fun loadLibrary() {
        val w = _workout.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val library = musicMgr.loadLibrary(w.mediaFolderPath, null)
            _songs.value = library
            _isBusy.value = false
        }
    }

    fun loadFromPlaylist(playlistPath: String) {
        val w = _workout.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val library = musicMgr.loadLibrary(w.mediaFolderPath, playlistPath)
            _songs.value = library
            _isBusy.value = false
        }
    }

    fun autoAssignMusic() {
        val w = _workout.value ?: return
        val library = _songs.value ?: return
        if (library.isEmpty()) { _message.value = "No songs loaded. Set a media folder first."; return }
        viewModelScope.launch {
            _isBusy.value = true
            val updated = musicMgr.autoAssign(w, library)
            _workout.value = updated
            _isBusy.value = false
            _message.value = "Music auto-assigned to all segments."
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────

    fun startPreview() {
        val segs = _workout.value?.segments ?: return
        musicPlayer.preview(segs, viewModelScope,
            onSegment = { _previewSegment.postValue(it) },
            onDone    = { _previewSegment.postValue(-1) }
        )
    }

    fun stopPreview() {
        musicPlayer.stop()
        _previewSegment.value = -1
    }

    // ── Persist ───────────────────────────────────────────────────────────

    fun save(onDone: (id: Long) -> Unit) {
        val w = _workout.value ?: return
        viewModelScope.launch {
            val id = repo.save(w)
            _workout.value = w.copy(id = id)
            _message.value = "Workout saved."
            onDone(id)
        }
    }

    fun load(id: Long) {
        viewModelScope.launch {
            val w = repo.load(id) ?: return@launch
            _workout.value = w
            _selectedIdx.value = if (w.segments.isNotEmpty()) 0 else -1
            loadLibrary()
        }
    }

    fun loadSavedWorkouts(onLoaded: ((List<CustomWorkout>) -> Unit)? = null) {
        viewModelScope.launch {
            val workouts = repo.getAll()
            _savedWorkouts.value = workouts
            onLoaded?.invoke(workouts)
        }
    }

    fun deleteWorkout(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
            loadSavedWorkouts()
        }
    }

    // ── Settings helper ───────────────────────────────────────────────────

    fun getSavedMediaFolder(): String {
        return getApplication<Application>()
            .getSharedPreferences("openrower", Context.MODE_PRIVATE)
            .getString("media_folder", "") ?: ""
    }

    override fun onCleared() {
        super.onCleared()
        musicPlayer.release()
    }
}
