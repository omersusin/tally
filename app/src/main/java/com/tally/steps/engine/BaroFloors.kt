package com.tally.steps.engine

import android.content.Context
import android.content.pm.PackageManager

/**
 * Barometer floor-climb detector. Fed pressure events from the service
 * (SENSOR_DELAY_NORMAL, no wakeups, batch-by-events); returns true from
 * [onPressure] when one floor completes. Persistence lives in
 * [StepRepository.addFloors] — this class holds no DB, no prefs, no threads.
 *
 * Physics: ~1 hPa ≈ 8 m near sea level, so one 3 m storey is only ~0.36 hPa —
 * far too close to weather drift and sensor noise to count honestly. The
 * [FLOOR_DROP_HPA] = 1.2 hPa bar is deliberately conservative (≈ a full
 * stairwell of 2–3 storeys, or one tall floor): it under-counts short climbs
 * rather than crediting phantom floors. Under-count chosen; never phantom.
 *
 * Algorithm: slow EMA tracks ambient pressure (absorbs weather drift); a
 * sustained drop ≥ threshold opens a climb; when the reading plateaus
 * (stable within [PLATEAU_HPA] for [PLATEAU_EVENTS] consecutive events =
 * the landing), one floor is emitted and the baseline re-anchors at the new
 * level. Stairs show a stepped drop, elevators a smooth ramp — both cross
 * the threshold, so BOTH COUNT AS A FLOOR. Limitation, stated plainly:
 * we cannot tell stairs from elevator from pressure alone.
 *
 * Devices without TYPE_PRESSURE hide the whole feature: check
 * [hasBarometer] before registering the listener or showing any floors UI.
 */
class BaroFloors {

    private var baseline: Float? = null
    private var inClimb = false
    private var climbLow = Float.MAX_VALUE
    private var stableCount = 0
    private var last: Float? = null

    /**
     * One pressure reading in hPa. Returns true exactly once per completed
     * floor (landing plateau reached). Pure function of the event stream —
     * reboot/process death loses the in-flight climb and under-counts it.
     */
    fun onPressure(hpa: Float, nowMs: Long): Boolean {
        if (!hpa.isFinite() || hpa <= 0f) return false
        val prev = last
        last = hpa
        val base = baseline
        if (base == null) {
            baseline = hpa
            return false
        }
        if (!inClimb) {
            if (base - hpa >= FLOOR_DROP_HPA) {
                // Sustained drop ≈ climbing. One event could be a gust/door
                // slam, so require the drop to persist: only open the climb
                // when the previous event was already falling too.
                val falling = prev != null && prev <= base - FLOOR_DROP_HPA / 2
                if (falling || base - hpa >= FLOOR_DROP_HPA * 2) {
                    inClimb = true
                    climbLow = hpa
                    stableCount = 0
                }
            } else {
                // Ambient tracking: slow EMA absorbs weather drift so a storm
                // front never looks like a climb. Never moves during a climb.
                baseline = base + (hpa - base) * DRIFT_ALPHA
            }
            return false
        }
        // In climb: track the low, watch for the landing plateau.
        if (hpa < climbLow) {
            climbLow = hpa
            stableCount = 0
        } else if (prev != null && kotlin.math.abs(hpa - prev) <= PLATEAU_HPA) {
            stableCount++
        } else {
            stableCount = 0
        }
        // Abort: pressure rose back near baseline (descended again, or noise)
        // — no floor, re-anchor, no credit.
        if (hpa >= base - FLOOR_DROP_HPA / 2) {
            inClimb = false
            baseline = base + (hpa - base) * DRIFT_ALPHA
            return false
        }
        if (stableCount >= PLATEAU_EVENTS) {
            inClimb = false
            baseline = climbLow
            stableCount = 0
            return true
        }
        return false
    }

    /** Day rollover or sensor gap: drop the in-flight climb (under-counts it). */
    fun reset() {
        baseline = null
        inClimb = false
        stableCount = 0
        last = null
    }

    companion object {
        /** Sustained drop that counts as a climb. Conservative by design (see above). */
        const val FLOOR_DROP_HPA = 1.2f

        /** Landing = this-stable for this many consecutive events. */
        const val PLATEAU_HPA = 0.15f
        const val PLATEAU_EVENTS = 6

        /** Ambient drift tracking rate; slow enough that climbs never leak into baseline. */
        const val DRIFT_ALPHA = 0.02f

        /** False when the device has no pressure sensor: hide the whole feature. */
        fun hasBarometer(context: Context): Boolean =
            runCatching {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)
            }.getOrDefault(false)
    }
}
