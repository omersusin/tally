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
import android.location.LocationListener
import android.location.LocationManager
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
import kotlinx.coroutines.flow.first
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
    private var baroListener: SensorEventListener? = null
    private val baroFloors = BaroFloors()
    private var dateRegistered = false
    private var vetoRegistered = false
    private var vetoPendingIntent: PendingIntent? = null
    /** Workout route listener (location-type FGS while a session is ACTIVE). */
    private var workoutListener: LocationListener? = null
    /** Activities currently ENTERed per AR transitions; non-empty = veto. Main-thread only. */
    private val vetoActivities = mutableSetOf<Int>()
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

    /**
     * Activity Transition result sink. Parses ENTER/EXIT for IN_VEHICLE and
     * ON_BICYCLE via the same reflected classes (absent = never called, veto
     * stays off). Keeps the ENTERed set and pushes veto = set.isNotEmpty().
     */
    private val vetoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = runCatching {
                val resultClass =
                    Class.forName("com.google.android.gms.location.ActivityTransitionResult")
                val hasResult = resultClass.getMethod("hasResult", Intent::class.java)
                if (!(hasResult.invoke(null, intent) as Boolean)) return@runCatching null
                val result =
                    resultClass.getMethod("extractResult", Intent::class.java).invoke(null, intent)
                        ?: return@runCatching null
                @Suppress("UNCHECKED_CAST")
                val events =
                    resultClass.getMethod("getTransitionEvents").invoke(result) as List<*>
                val detectedClass =
                    Class.forName("com.google.android.gms.location.DetectedActivity")
                val inVehicle = detectedClass.getField("IN_VEHICLE").getInt(null)
                val onBicycle = detectedClass.getField("ON_BICYCLE").getInt(null)
                val transitionClass =
                    Class.forName("com.google.android.gms.location.ActivityTransition")
                val enter = transitionClass.getField("ACTIVITY_TRANSITION_ENTER").getInt(null)
                for (e in events) {
                    if (e == null) continue
                    val type = e.javaClass.getMethod("getActivityType").invoke(e) as Int
                    if (type != inVehicle && type != onBicycle) continue
                    val trans = e.javaClass.getMethod("getTransitionType").invoke(e) as Int
                    if (trans == enter) vetoActivities.add(type) else vetoActivities.remove(type)
                }
                vetoActivities.isNotEmpty()
            }.getOrNull() ?: return
            scope.launch { runCatching { repo.setArVeto(active) } }
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
            ACTION_WORKOUT_START -> {
                startWorkoutTracking()
                return START_STICKY
            }
            ACTION_WORKOUT_STOP -> {
                stopWorkoutTracking()
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
        registerBarometer()
        registerVehicleVeto()
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
        if (vetoRegistered) {
            runCatching { unregisterReceiver(vetoReceiver) }
            vetoRegistered = false
        }
        // Best-effort: stop AR callbacks. Absent API = no-op; failure to
        // remove only costs battery, never phantom steps (fresh process =
        // veto off + rebaseline anyway).
        runCatching {
            val pi = vetoPendingIntent ?: return@runCatching
            val arClass = Class.forName("com.google.android.gms.location.ActivityRecognition")
            val client = arClass.getMethod("getClient", Context::class.java).invoke(null, this)
                ?: return@runCatching
            client.javaClass.getMethod("removeActivityTransitionUpdates", PendingIntent::class.java)
                .invoke(client, pi)
        }
        vetoPendingIntent = null
        WorkoutGps.setActive(false)
        workoutListener?.let { l ->
            runCatching { (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l) }
        }
        workoutListener = null
        runCatching {
            val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return@runCatching
            listener?.let { sm.unregisterListener(it) }
            baroListener?.let { sm.unregisterListener(it) }
        }
        listener = null
        baroListener = null
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
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null || e.values.isEmpty()) return
                sensorQueue.trySend(e.values[0].toLong())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // SENSOR_DELAY_UI: hardware batching does the work; faster rates only burn battery.
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        listener = l
    }

    /**
     * Barometer floors: registers TYPE_PRESSURE only when the hardware exists
     * ([BaroFloors.hasBarometer] — whole feature hides otherwise, no permission
     * needed). SENSOR_DELAY_NORMAL = low power; events are cheap EMA math and
     * only a completed floor touches the DB (one upsert per floor, not per
     * event). Feeds [BaroFloors.onPressure]; true = landing reached → credit.
     */
    private fun registerBarometer() {
        if (baroListener != null || !BaroFloors.hasBarometer(this)) return
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null || e.values.isEmpty()) return
                val done = baroFloors.onPressure(e.values[0], System.currentTimeMillis())
                if (done) scope.launch { runCatching { repo.addFloors(1) } }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        baroListener = l
    }

    /**
     * Workout route tracking inside the service (not the composable) so the
     * route keeps recording with the screen off. Upgrades this service to
     * health|location while ACTIVE, downgrades on stop. No location grant →
     * no listener: the workout stays honestly step-only.
     */
    private fun startWorkoutTracking() {
        if (workoutListener != null) return
        scope.launch {
            val fine = ContextCompat.checkSelfPermission(
                this@StepCounterService, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                this@StepCounterService, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return@launch
            // Location type BEFORE requesting: background fixes need it.
            runCatching {
                val day = repo.today().first()
                val paused = repo.paused().first()
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceCompat.startForeground(
                        this@StepCounterService, NOTIF_ID,
                        buildSnapshot(day.steps, day.goal, paused),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                }
            }
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return@launch
            val listener = LocationListener { loc ->
                WorkoutGps.emit(
                    GpsFix(
                        loc.latitude, loc.longitude,
                        if (loc.hasAccuracy()) loc.accuracy else 0f,
                        System.currentTimeMillis(),
                    ),
                )
            }
            // 5s / 5m cadence matches the tracker's 5s filter window; GPS first,
            // network as an indoor fallback (its fixes usually fail the 20m gate).
            runCatching {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 5f, listener)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 5f, listener)
                }
            }.onSuccess {
                workoutListener = listener
                WorkoutGps.setActive(true)
            }.onFailure {
                runCatching { lm.removeUpdates(listener) }
            }
        }
    }

    private fun stopWorkoutTracking() {
        WorkoutGps.setActive(false)
        workoutListener?.let { l ->
            runCatching { (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l) }
        }
        workoutListener = null
        // Downgrade to health-only; the step count keeps flowing either way.
        scope.launch {
            runCatching {
                val day = repo.today().first()
                val paused = repo.paused().first()
                startForegroundTyped(buildSnapshot(day.steps, day.goal, paused))
            }
        }
    }
    /**
     * Vehicle/cycle veto via the Activity Recognition Transition API
     * (IN_VEHICLE + ON_BICYCLE, ENTER/EXIT).
     *
     * Reflection, deliberately: play-services-location is NOT a compile
     * dependency, so on degoogled phones (or until the build owner adds
     * `implementation("com.google.android.gms:play-services-location:21.x")`)
     * Class.forName throws, runCatching swallows it, and the veto stays off.
     * API absent → veto off, steps count exactly as before. The GPS-speed veto
     * in [StepRepository.reportSpeed] is the zero-dependency backup and works
     * regardless. Dynamic receiver is fine: this is a sticky foreground
     * service, alive exactly while counting matters; process death clears the
     * ENTER set AND rebaselines, so a stale veto can never stick.
     */
    private fun registerVehicleVeto() {
        if (vetoRegistered) return
        runCatching {
            val arClass = Class.forName("com.google.android.gms.location.ActivityRecognition")
            val client = arClass.getMethod("getClient", Context::class.java).invoke(null, this)
                ?: return@runCatching
            val transitionClass = Class.forName("com.google.android.gms.location.ActivityTransition")
            val enter = transitionClass.getField("ACTIVITY_TRANSITION_ENTER").getInt(null)
            val exit = transitionClass.getField("ACTIVITY_TRANSITION_EXIT").getInt(null)
            val detectedClass = Class.forName("com.google.android.gms.location.DetectedActivity")
            val inVehicle = detectedClass.getField("IN_VEHICLE").getInt(null)
            val onBicycle = detectedClass.getField("ON_BICYCLE").getInt(null)
            val builderClass =
                Class.forName("com.google.android.gms.location.ActivityTransition\$Builder")
            val newBuilder = builderClass.getDeclaredConstructor()
            val setType =
                builderClass.getMethod("setActivityType", Int::class.javaPrimitiveType)
            val setTrans =
                builderClass.getMethod("setActivityTransition", Int::class.javaPrimitiveType)
            val build = builderClass.getMethod("build")
            fun transition(activity: Int, type: Int): Any {
                val b = newBuilder.newInstance()
                setType.invoke(b, activity)
                setTrans.invoke(b, type)
                return build.invoke(b)
            }
            val requestClass =
                Class.forName("com.google.android.gms.location.ActivityTransitionRequest")
            val request = requestClass.getDeclaredConstructor(List::class.java).newInstance(
                listOf(
                    transition(inVehicle, enter),
                    transition(inVehicle, exit),
                    transition(onBicycle, enter),
                    transition(onBicycle, exit),
                ),
            )
            val pi = PendingIntent.getBroadcast(
                this, VETO_PI_REQUEST, Intent(ACTION_VETO_TRANSITION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            client.javaClass.getMethod(
                "requestActivityTransitionUpdates",
                requestClass,
                PendingIntent::class.java,
            ).invoke(client, request, pi)
            vetoPendingIntent = pi
            ContextCompat.registerReceiver(
                this,
                vetoReceiver,
                IntentFilter(ACTION_VETO_TRANSITION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            vetoRegistered = true
        }
    }

    private fun hasActivityRecognition(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
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
        const val ACTION_WORKOUT_START = "com.tally.steps.action.WORKOUT_START"
        const val ACTION_WORKOUT_STOP = "com.tally.steps.action.WORKOUT_STOP"
        private const val ACTION_VETO_TRANSITION = "com.tally.steps.action.VETO_TRANSITION"
        private const val VETO_PI_REQUEST = 2001
        const val CHANNEL_ID = "tally_steps"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StepCounterService::class.java))
        }

        /** Route tracking for the active workout; survives screen-off. Step-only when location is denied. */
        fun workoutStart(context: Context) {
            val app = context.applicationContext
            start(app)
            app.startService(Intent(app, StepCounterService::class.java).setAction(ACTION_WORKOUT_START))
        }

        fun workoutStop(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, StepCounterService::class.java).setAction(ACTION_WORKOUT_STOP),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepCounterService::class.java))
        }
    }
}
