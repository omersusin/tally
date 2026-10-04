package com.tally.steps.engine

import android.content.Context
import android.content.pm.PackageManager
import com.tally.steps.data.Day
import com.tally.steps.data.PrefsStore
import com.tally.steps.data.TallyDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import java.time.LocalDate

/**
 * Offline step engine. UI agents code against the public API below;
 * sensor/reboot/rollover/estimate details are internal.
 */
class StepRepository private constructor(
    private val app: Context,
    private val db: TallyDatabase,
    private val prefs: PrefsStore,
    private val hc: HealthConnect,
) {
    private val dao = db.dayDao()
    private val mutex = Mutex()

    // ---- Public API (keep signatures stable) ----

    /**
     * Today's row; emits a blank row instead of null before anything is recorded.
     * Queries the day row for the current epoch day — never historyFlow(1), whose
     * global-latest row can be a FUTURE day after a clock-back/timezone shift.
     */
    fun today(): Flow<Day> {
        val t = todayEpoch()
        return combine(dao.dayFlow(t), prefs.goal) { row, goal ->
            row?.copy(goal = goal)
                ?: Day(t, 0, 0f, 0f, 0, goal, 0, "sensor", now())
        }
    }

    fun history(limit: Int): Flow<List<Day>> = dao.historyFlow(limit.coerceIn(1, 365))

    suspend fun setGoal(v: Int) = mutex.withLock {
        prefs.setGoal(v)
        val goal = prefs.goal.first()
        val t = todayEpoch()
        dao.upsert((dao.getDay(t) ?: blank(t)).copy(goal = goal, updatedAt = now()))
    }

    suspend fun adjustManual(d: Int) = mutex.withLock {
        val t = todayEpoch()
        val cur = dao.getDay(t) ?: blank(t)
        val manual = (cur.steps + cur.manualDelta + d).coerceAtLeast(0) - cur.steps
        dao.upsert(estimates(cur.copy(manualDelta = manual)))
    }

    suspend fun clearManual() = mutex.withLock {
        val t = todayEpoch()
        dao.getDay(t)?.let { dao.upsert(estimates(it.copy(manualDelta = 0))) }
    }

    suspend fun setPaused(v: Boolean) = prefs.setPaused(v)

    fun paused(): Flow<Boolean> = prefs.paused

    /** True when the device reports a hardware step counter. Never null, never throws. */
    fun hasSensor(): Flow<Boolean> = flow {
        emit(
            runCatching {
                app.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
            }.getOrDefault(false),
        )
    }

    /**
     * Restore path: upsert backup rows with ranges clamped and estimates
     * recomputed, atomically. The user's current goal pref is NEVER overwritten
     * from backup — rows keep their own stored goal column, and today() always
     * displays the live pref goal regardless.
     */
    suspend fun importDays(days: List<Day>) = mutex.withLock {
        db.withTransaction {
            days.forEach { d ->
                val steps = d.steps.coerceIn(0, MAX_STEPS)
                val clean = estimates(
                    d.copy(
                        steps = steps,
                        activeMin = d.activeMin.coerceIn(0, 1440),
                        // Clamp so steps + manualDelta can never go negative (mirrors
                        // adjustManual's total>=0 guarantee); absurd values rejected at parse.
                        manualDelta = d.manualDelta.coerceIn(-steps, MANUAL_DELTA_ABS_MAX),
                        goal = d.goal.coerceIn(0, 200_000),
                        updatedAt = if (d.updatedAt > 0) d.updatedAt else now(),
                    ),
                ).copy(goal = d.goal.coerceIn(0, 200_000))
                dao.upsert(clean)
            }
        }
    }

    // ---- Sensor ingest (service/worker only) ----

    /** One raw TYPE_STEP_COUNTER cumulative value. Handles reboot + rollover. */
    suspend fun onSensor(counter: Long) = mutex.withLock {
        val t = todayEpoch()
        val baseline = prefs.baseline.first()
        // Baseline-first order is deliberate: a crash between the baseline write
        // and the day upsert loses at most one delta (under-count, honest). The
        // reverse order could credit the same delta twice after a crash (phantom
        // steps). Under-count chosen; never phantom.
        if (baseline < 0 || counter < baseline) {
            // First reading ever, post-boot, or reboot (counter restarted):
            // rebaseline, count nothing, keep Day.steps.
            prefs.setBaselineAndSensorAt(counter, now())
            ensureToday(t)
            return@withLock
        }
        val delta = counter - baseline
        prefs.setBaselineAndSensorAt(counter, now())
        if (prefs.paused.first() || delta <= 0) {
            ensureToday(t)
            return@withLock
        }
        val cur = dao.getDay(t) ?: blank(t)
        dao.upsert(estimates(cur.copy(steps = (cur.steps + delta).coerceAtMost(MAX_STEPS.toLong()).toInt())))
    }

    /** Idempotent: call on every worker tick and date/timezone change. */
    suspend fun rolloverCheck() = mutex.withLock { ensureToday(todayEpoch()) }

    suspend fun onDateOrZoneChanged() = rolloverCheck()

    /**
     * Health Connect sync, hcMode-gated. Merge is max() (reads never overwrite a
     * larger sensor value) and only after a sensor gap; write-back is sensor
     * delta only, manual excluded. Write-back runs in RW mode with the write
     * grant only — without it we skip (honest: no fake sync), we never throw.
     *
     * Merged-write misattribution limitation: row.steps can include steps
     * gap-filled from OTHER apps' HC records, so write-back re-attributes those
     * as ours. Echo subtraction keeps our own reads honest, but third-party
     * apps merging HC totals may double-count. Accepted: our DB stays
     * sensor-first; HC is a courtesy copy, not source of truth.
     */
    suspend fun syncHealth() {
        val mode = prefs.hcMode.first()
        if (mode == "OFF" || !hc.hasPermissions(app)) return
        mutex.withLock {
            val t = todayEpoch()
            val today = LocalDate.now()
            val cur = dao.getDay(t) ?: blank(t)
            if (now() - prefs.lastSensorAtMs.first() > GAP_MS) {
                val external = hc.readDayExclOurs(app, today).coerceAtMost(MAX_STEPS.toLong())
                if (external > cur.steps) {
                    dao.upsert(estimates(cur.copy(steps = external.toInt(), source = "hc")))
                }
            }
            if (mode == "RW" && hc.hasWritePermission(app)) {
                val row = dao.getDay(t) ?: cur
                if (row.steps > 0) hc.writeDay(app, today, row.steps, row.distanceM, row.kcal)
            }
        }
    }

    // ---- Internals ----

    private suspend fun ensureToday(t: Long) {
        if (dao.getDay(t) == null) dao.upsert(blank(t))
    }

    private suspend fun blank(t: Long): Day =
        Day(t, 0, 0f, 0f, 0, prefs.goal.first(), 0, "sensor", now())

    /** distance = total steps * stride; kcal = steps * 0.04 * (kg/70). */
    private suspend fun estimates(d: Day): Day {
        val stepLenM = prefs.stepLenCm.first() / 100f
        val kg = prefs.weightKg.first()
        val total = d.steps + d.manualDelta
        return d.copy(
            distanceM = total * stepLenM,
            kcal = total * 0.04f * (kg / 70f),
            goal = prefs.goal.first(),
            updatedAt = now(),
        )
    }

    private fun todayEpoch(): Long = LocalDate.now().toEpochDay()

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val MAX_STEPS = Int.MAX_VALUE - 1
        private const val GAP_MS = 30 * 60 * 1000L
        /** Absurd manual corrections are rejected at CSV parse; this is the last-resort clamp. */
        private const val MANUAL_DELTA_ABS_MAX = 100_000

        @Volatile
        private var instance: StepRepository? = null

        fun getInstance(context: Context): StepRepository {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: StepRepository(
                    app,
                    TallyDatabase.getInstance(app),
                    PrefsStore.getInstance(app),
                    HealthConnect(),
                ).also { instance = it }
            }
        }
    }
}
