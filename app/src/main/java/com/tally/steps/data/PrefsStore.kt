package com.tally.steps.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    }

    val goal: Flow<Int> = context.tallyPrefs.data.map { it[K.goal] ?: 8000 }
    val heightCm: Flow<Int> = context.tallyPrefs.data.map { it[K.heightCm] ?: 170 }
    val weightKg: Flow<Int> = context.tallyPrefs.data.map { it[K.weightKg] ?: 70 }
    val stepLenCm: Flow<Int> = context.tallyPrefs.data.map { it[K.stepLenCm] ?: 70 }
    val units: Flow<String> = context.tallyPrefs.data.map { it[K.units] ?: "metric" }
    val theme: Flow<String> = context.tallyPrefs.data.map { it[K.theme] ?: "system" }
    val sensitivity: Flow<String> = context.tallyPrefs.data.map { it[K.sensitivity] ?: "M" }
    val treadmill: Flow<Boolean> = context.tallyPrefs.data.map { it[K.treadmill] ?: false }
    /** OFF | R | RW */
    val hcMode: Flow<String> = context.tallyPrefs.data.map { it[K.hcMode] ?: "OFF" }
    val hcAuto: Flow<Boolean> = context.tallyPrefs.data.map { it[K.hcAuto] ?: false }
    val batteryAsked: Flow<Boolean> = context.tallyPrefs.data.map { it[K.batteryAsked] ?: false }
    val onboardingDone: Flow<Boolean> = context.tallyPrefs.data.map { it[K.onboardingDone] ?: false }
    /** Last raw TYPE_STEP_COUNTER value; -1 = unset, force rebaseline. */
    val baseline: Flow<Long> = context.tallyPrefs.data.map { it[K.baseline] ?: -1L }
    val bootId: Flow<Long> = context.tallyPrefs.data.map { it[K.bootId] ?: -1L }
    val paused: Flow<Boolean> = context.tallyPrefs.data.map { it[K.paused] ?: false }
    /** Last accepted sensor-event time; persisted so post-restart gap-fill doesn't always trigger. */
    val lastSensorAtMs: Flow<Long> = context.tallyPrefs.data.map { it[K.lastSensorAtMs] ?: 0L }

    suspend fun setGoal(v: Int) = context.tallyPrefs.edit { it[K.goal] = v.coerceIn(1_000, 100_000) }
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

    companion object {
        @Volatile
        private var instance: PrefsStore? = null

        fun getInstance(context: Context): PrefsStore =
            instance ?: synchronized(this) {
                instance ?: PrefsStore(context.applicationContext).also { instance = it }
            }
    }
}
