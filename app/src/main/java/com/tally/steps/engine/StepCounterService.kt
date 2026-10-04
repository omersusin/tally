package com.tally.steps.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tally.steps.MainActivity
import com.tally.steps.widget.TallyWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground step service (health type): feeds TYPE_STEP_COUNTER into
 * [StepRepository] and keeps a persistent notification with live count +
 * pause/resume action.
 */
class StepCounterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private lateinit var repo: StepRepository
    private var listener: SensorEventListener? = null
    private var dateRegistered = false
    /**
     * Ordered sensor pipeline: the listener only enqueues (never launches work),
     * ONE consumer below drains in arrival order under the repo mutex. UNLIMITED
     * so step counts are never dropped; per-event coroutines would race and cause
     * phantom reboot detection + double-count.
     */
    private val sensorQueue = Channel<Long>(Channel.UNLIMITED)
    private var sensorJob: Job? = null

    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { runCatching { repo.onDateOrZoneChanged() } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = StepRepository.getInstance(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                scope.launch { runCatching { repo.setPaused(true) } }
                return START_STICKY
            }
            ACTION_RESUME -> {
                scope.launch { runCatching { repo.setPaused(false) } }
                return START_STICKY
            }
        }
        // Health-type FGS needs ACTIVITY_RECOGNITION on API 29+; without it the
        // start throws, so stop early — S01 grants the permission first.
        if (!hasActivityRecognition()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // No step-counter hardware: never run a permanent 0-step FGS.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundTyped(buildSnapshot(0, 8000, false))
        registerSensor()
        if (!dateRegistered) {
            dateRegistered = true
            ContextCompat.registerReceiver(
                this,
                dateReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        if (collectJob == null) {
            collectJob = scope.launch {
                combine(repo.today(), repo.paused()) { day, paused -> Triple(day.steps, day.goal, paused) }
                    .distinctUntilChanged()
                    .collect { (steps, goal, paused) ->
                        // Live widget push on each distinct change (Glance reads
                        // stored state, never blocks on the DB). Hourly
                        // SyncWorker remains the safety net for missed pushes.
                        runCatching { TallyWidget.requestUpdate(this@StepCounterService) }
                        // POST_NOTIFICATIONS is runtime-revocable: skip the update
                        // when denied (counting continues, only the shade goes stale).
                        val allowed = ContextCompat.checkSelfPermission(
                            this@StepCounterService,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (allowed) {
                            runCatching {
                                NotificationManagerCompat.from(this@StepCounterService)
                                    .notify(NOTIF_ID, buildSnapshot(steps, goal, paused))
                            }
                        }
                    }
            }
        }
        if (sensorJob == null) {
            sensorJob = scope.launch {
                for (v in sensorQueue) runCatching { repo.onSensor(v) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dateReceiver) }
        listener?.let {
            (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(it)
        }
        listener = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundTyped(notif: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure {
            stopSelf()
        }
    }

    private fun registerSensor() {
        if (listener != null) return
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                sensorQueue.trySend(e.values[0].toLong())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // SENSOR_DELAY_UI: hardware batching does the work; faster rates only burn battery.
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        listener = l
    }

    private fun hasActivityRecognition(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Step count", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(), Intent(this, StepCounterService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildSnapshot(steps: Int, goal: Int, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = if (paused) ACTION_RESUME else ACTION_PAUSE
        val progress = if (goal > 0) ((steps * 100L) / goal).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (paused) "Paused — %,d steps".format(steps) else "%,d steps today".format(steps))
            .setContentText("%d%% of %,d goal".format(progress, goal))
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", actionIntent(toggle))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    companion object {
        const val ACTION_PAUSE = "com.tally.steps.action.PAUSE"
        const val ACTION_RESUME = "com.tally.steps.action.RESUME"
        const val CHANNEL_ID = "tally_steps"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StepCounterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepCounterService::class.java))
        }
    }
}
