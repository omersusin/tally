package com.tally.steps.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One raw GPS fix for the active workout session. Times/accuracy feed the tracker's filter. */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long)

/**
 * Process-wide pipe between [StepCounterService] (which owns the location
 * listener inside a location-type foreground service, so fixes keep flowing
 * with the screen off) and the workout ViewModel (which owns the
 * [WorkoutTracker] filter). No lifecycle, no persistence — a workout is a
 * live session; process death ends it, honestly.
 */
object WorkoutGps {
    private val _fixes = MutableSharedFlow<GpsFix>(extraBufferCapacity = 64)
    val fixes: SharedFlow<GpsFix> = _fixes.asSharedFlow()

    /** True while the service is actively tracking a workout route. Never throws. */
    @Volatile
    var active: Boolean = false
        private set

    fun setActive(v: Boolean) {
        active = v
    }

    fun emit(fix: GpsFix) {
        _fixes.tryEmit(fix)
    }
}
