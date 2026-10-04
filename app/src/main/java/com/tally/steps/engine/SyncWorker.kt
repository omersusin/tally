package com.tally.steps.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tally.steps.widget.TallyWidget
import java.util.concurrent.TimeUnit

/** Hourly: flush/rollover + HC sync. No battery constraint (rollover must run even on low battery). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val repo = StepRepository.getInstance(applicationContext)
        repo.rolloverCheck()
        repo.syncHealth()
        runCatching { TallyWidget.update(applicationContext) }
        Result.success()
    }.getOrDefault(Result.retry())

    companion object {
        private const val UNIQUE = "tally_hourly_sync"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }
    }
}
