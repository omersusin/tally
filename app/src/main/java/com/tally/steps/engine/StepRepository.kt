package com.tally.steps.engine

import android.content.Context
import android.content.pm.PackageManager
import com.tally.steps.data.Day
import com.tally.steps.data.PrefsStore
import com.tally.steps.data.TallyDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

    // Active minutes: in-memory minute buckets only, persisted count only.
    // minuteId = wallClockMs / 60_000. Reboot/process death loses the buckets,
    // so partial-minute progress toward the threshold is lost and that minute
    // under-counts. Honest direction (never phantom), accepted.
    private val minuteSteps = mutableMapOf<Long, Long>()
    private val countedMinutes = mutableSetOf<Long>()
    private var activeDayEpoch: Long = -1L

    // ---- Vehicle/cycle veto state (in-memory; survives no reboot, honest) ----
    // AR-transition veto (service feeds via setArVeto) OR GPS-speed veto
    // (whoever holds location feeds via reportSpeed). Either one holds the
    // baseline: sensor deltas advance the baseline silently and are tallied
    // into vetoIgnoredToday instead of steps. Reboot clears both flags, and a
    // reboot already rebaselines, so the veto can only ever under-count.
    private val arVeto = MutableStateFlow(false)
    private val gpsVeto = MutableStateFlow(false)
    private var fastStreak = 0

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

    /**
     * Mark a past missed day as rest, preserving streaks through it. Only for
     * past days that missed their goal (epochDay < today, steps < goal, not
     * already rest). At most one rest day per rolling 7 days: any other rest
     * within 6 days either side rejects. Returns true when saved. Additive —
     * touches only the restDay flag and updatedAt, never the step math.
     */
    suspend fun markRestDay(epochDay: Long): Boolean = mutex.withLock {
        val today = todayEpoch()
        if (epochDay >= today) return false
        val row = dao.getDay(epochDay) ?: return false
        if (row.restDay) return false
        if (row.goal <= 0 || row.steps >= row.goal) return false
        val near = dao.historyFlow(365).first()
        if (near.any {
                it.restDay && it.epochDay != epochDay &&
                    kotlin.math.abs(it.epochDay - epochDay) <= 6
            }
        ) return false
        dao.upsert(row.copy(restDay = true, updatedAt = now()))
        true
    }

    /**
     * Current streak via [GoalCoach]: goal days connected through rest days.
     * Returns (days, restDaysUsed). Read-only, additive.
     */
    suspend fun currentStreak(): Pair<Int, Int> {
        val today = today().first()
        val days = dao.historyFlow(365).first()
        val all = if (days.none { it.epochDay == today.epochDay }) {
            listOf(today) + days
        } else {
            days.map { if (it.epochDay == today.epochDay) today else it }
        }
        return GoalCoach.currentStreak(
            all.map { GoalCoach.DayMark(it.epochDay, it.goal > 0 && it.steps >= it.goal, it.restDay) },
        )
    }

    /**
     * Quiet goal suggestion via [GoalCoach], from the last 7 complete days
     * (today is partial, so it's excluded). Null when silence is honest.
     * Read-only, additive.
     */
    suspend fun goalNudge(): GoalCoach.GoalNudge? {
        val goal = prefs.goal.first()
        val last7 = dao.historyFlow(8).first()
            .filter { it.epochDay < todayEpoch() }
            .sortedBy { it.epochDay }
            .takeLast(7)
            .map { it.steps + it.manualDelta }
        return GoalCoach.nudgeFor(last7, goal)
    }

    /** Suggested goal only; null when silence is honest. See [goalNudge]. */
    suspend fun suggestedGoal(): Int? = goalNudge()?.suggestedGoal

    /**
     * Sensitivity meaning (the stored L/M/H finally means something concrete):
     * minimum steps within one clock minute for that minute to count as an
     * active minute. L=100 (only brisk sustained walking), M=60 (~1 step/s
     * average), H=30 (gentle movement counts). It never touches the hardware
     * counter — it only gates the active-minute count. Also exposed for
     * workout auto-break tuning (another agent owns that consumer).
     */
    fun sensitivityThreshold(): Flow<Int> = prefs.sensitivity.map { thresholdFor(it) }

    /**
     * One-way HC auto-sync latch: engages only while the HC read grant is held;
     * once latched it persists — a later revoke only pauses syncing
     * ([syncHealth] returns early) and never clears the flag; the user turns
     * it off in Settings. Returns whether the latch engaged.
     */
    suspend fun latchHcAuto(): Boolean = mutex.withLock {
        if (hc.hasPermissions(app)) {
            prefs.setHcAuto(true)
            true
        } else {
            false
        }
    }

    /** User toggle for auto-sync, including OFF. The latch above is the only path to true-with-grant. */
    suspend fun setHcAuto(v: Boolean) = prefs.setHcAuto(v)

    fun hcAuto(): Flow<Boolean> = prefs.hcAuto

    /** True when the device reports a hardware step counter. Never null, never throws. */
    fun hasSensor(): Flow<Boolean> = flow {
        emit(
            runCatching {
                app.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
            }.getOrDefault(false),
        )
    }

    /**
     * Vehicle/cycle veto, additive. True while IN_VEHICLE/ON_BICYCLE (Activity
     * Recognition transitions, service-owned) or while GPS reports
     * walk-impossible speed (workout-track owner feeds [reportSpeed]).
     * UI: show a quiet "not counting — driving/cycling" note, never a number
     * correction control; the ignored steps are gone by design.
     */
    fun vetoActive(): Flow<Boolean> =
        combine(arVeto, gpsVeto) { a, g -> a || g }.distinctUntilChanged()

    /**
     * Steps swallowed by the veto today, for the honest UI note
     * ("N steps ignored while driving/cycling"). Persisted in PrefsStore,
     * reset on day rollover. Never added back to any total.
     */
    fun vetoIgnoredToday(): Flow<Int> = prefs.vetoIgnoredToday.distinctUntilChanged()

    /** Floors climbed today, from [BaroFloors]; 0 also means "no barometer". */
    fun floorsToday(): Flow<Int> = today().map { it.floors }.distinctUntilChanged()

    /** Service-only: Activity Recognition transition state. Mutex-guarded. */
    suspend fun setArVeto(active: Boolean) = mutex.withLock { arVeto.value = active }

    /**
     * Location-owner-only: one GPS speed sample in m/s per accepted fix.
     * Sustained [VETO_SPEED_MPS]+ over [VETO_STREAK] consecutive fixes latches
     * the GPS veto (walk-impossible: faster than any run); a slow fix below
     * [VETO_CLEAR_MPS] releases it. Single noisy fixes never latch — hence
     * the streak, hence the low release bar. Call only while a workout track
     * is actively receiving fixes; never call with synthetic speeds.
     */
    suspend fun reportSpeed(speedMps: Float) = mutex.withLock {
        if (!speedMps.isFinite()) return@withLock
        if (speedMps > VETO_SPEED_MPS) {
            if (++fastStreak >= VETO_STREAK) gpsVeto.value = true
        } else {
            fastStreak = 0
            if (speedMps < VETO_CLEAR_MPS) gpsVeto.value = false
        }
    }

    /**
     * Barometer-service-only: credit completed floors to today. Additive;
     * never touches steps/distance/kcal. Day rollover between events credits
     * to whichever day is current at call time (under-count direction kept:
     * each floor counted at most once, never duplicated across days).
     */
    suspend fun addFloors(n: Int) = mutex.withLock {
        if (n <= 0) return@withLock
        val t = todayEpoch()
        val cur = dao.getDay(t) ?: blank(t)
        dao.upsert(
            cur.copy(
                floors = (cur.floors + n).coerceIn(0, MAX_FLOORS),
                updatedAt = now(),
            ),
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
                        floors = d.floors.coerceIn(0, MAX_FLOORS),
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
        val nowMs = now()
        val baseline = prefs.baseline.first()
        // Baseline-first order is deliberate: a crash between the baseline write
        // and the day upsert loses at most one delta (under-count, honest). The
        // reverse order could credit the same delta twice after a crash (phantom
        // steps). Under-count chosen; never phantom.
        if (baseline < 0 || counter < baseline) {
            // First reading ever, post-boot, or reboot (counter restarted):
            // rebaseline, count nothing, keep Day.steps. The in-flight minute's
            // partial progress is also lost here (buckets below are untouched),
            // so a reboot minute under-counts by design.
            prefs.setBaselineAndSensorAt(counter, nowMs)
            ensureToday(t)
            return@withLock
        }
        val delta = counter - baseline
        prefs.setBaselineAndSensorAt(counter, nowMs)
        if (prefs.paused.first() || delta <= 0) {
            ensureToday(t)
            return@withLock
        }
        // Vehicle/cycle veto: baseline already advanced above, so this delta
        // can never be credited later — it is silently dropped and tallied
        // into vetoIgnoredToday for the honest UI note. Same philosophy as
        // the crash path: under-count, never phantom.
        if (arVeto.value || gpsVeto.value) {
            prefs.addVetoIgnored(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            ensureToday(t)
            return@withLock
        }
        if (t != activeDayEpoch) {
            // Day rolled: yesterday's buckets can never complete, drop them.
            minuteSteps.clear()
            countedMinutes.clear()
            activeDayEpoch = t
        }
        val cur = dao.getDay(t) ?: blank(t)
        dao.upsert(
            estimates(
                cur.copy(
                    steps = (cur.steps + delta).coerceAtMost(MAX_STEPS.toLong()).toInt(),
                    activeMin = countActiveMinute(nowMs, delta, cur.activeMin),
                    source = sourceFor(cur.source),
                ),
            ),
        )
    }

    /** Idempotent: call on every worker tick and date/timezone change. */
    suspend fun rolloverCheck() = mutex.withLock { ensureToday(todayEpoch()) }

    suspend fun onDateOrZoneChanged() = rolloverCheck()

    /**
     * Health Connect sync, hcMode/hcAuto-gated. Merge is max() (reads never overwrite a
     * larger sensor value) and only after a sensor gap; write-back is sensor
     * delta only, manual excluded. Write-back runs in RW mode with the write
     * grant only — without it we skip (honest: no fake sync), we never throw.
     *
     * Auto-sync latch: proceeds when hcMode != OFF *or* hcAuto is latched, so a
     * latched user keeps background gap-fill even with mode OFF. A missing read
     * grant only skips the run — it never clears hcAuto (one-way latch). With
     * mode OFF + auto, only reads run; write-back still needs mode RW.
     *
     * Merged-write misattribution limitation: row.steps can include steps
     * gap-filled from OTHER apps' HC records, so write-back re-attributes those
     * as ours. Echo subtraction keeps our own reads honest, but third-party
     * apps merging HC totals may double-count. Accepted: our DB stays
     * sensor-first; HC is a courtesy copy, not source of truth.
     */
    suspend fun syncHealth() {
        val mode = prefs.hcMode.first()
        val auto = prefs.hcAuto.first()
        if (mode == "OFF" && !auto) return
        if (!hc.hasPermissions(app)) return // revoke pauses sync; latch untouched
        mutex.withLock {
            val t = todayEpoch()
            val today = LocalDate.now()
            val cur = dao.getDay(t) ?: blank(t)
            if (now() - prefs.lastSensorAtMs.first() > GAP_MS) {
                val external = hc.readDayForWorker(app, today)?.coerceAtMost(MAX_STEPS.toLong())
                if (external != null && external > cur.steps) {
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

    /**
     * Active-minute gate: accumulates this event's delta into its clock-minute
     * bucket; the minute counts once its total reaches the sensitivity
     * threshold. Steps always count; only the minute may not. HC gap-fill and
     * restore never route through here (we know no minutes for their steps),
     * so those paths keep the persisted count untouched.
     */
    private suspend fun countActiveMinute(nowMs: Long, delta: Long, persisted: Int): Int {
        val threshold = thresholdFor(prefs.sensitivity.first())
        val id = nowMs / 60_000L
        if (minuteSteps.size > 240) {
            // Bound memory: drop buckets older than ~3h (a day has 1440 min max).
            minuteSteps.keys.filter { it < id - 180 }.forEach {
                minuteSteps.remove(it)
                countedMinutes.remove(it)
            }
        }
        val acc = (minuteSteps[id] ?: 0L) + delta
        minuteSteps[id] = acc
        return if (acc >= threshold && countedMinutes.add(id)) {
            (persisted + 1).coerceAtMost(1440)
        } else {
            persisted
        }
    }

    private fun thresholdFor(sensitivity: String): Int = when (sensitivity) {
        "L" -> ACTIVE_THRESHOLD_L
        "H" -> ACTIVE_THRESHOLD_H
        else -> ACTIVE_THRESHOLD_M
    }

    /**
     * Treadmill wiring: when the user flagged treadmill mode, sensor-path rows
     * are labelled source="treadmill" (distance already = steps*stepLen via
     * estimates, verified not changed). Restore/HC paths keep their own source
     * — this labels only what the sensor recorded while the flag was on.
     * GPS-filter skip lives in WorkoutTracker (another agent).
     */
    private suspend fun sourceFor(fallback: String): String =
        if (prefs.treadmill.first()) "treadmill" else fallback

    private suspend fun ensureToday(t: Long) {
        if (dao.getDay(t) == null) dao.upsert(blank(t))
        // Veto-counter rollover: same hook, same mutex. First event of a new
        // day resets the honest-note counter; stale vetoDay (-1 included)
        // always mismatches, so a fresh install resets harmlessly to 0.
        if (prefs.vetoDay.first() != t) {
            prefs.setVetoIgnoredToday(0)
            prefs.setVetoDay(t)
        }
    }

    private suspend fun blank(t: Long): Day =
        Day(t, 0, 0f, 0f, 0, prefs.goal.first(), 0, sourceFor("sensor"), now())

    /** distance = total steps * stride; kcal = steps * 0.04 * (kg/70). activeMin intentionally preserved. */
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
        /** Absurd floor counts are rejected; nobody climbs 10k floors in a day. */
        private const val MAX_FLOORS = 10_000
        /**
         * GPS veto: sustained speed above this latches (walk-impossible —
         * above WorkoutTracker.MAX_WALK_SPEED_MS with margin, so a sprint
         * never trips it); below [VETO_CLEAR_MPS] releases. Streak required
         * so one noisy fix can't veto real steps.
         */
        private const val VETO_SPEED_MPS = 4f
        private const val VETO_CLEAR_MPS = 2f
        private const val VETO_STREAK = 3
        /** Active-minute step thresholds per sensitivity (see sensitivityThreshold). */
        private const val ACTIVE_THRESHOLD_L = 100
        private const val ACTIVE_THRESHOLD_M = 60
        private const val ACTIVE_THRESHOLD_H = 30

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
