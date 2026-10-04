package com.tally.steps.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tally.steps.TallyApp
import com.tally.steps.data.Day
import com.tally.steps.engine.StepRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Today state. Sensor-missing comes from PackageManager
 * ([StepRepository.hasSensor]), never from a Day.source sentinel —
 * the engine never writes "missing"/"unavailable"/"none".
 */
class TodayViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: StepRepository = (application as TallyApp).repository

    val day: StateFlow<Day?> = repository.today()
        .map { it as Day? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val paused: StateFlow<Boolean> = repository.paused()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the device reports no step sensor. Counts stay honestly at 0. */
    val sensorMissing: StateFlow<Boolean> = repository.hasSensor()
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setGoal(steps: Int) {
        viewModelScope.launch { repository.setGoal(steps.coerceIn(1_000, 50_000)) }
    }

    /** Manual fixes touch ONLY manualDelta — never the sensor baseline. */
    fun nudgeManual(delta: Int) {
        viewModelScope.launch { repository.adjustManual(delta) }
    }

    fun clearManual() {
        viewModelScope.launch { repository.clearManual() }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { repository.setPaused(paused) }
    }
}
