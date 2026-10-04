package com.tally.steps.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS/step workout session. Steps are attributed from the hardware
 * counter delta between start/end (minus paused windows) — the workout never
 * writes Day.manualDelta, so it can never double-count today's total.
 */
@Entity(tableName = "workouts", indices = [Index("startMs")])
data class Workout(
    @PrimaryKey val id: String,
    /** walk | run | hike | ride | treadmill */
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val steps: Int,
    val distanceM: Float,
    val pausedMs: Long,
    /** "lat,lon;lat,lon;…" or null when step-only (denied GPS / treadmill). */
    val gpsPolyline: String?,
)
