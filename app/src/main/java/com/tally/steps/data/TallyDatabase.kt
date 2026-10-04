package com.tally.steps.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(entities = [Day::class, Workout::class], version = 2, exportSchema = false)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun dayDao(): DayDao
    abstract fun workoutDao(): WorkoutDao

    companion object {
        const val NAME = "tally.db"

        /**
         * Real migration path: table create only, no data loss.
         * Destructive fallback is intentionally never used.
         */
        val MIGRATIONS = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `workouts` (" +
                            "`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                            "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, " +
                            "`steps` INTEGER NOT NULL, `distanceM` REAL NOT NULL, " +
                            "`pausedMs` INTEGER NOT NULL, `gpsPolyline` TEXT, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_workouts_startMs` " +
                            "ON `workouts` (`startMs`)",
                    )
                }
            },
        )

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
