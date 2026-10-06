package com.tally.steps.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tally.steps.data.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Post-boot: invalidate baseline (forces rebaseline, never phantom steps), restart FGS, reschedule hourly sync. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // LOCKED_BOOT covers file-encrypted phones (counts resume after unlock);
        // QUICKBOOT covers Xiaomi/BBK fast-boot broadcasts.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = context.applicationContext
                val prefs = PrefsStore.getInstance(app)
                prefs.resetBaseline()
                prefs.setBootId(System.currentTimeMillis())
                if (!StepRepository.getInstance(app).paused().first()) {
                    runCatching { StepCounterService.start(app) }
                }
                SyncWorker.schedule(app)
            } finally {
                pending.finish()
            }
        }
    }
}
