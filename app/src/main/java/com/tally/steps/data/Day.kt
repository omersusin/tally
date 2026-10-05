package com.tally.steps.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Offline source of truth: one row per calendar day, keyed by epoch day. */
@Entity(tableName = "days", indices = [Index("updatedAt")])
data class Day(
    @PrimaryKey val epochDay: Long,
    val steps: Int,
    val distanceM: Float,
    val kcal: Float,
    val activeMin: Int,
    val goal: Int,
    /** User correction only; sensor math never writes here. */
    val manualDelta: Int,
    val source: String,
    val updatedAt: Long,
    /**
     * Rest day: a past missed day the user marked to preserve a streak.
     * Defaults false so every existing constructor call keeps compiling and
     * every pre-v3 row reads as "not rest" until Migration(2, 3) backfills 0.
     */
    val restDay: Boolean = false,
    /**
     * Floors climbed today, from the barometer ([BaroFloors]). Barometer-less
     * devices never write here — 0 both means "none climbed" and "no sensor",
     * so the UI must check BaroFloors.hasBarometer() before showing the row.
     * Appended last so every existing positional constructor call (blank())
     * keeps compiling; pre-v4 rows backfill 0 via Migration(3, 4).
     */
    val floors: Int = 0,
)
