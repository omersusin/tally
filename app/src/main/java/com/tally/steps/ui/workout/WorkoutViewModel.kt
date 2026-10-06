package com.tally.steps.ui.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tally.steps.data.PrefsStore
import com.tally.steps.data.TallyDatabase
import com.tally.steps.data.Workout
import com.tally.steps.engine.StepCounterService
import com.tally.steps.engine.StepRepository
import com.tally.steps.engine.TrackPoint
import com.tally.steps.engine.WorkoutGps
import com.tally.steps.engine.WorkoutTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Access to the engine singletons.
 *
 * Engine access pattern (matches the engine agent's API): process-wide
 * singletons via getInstance(). If the app-surface owner ever adds
 * `repository`/`prefs` properties to TallyApp, those win — without this file
 * needing to change, and without anyone editing TallyApp from here.
 */
object EngineBridge {
    fun stepRepository(app: Application): StepRepository =
        (runCatching {
            val c = app.javaClass
            val any = runCatching { c.getMethod("getRepository").invoke(app) }.getOrNull()
                ?: runCatching {
                    c.getDeclaredField("repository").apply { isAccessible = true }.get(app)
                }.getOrNull()
            any as? StepRepository
        }.getOrNull()) ?: StepRepository.getInstance(app)

    fun prefsStore(app: Application): PrefsStore =
        (runCatching {
            val c = app.javaClass
            val any = runCatching { c.getMethod("getPrefs").invoke(app) }.getOrNull()
                ?: runCatching {
                    c.getDeclaredField("prefs").apply { isAccessible = true }.get(app)
                }.getOrNull()
            any as? PrefsStore
        }.getOrNull()) ?: PrefsStore.getInstance(app)

    /** Step length in cm for distance estimates; engine default 70 when unreadable. */
    suspend fun stepLengthCm(prefs: PrefsStore): Int =
        runCatching { prefs.stepLenCm.first() }.getOrDefault(70).coerceIn(30, 150)
}

enum class WorkoutType(val label: String, val usesGps: Boolean) {
    WALK("Walk", usesGps = true),
    RUN("Run", usesGps = true),
    HIKE("Hike", usesGps = true),
    RIDE("Ride", usesGps = true),
    TREADMILL("Treadmill", usesGps = false),
}

enum class TargetKind(val label: String) {
    FREE("Free"),
    STEPS("Steps"),
    DISTANCE("Distance"),
    TIME("Time"),
}

data class WorkoutConfig(
    val type: WorkoutType = WorkoutType.WALK,
    val targetKind: TargetKind = TargetKind.FREE,
    val targetSteps: Int = 2000,
    val targetDistanceM: Int = 1000,
    val targetTimeMin: Int = 20,
    /** Auto-pause when no steps are recorded for 60 s. */
    val autoBreak: Boolean = true,
    /** False when location was denied or treadmill is selected: step-only mode. */
    val gpsAvailable: Boolean = false,
)

data class WorkoutSummary(
    val type: WorkoutType,
    val startMs: Long,
    val endMs: Long,
    val elapsedMs: Long,
    val pausedMs: Long,
    val steps: Int,
    val distanceM: Float,
    val targetHit: Boolean,
)

data class WorkoutUiState(
    val phase: Phase = Phase.IDLE,
    val config: WorkoutConfig = WorkoutConfig(),
    val sessionSteps: Int = 0,
    val elapsedMs: Long = 0L,
    val pausedMs: Long = 0L,
    val autoPaused: Boolean = false,
    val distanceM: Float = 0f,
    /** 0..1 progress toward the target, or null for Free. */
    val targetFraction: Float? = null,
    val targetHit: Boolean = false,
    val lastSummary: WorkoutSummary? = null,
    /** Honest note about where the summary was saved. */
    val saveNote: String? = null,
    /** Transient notice (export/delete confirmations). Cleared on next action. */
    val notice: String? = null,
    /** Always true now: summaries persist to the Workout table. */
    val historySupported: Boolean = true,
) {
    enum class Phase { IDLE, ACTIVE, PAUSED, DONE }
}

/**
 * S04 session state machine. Steps come from [StepRepository.today] so the
 * workout never double-counts: session steps = today.steps - baseline.
 * The summary persists to the Workout table (visible via [workouts]); the
 * sensor already counted these steps in today's total, so nothing is added
 * via adjustManual — that would double-count. Distance is a step-length
 * estimate (treadmill: steps only, no GPS, source=treadmill via type).
 */
class WorkoutViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: StepRepository = EngineBridge.stepRepository(app)
    private val prefs: PrefsStore = EngineBridge.prefsStore(app)
    private val workoutDao = TallyDatabase.getInstance(app).workoutDao()
    private val tracker = WorkoutTracker()

    private val _ui = MutableStateFlow(WorkoutUiState())
    val ui: StateFlow<WorkoutUiState> = _ui.asStateFlow()

    /** Live GPS fixes (filtered per arch §5); drives WorkoutMap + polyline. */
    val trackPoints: StateFlow<List<TrackPoint>> = tracker.pointsFlow

    /** Persisted workout history, newest first. */
    val workouts: StateFlow<List<Workout>> = workoutDao.workoutsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var ticker: Job? = null
    private var gpsJob: Job? = null
    private var baselineSteps = 0
    private var sessionStartMs = 0L
    private var activeSinceMs = 0L
    private var lastProgressSteps = 0
    private var lastProgressAtMs = 0L

    fun setConfig(config: WorkoutConfig) {
        if (_ui.value.phase == WorkoutUiState.Phase.IDLE ||
            _ui.value.phase == WorkoutUiState.Phase.DONE
        ) {
            val clean = if (!config.type.usesGps) config.copy(gpsAvailable = false) else config
            _ui.value = _ui.value.copy(config = clean, saveNote = null, lastSummary = null)
        }
    }

    /** Called by the permission launcher in WorkoutScreen; safe mid-session. */
    fun setGpsAvailable(available: Boolean) {
        val s = _ui.value
        val v = available && s.config.type.usesGps
        if (s.config.gpsAvailable != v) {
            _ui.value = s.copy(config = s.config.copy(gpsAvailable = v))
        }
    }

    /** Raw GPS fix from WorkoutMap; the tracker applies the arch §5 filter. */
    fun onLocation(lat: Double, lon: Double, accuracyM: Float) {
        val s = _ui.value
        if (s.phase != WorkoutUiState.Phase.ACTIVE) return
        if (!s.config.gpsAvailable || !s.config.type.usesGps) return
        if (tracker.onLocation(lat, lon, System.currentTimeMillis(), accuracyM)) {
            // Feed the engine veto: sustained drive-speeds hold the step baseline.
            val speed = tracker.lastSpeedMs
            viewModelScope.launch { runCatching { repo.reportSpeed(speed) } }
        }
    }

    /** Service pipe: fixes flow even with the screen off (location-type FGS). */
    private fun startGpsPipe() {
        gpsJob?.cancel()
        val s = _ui.value
        if (!s.config.gpsAvailable || !s.config.type.usesGps) return
        StepCounterService.workoutStart(getApplication())
        gpsJob = viewModelScope.launch {
            WorkoutGps.fixes.collect { fix ->
                onLocation(fix.lat, fix.lon, fix.accuracyM)
            }
        }
    }

    private fun stopGpsPipe() {
        gpsJob?.cancel()
        gpsJob = null
        runCatching { StepCounterService.workoutStop(getApplication()) }
    }

    /**
     * One-tap start that keeps the chosen activity and GPS state and only
     * clears the target to Free — never silently switches Walk vs Ride.
     */
    fun quickStart() {
        val cur = _ui.value.config
        setConfig(cur.copy(targetKind = TargetKind.FREE))
        start()
    }

    fun start() {
        if (_ui.value.phase == WorkoutUiState.Phase.ACTIVE) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Baseline from the engine's own Day row; session delta can never double-count.
            baselineSteps = runCatching { repo.today().first().steps }.getOrDefault(0)
            lastProgressSteps = 0
            lastProgressAtMs = now
            sessionStartMs = now
            activeSinceMs = now
            tracker.start(now)
            // RIDE pedals: stand the drive/cycle vetoes down while ACTIVE.
            runCatching { repo.setRideActive(_ui.value.config.type == WorkoutType.RIDE) }
            startGpsPipe()
            _ui.value = _ui.value.copy(
                phase = WorkoutUiState.Phase.ACTIVE,
                sessionSteps = 0,
                elapsedMs = 0L,
                pausedMs = 0L,
                autoPaused = false,
                distanceM = 0f,
                targetFraction = null,
                targetHit = false,
                saveNote = null,
                historySupported = true,
            )
            startTicker()
        }
    }

    fun pause(byAutoBreak: Boolean = false) {
        val s = _ui.value
        if (s.phase != WorkoutUiState.Phase.ACTIVE) return
        ticker?.cancel()
        tracker.pause(System.currentTimeMillis())
        _ui.value = s.copy(phase = WorkoutUiState.Phase.PAUSED, autoPaused = byAutoBreak)
    }

    fun resume() {
        val s = _ui.value
        if (s.phase != WorkoutUiState.Phase.PAUSED) return
        val now = System.currentTimeMillis()
        tracker.resume(now)
        _ui.value = s.copy(
            phase = WorkoutUiState.Phase.ACTIVE,
            pausedMs = s.pausedMs + (now - activeSinceMs).coerceAtLeast(0L),
            autoPaused = false,
        )
        activeSinceMs = now
        lastProgressAtMs = now
        startTicker()
    }

    fun finish() {
        val s = _ui.value
        if (s.phase != WorkoutUiState.Phase.ACTIVE &&
            s.phase != WorkoutUiState.Phase.PAUSED
        ) return
        ticker?.cancel()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Finishing while paused: include the trailing paused stretch.
            val pausedMs = if (s.phase == WorkoutUiState.Phase.PAUSED) {
                s.pausedMs + (now - activeSinceMs).coerceAtLeast(0L)
            } else {
                s.pausedMs
            }
            val steps = s.sessionSteps
            val distM = s.distanceM
            val summary = WorkoutSummary(
                type = s.config.type,
                startMs = sessionStartMs,
                endMs = now,
                elapsedMs = s.elapsedMs,
                pausedMs = pausedMs,
                steps = steps,
                distanceM = distM,
                targetHit = s.targetHit,
            )
            val note = persistSummary(summary, tracker.polylineString())
            runCatching { repo.setRideActive(false) }
            stopGpsPipe()
            _ui.value = s.copy(phase = WorkoutUiState.Phase.DONE, lastSummary = summary, saveNote = note)
        }
    }

    fun reset() {
        ticker?.cancel()
        tracker.clear()
        stopGpsPipe()
        viewModelScope.launch { runCatching { repo.setRideActive(false) } }
        _ui.value = WorkoutUiState(config = _ui.value.config)
    }

    override fun onCleared() {
        // Screen gone mid-session: stop the route pipe (steps survive in Room).
        stopGpsPipe()
        super.onCleared()
    }

    fun clearNotice() {
        if (_ui.value.notice != null) _ui.value = _ui.value.copy(notice = null)
    }

    /** Permanent delete of one workout. Day totals are untouched (sensor truth). */
    fun deleteWorkout(w: Workout) {
        viewModelScope.launch {
            runCatching { workoutDao.delete(w) }
                .onSuccess { _ui.value = _ui.value.copy(notice = "Workout deleted.") }
                .onFailure { _ui.value = _ui.value.copy(notice = "Could not delete that workout.") }
        }
    }

    /** GPX 1.1 export of one workout's route into the Tally folder. Step-only workouts have no route. */
    fun exportGpx(w: Workout) {
        viewModelScope.launch {
            val gpx = WorkoutTracker.storedToGpx(w.type, w.startMs, w.gpsPolyline)
            if (gpx == null) {
                _ui.value = _ui.value.copy(notice = "Step-only workout — no route to export.")
                return@launch
            }
            runCatching {
                val dir = java.io.File(
                    getApplication<Application>().getExternalFilesDir(null), "Tally",
                ).apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date(w.startMs))
                java.io.File(dir, "tally-${w.type}-$stamp.gpx").apply { writeText(gpx) }
            }.onSuccess {
                _ui.value = _ui.value.copy(notice = "Route saved to ${it.name} in the Tally folder.")
            }.onFailure {
                _ui.value = _ui.value.copy(notice = "Export failed — nothing was written.")
            }
        }
    }

    // ---- internals ----

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            val stepLen = EngineBridge.stepLengthCm(prefs)
            while (true) {
                delay(1000L)
                val s = _ui.value
                if (s.phase != WorkoutUiState.Phase.ACTIVE) break
                val now = System.currentTimeMillis()
                val todaySteps = runCatching { repo.today().first().steps }
                    .getOrDefault(baselineSteps)
                val session = (todaySteps - baselineSteps).coerceAtLeast(0)
                val elapsed = s.elapsedMs + 1000L
                val distM = session * stepLen / 100f
                if (session > lastProgressSteps) {
                    lastProgressSteps = session
                    lastProgressAtMs = now
                    tracker.onSteps(now)
                }
                val fraction = targetFraction(s.config, session, distM, elapsed)
                val hit = fraction != null && fraction >= 1f
                _ui.value = s.copy(
                    sessionSteps = session,
                    elapsedMs = elapsed,
                    distanceM = distM,
                    targetFraction = fraction?.coerceAtMost(1f),
                    targetHit = hit || s.targetHit,
                )
                if (s.config.autoBreak && now - lastProgressAtMs >= 60_000L && session > 0) {
                    pause(byAutoBreak = true)
                    break
                }
            }
        }
    }

    private fun targetFraction(c: WorkoutConfig, steps: Int, distM: Float, elapsedMs: Long): Float? =
        when (c.targetKind) {
            TargetKind.FREE -> null
            TargetKind.STEPS -> if (c.targetSteps > 0) steps / c.targetSteps.toFloat() else null
            TargetKind.DISTANCE ->
                if (c.targetDistanceM > 0) distM / c.targetDistanceM.toFloat() else null
            TargetKind.TIME ->
                if (c.targetTimeMin > 0) elapsedMs / (c.targetTimeMin * 60_000f) else null
        }

    /**
     * Persists the summary to the Workout table. Sensor-delta attribution
     * only — never adjustManual (that would double-count today's total).
     */
    private suspend fun persistSummary(summary: WorkoutSummary, polyline: String?): String {
        return runCatching {
            workoutDao.insert(
                Workout(
                    id = UUID.randomUUID().toString(),
                    type = summary.type.name.lowercase(),
                    startMs = summary.startMs,
                    endMs = summary.endMs,
                    steps = summary.steps,
                    distanceM = summary.distanceM,
                    pausedMs = summary.pausedMs,
                    gpsPolyline = polyline,
                ),
            )
            "Saved to your workout history."
        }.getOrElse {
            "Couldn't save this workout — ${summary.steps} steps are still " +
                "in today's count from the sensor, so nothing was lost."
        }
    }
}
