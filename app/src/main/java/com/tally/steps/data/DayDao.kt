package com.tally.steps.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {
    @Upsert
    suspend fun upsert(day: Day)

    @Query("SELECT * FROM days WHERE epochDay = :epochDay")
    fun dayFlow(epochDay: Long): Flow<Day?>

    @Query("SELECT * FROM days WHERE epochDay = :epochDay")
    suspend fun getDay(epochDay: Long): Day?

    @Query("SELECT * FROM days ORDER BY epochDay DESC LIMIT :limit")
    fun historyFlow(limit: Int): Flow<List<Day>>
}
