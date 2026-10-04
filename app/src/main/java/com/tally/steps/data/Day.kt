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
)
