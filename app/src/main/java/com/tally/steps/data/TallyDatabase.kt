package com.tally.steps.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(entities = [Day::class], version = 1, exportSchema = false)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun dayDao(): DayDao

    companion object {
        const val NAME = "tally.db"

        /**
         * Real migration path: append Migration(1 -> 2)… here as schema evolves.
         * Destructive fallback is intentionally never used.
         */
        val MIGRATIONS = emptyArray<Migration>()

        @Volatile
        private var instance: TallyDatabase? = null

        fun getInstance(context: Context): TallyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TallyDatabase::class.java,
                    NAME,
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
