package com.tally.steps.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.tallyPrefs by preferencesDataStore("tally_prefs")

/** Typed key-value settings; step counts live in Room, never here. */
class PrefsStore(private val context: Context) {

    private object K {
        val goal = intPreferencesKey("goal")
        val heightCm = intPreferencesKey("height_cm")
        val weightKg = intPreferencesKey("weight_kg")
        val stepLenCm = intPreferencesKey("step_len_cm")
        val units = stringPreferencesKey("units")
        val theme = stringPreferencesKey("theme")
        val sensitivity = stringPreferencesKey("sensitivity")
        val treadmill = booleanPreferencesKey("treadmill")
        val hcMode = stringPreferencesKey("hc_mode")
        val hcAuto = booleanPreferencesKey("hc_auto")
        val batteryAsked = booleanPreferencesKey("battery_asked")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val baseline = longPreferencesKey("baseline")
        val bootId = longPreferencesKey("boot_id")
        val paused = booleanPreferencesKey("paused")
        val lastSensorAtMs = longPreferencesKey("last_sensor_at_ms")
        val lastNudgeDay = stringPreferencesKey("last_nudge_day")
        val vetoIgnoredToday = intPreferencesKey("veto_ignored_today")
        val vetoDay = longPreferencesKey("veto_day")
    }

    // Every flow survives a corrupt prefs file: IOException -> default, never a crash.
    private fun <T> safeFlow(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): Flow<T> =
        context.tallyPrefs.data.catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
            .map { it[key] ?: default }

    val goal: Flow<Int> = safeFlow(K.goal, 8000)
    val heightCm: Flow<Int> = safeFlow(K.heightCm, 170)
    val weightKg: Flow<Int> = safeFlow(K.weightKg, 70)
    val stepLenCm: Flow<Int> = safeFlow(K.stepLenCm, 70)
    val units: Flow<String> = safeFlow(K.units, "metric")
    val theme: Flow<String> = safeFlow(K.theme, "system")
    val sensitivity: Flow<String> = safeFlow(K.sensitivity, "M")
    val treadmill: Flow<Boolean> = safeFlow(K.treadmill, false)
    /** OFF | R | RW */
    val hcMode: Flow<String> = safeFlow(K.hcMode, "OFF")
    val hcAuto: Flow<Boolean> = safeFlow(K.hcAuto, false)
    val batteryAsked: Flow<Boolean> = safeFlow(K.batteryAsked, false)
    val onboardingDone: Flow<Boolean> = safeFlow(K.onboardingDone, false)
    /** Last raw TYPE_STEP_COUNTER value; -1 = unset, force rebaseline. */
    val baseline: Flow<Long> = safeFlow(K.baseline, -1L)
    val bootId: Flow<Long> = safeFlow(K.bootId, -1L)
    val paused: Flow<Boolean> = safeFlow(K.paused, false)
    /** Last accepted sensor-event time; persisted so post-restart gap-fill doesn't always trigger. */
    val lastSensorAtMs: Flow<Long> = safeFlow(K.lastSensorAtMs, 0L)
    /** "yyyy-MM-dd" of the last goal-nudge card shown; empty = never. Guards once-per-day. */
    val lastNudgeDay: Flow<String> = safeFlow(K.lastNudgeDay, "")
    /**
     * Steps swallowed by the vehicle/cycle veto today (honest UI note:
     * "N steps ignored while driving/cycling"). Reset on day rollover by
     * StepRepository.ensureToday via [vetoDay]. Step counts live in Room;
     * this counter is a UI-facing note only, never added back to steps.
     */
    val vetoIgnoredToday: Flow<Int> = safeFlow(K.vetoIgnoredToday, 0)
    /** Epoch day the veto counter belongs to; mismatch with today = stale, reset. */
    val vetoDay: Flow<Long> = safeFlow(K.vetoDay, -1L)

    suspend fun setGoal(v: Int) = context.tallyPrefs.edit { it[K.goal] = v.coerceIn(1_000, 50_000) }
    suspend fun setHeightCm(v: Int) = context.tallyPrefs.edit { it[K.heightCm] = v.coerceIn(100, 230) }
    suspend fun setWeightKg(v: Int) = context.tallyPrefs.edit { it[K.weightKg] = v.coerceIn(30, 250) }
    suspend fun setStepLenCm(v: Int) = context.tallyPrefs.edit { it[K.stepLenCm] = v.coerceIn(30, 150) }
    suspend fun setUnits(v: String) = context.tallyPrefs.edit { it[K.units] = v }
    suspend fun setTheme(v: String) = context.tallyPrefs.edit { it[K.theme] = v }
    suspend fun setSensitivity(v: String) = context.tallyPrefs.edit { it[K.sensitivity] = v }
    suspend fun setTreadmill(v: Boolean) = context.tallyPrefs.edit { it[K.treadmill] = v }
    suspend fun setHcMode(v: String) = context.tallyPrefs.edit { it[K.hcMode] = v }
    suspend fun setHcAuto(v: Boolean) = context.tallyPrefs.edit { it[K.hcAuto] = v }
    suspend fun setBatteryAsked(v: Boolean) = context.tallyPrefs.edit { it[K.batteryAsked] = v }
    suspend fun setOnboardingDone(v: Boolean) = context.tallyPrefs.edit { it[K.onboardingDone] = v }
    suspend fun setBaseline(v: Long) = context.tallyPrefs.edit { it[K.baseline] = v }
    /** Single-edit sensor checkpoint: one DataStore write per sensor event. */
    suspend fun setBaselineAndSensorAt(baseline: Long, atMs: Long) = context.tallyPrefs.edit {
        it[K.baseline] = baseline
        it[K.lastSensorAtMs] = atMs
    }
    suspend fun resetBaseline() = setBaseline(-1L)
    suspend fun setBootId(v: Long) = context.tallyPrefs.edit { it[K.bootId] = v }
    suspend fun setPaused(v: Boolean) = context.tallyPrefs.edit { it[K.paused] = v }
    suspend fun setLastNudgeDay(v: String) = context.tallyPrefs.edit { it[K.lastNudgeDay] = v }
    /** Single-transaction increment: never loses a vetoed delta to a concurrent reset. */
    suspend fun addVetoIgnored(delta: Int) = context.tallyPrefs.edit {
        it[K.vetoIgnoredToday] = ((it[K.vetoIgnoredToday] ?: 0) + delta).coerceAtLeast(0)
    }
    suspend fun setVetoIgnoredToday(v: Int) = context.tallyPrefs.edit { it[K.vetoIgnoredToday] = v.coerceAtLeast(0) }
    suspend fun setVetoDay(v: Long) = context.tallyPrefs.edit { it[K.vetoDay] = v }

    companion object {
        @Volatile
        private var instance: PrefsStore? = null

        fun getInstance(context: Context): PrefsStore =
            instance ?: synchronized(this) {
                instance ?: PrefsStore(context.applicationContext).also { instance = it }
            }
    }
}
