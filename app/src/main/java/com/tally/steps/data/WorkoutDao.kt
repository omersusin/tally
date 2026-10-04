package com.tally.steps.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workout: Workout)

    @Query("SELECT * FROM workouts ORDER BY startMs DESC")
    fun workoutsFlow(): Flow<List<Workout>>

    @Delete
    suspend fun delete(workout: Workout)
}
