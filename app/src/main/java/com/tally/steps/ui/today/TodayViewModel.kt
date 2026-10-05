package com.tally.steps.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tally.steps.TallyApp
import com.tally.steps.data.Day
import com.tally.steps.engine.StepRepository
import com.tally.steps.engine.BaroFloors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    /** Floors climbed today; 0 also means "no barometer" — UI gates on hasBarometer. */
    val floors: StateFlow<Int> = repository.floorsToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val hasBarometer: Boolean = BaroFloors.hasBarometer(getApplication())

    /** Quiet veto note, or null when there is nothing to say. */
    val vetoNote: StateFlow<String?> = combine(
        repository.vetoActive(),
        repository.vetoIgnoredToday(),
    ) { active, ignored ->
        when {
            active -> "Not counting right now — driving or cycling."
            ignored > 0 -> "$ignored steps ignored while driving or cycling today."
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
