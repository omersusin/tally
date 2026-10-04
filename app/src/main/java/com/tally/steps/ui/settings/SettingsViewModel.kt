package com.tally.steps.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tally.steps.data.Day
import com.tally.steps.export.BackupExport
import com.tally.steps.export.RestoreOutcome
import com.tally.steps.ui.workout.EngineBridge
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * S06 settings state. Reads come from [com.tally.steps.data.PrefsStore] flows
 * plus [com.tally.steps.engine.StepRepository] for goal/paused.
 * Goal writes go to StepRepository.setGoal (spec); everything else uses the
 * PrefsStore setters, whose names match the engine exactly.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app
    private val repo = EngineBridge.stepRepository(app)
    private val prefs = EngineBridge.prefsStore(app)

    private fun <T> prefFlow(flow: Flow<T>, default: T): StateFlow<T> =
        flow.stateIn(viewModelScope, SharingStarted.Eagerly, default)

    // Defaults mirror PrefsStore (goal 8000, step length 70).
    val goal: StateFlow<Int> = prefFlow(prefs.goal, 8000)
    val heightCm: StateFlow<Int> = prefFlow(prefs.heightCm, 170)
    val weightKg: StateFlow<Int> = prefFlow(prefs.weightKg, 70)
    val stepLenCm: StateFlow<Int> = prefFlow(prefs.stepLenCm, 70)
    val units: StateFlow<String> = prefFlow(prefs.units, "metric")
    val theme: StateFlow<String> = prefFlow(prefs.theme, "system")
    val sensitivity: StateFlow<String> = prefFlow(prefs.sensitivity, "M")
    val treadmill: StateFlow<Boolean> = prefFlow(prefs.treadmill, false)
    /** Health Connect mode: OFF (default), R, RW. Default OFF, per spec. */
    val hcMode: StateFlow<String> = prefFlow(prefs.hcMode, "OFF")
    val batteryAsked: StateFlow<Boolean> = prefFlow(prefs.batteryAsked, false)

    val paused: StateFlow<Boolean> =
        repo.paused().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() {
        _message.value = null
    }

    // ---- writes ----

    fun setGoal(v: Int) {
        viewModelScope.launch {
            runCatching { repo.setGoal(v) }
                .onFailure { _message.value = "Could not save goal (${it.message})." }
        }
    }

    fun setHeightCm(v: Int) = launchPrefs("height") { prefs.setHeightCm(v) }
    fun setWeightKg(v: Int) = launchPrefs("weight") { prefs.setWeightKg(v) }
    fun setStepLenCm(v: Int) = launchPrefs("step length") { prefs.setStepLenCm(v) }
    fun setUnits(v: String) = launchPrefs("units") { prefs.setUnits(v) }
    fun setTheme(v: String) = launchPrefs("theme") { prefs.setTheme(v) }
    fun setSensitivity(v: String) = launchPrefs("sensitivity") { prefs.setSensitivity(v) }
    fun setTreadmill(v: Boolean) = launchPrefs("treadmill mode") { prefs.setTreadmill(v) }
    fun setHcMode(v: String) = launchPrefs("Health Connect") { prefs.setHcMode(v) }
    fun setBatteryAsked() = launchPrefs("battery choice") { prefs.setBatteryAsked(true) }

    fun setPaused(v: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setPaused(v) }
                .onFailure { _message.value = "Could not pause tracking (${it.message})." }
        }
    }

    fun clearManual() {
        viewModelScope.launch {
            runCatching { repo.clearManual() }
                .onSuccess { _message.value = "Manual corrections for today were cleared." }
                .onFailure { _message.value = "Could not clear (${it.message})." }
        }
    }

    private fun launchPrefs(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { _message.value = "Could not save $what (${it.message})." }
        }
    }

    // ---- export / backup / restore ----

    private suspend fun allDays(): List<Day> =
        runCatching { repo.history(365).first() }.getOrDefault(emptyList())

    private suspend fun prefsSnapshot(): Map<String, String> = mapOf(
        "goal" to goal.first().toString(),
        "heightCm" to heightCm.first().toString(),
        "weightKg" to weightKg.first().toString(),
        "stepLenCm" to stepLenCm.first().toString(),
        "units" to units.first(),
        "theme" to theme.first(),
        "sensitivity" to sensitivity.first(),
        "treadmill" to treadmill.first().toString(),
        "hcMode" to hcMode.first(),
    )

    fun exportCsv() {
        guardBusy()
        viewModelScope.launch {
            try {
                val file: File = BackupExport.exportCsv(appRef, allDays())
                _message.value = "Steps saved to ${file.name} in the Tally folder."
            } catch (e: Exception) {
                _message.value = "Export failed (${e.message})."
            } finally {
                _busy.value = false
            }
        }
    }

    fun backup() {
        guardBusy()
        viewModelScope.launch {
            try {
                val file: File = BackupExport.backup(appRef, allDays(), prefsSnapshot())
                _message.value = "Backup saved to ${file.name} in the Tally folder. " +
                    "Keep a copy somewhere safe — restoring replaces this phone's data."
            } catch (e: Exception) {
                _message.value = "Backup failed (${e.message})."
            } finally {
                _busy.value = false
            }
        }
    }

    fun restore(uri: Uri) {
        guardBusy()
        viewModelScope.launch {
            try {
                val outcome: RestoreOutcome = BackupExport.restore(appRef, uri)
                _message.value = outcome.userMessage
                if (outcome is RestoreOutcome.Success) applyRestore(outcome)
            } catch (e: Exception) {
                _message.value = "Restore failed (${e.message}). Nothing was changed."
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Applies validated restore data: Day rows go back through
     * [com.tally.steps.engine.StepRepository.importDays] (clamped, estimates
     * recomputed, atomic), then the remaining preferences. The user's current
     * goal is NEVER overwritten from backup. Validation already happened in
     * [BackupExport.restore]; the message says exactly what was applied.
     */
    private suspend fun applyRestore(outcome: RestoreOutcome.Success) {
        runCatching {
            repo.importDays(
                outcome.days.map {
                    Day(
                        epochDay = it.epochDay,
                        steps = it.steps,
                        distanceM = it.distanceM,
                        kcal = it.kcal,
                        activeMin = it.activeMin,
                        goal = it.goal,
                        manualDelta = it.manualDelta,
                        source = it.source,
                        updatedAt = it.updatedAt,
                    )
                },
            )
        }.onFailure {
            _message.value = "Backup checked but days could not be written (${it.message}). Nothing was changed."
            return
        }
        val p = outcome.prefs
        runCatching {
            p["heightCm"]?.toIntOrNull()?.let { prefs.setHeightCm(it) }
            p["weightKg"]?.toIntOrNull()?.let { prefs.setWeightKg(it) }
            p["stepLenCm"]?.toIntOrNull()?.let { prefs.setStepLenCm(it) }
            p["units"]?.let { prefs.setUnits(it) }
            p["theme"]?.let { prefs.setTheme(it) }
            p["sensitivity"]?.let { prefs.setSensitivity(it) }
            p["treadmill"]?.toBooleanStrictOrNull()?.let { prefs.setTreadmill(it) }
            // hcMode deliberately NOT restored: stays OFF until the user turns it on.
        }
        _message.value = "Restored ${outcome.days.size} day(s). Goal unchanged. " +
            "(Health Connect stays off)."
    }

    private fun guardBusy() {
        _busy.value = true
        _message.value = null
    }
}
