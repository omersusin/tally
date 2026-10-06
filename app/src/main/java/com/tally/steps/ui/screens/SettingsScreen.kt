package com.tally.steps.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.export.PdfExport
import com.tally.steps.ui.settings.SettingsViewModel
import com.tally.steps.ui.workout.EngineBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val MinTouch = Modifier.heightIn(min = 48.dp)

/**
 * S06 settings: goal, body, units, theme (incl. AMOLED), sensitivity,
 * treadmill, Health Connect (default OFF), tracking pause, battery/OEM cards,
 * export/backup/restore. Every row is labelled in plain words.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val goal by vm.goal.collectAsState()
    val heightCm by vm.heightCm.collectAsState()
    val weightKg by vm.weightKg.collectAsState()
    val stepLenCm by vm.stepLenCm.collectAsState()
    val units by vm.units.collectAsState()
    val theme by vm.theme.collectAsState()
    val sensitivity by vm.sensitivity.collectAsState()
    val treadmill by vm.treadmill.collectAsState()
    val hcMode by vm.hcMode.collectAsState()
    val paused by vm.paused.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val batteryAsked by vm.batteryAsked.collectAsState()

    // hcAuto + PDF export bypass SettingsViewModel (engine-owned prefs/repo
    // read directly so no other Settings wiring changes).
    val app = context.applicationContext as Application
    val repo = remember(app) { EngineBridge.stepRepository(app) }
    val prefs = remember(app) { EngineBridge.prefsStore(app) }
    val hcAuto by prefs.hcAuto.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var hcAutoNote by remember { mutableStateOf<String?>(null) }
    var pdfNote by remember { mutableStateOf<String?>(null) }
    var pdfBusy by remember { mutableStateOf(false) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) vm.restore(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        message?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = vm::clearMessage,
                        modifier = Modifier.then(MinTouch),
                    ) { Text("Dismiss") }
                }
            }
        }

        Section("Daily goal") {
            Text("$goal steps a day", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = goal.toFloat(),
                onValueChange = { vm.setGoal((it.toInt() / 500 * 500).coerceIn(1_000, 50_000)) },
                valueRange = 1000f..50000f,
                steps = 97,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Daily goal, $goal steps" },
            )
            Text(
                "Your awards and streaks use this number. " +
                    "Changing it does not rewrite past days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("About you (for distance and calorie estimates)") {
            NumberRow(
                label = "Height",
                unit = if (units == "imperial") "in" else "cm",
                value = if (units == "imperial") "%.1f".format(heightCm / 2.54) else "$heightCm",
                onDone = { raw ->
                    raw.toFloatOrNull()?.let { v ->
                        val cm = if (units == "imperial") (v * 2.54).toInt() else v.toInt()
                        vm.setHeightCm(cm)
                    }
                },
            )
            NumberRow(
                label = "Weight",
                unit = if (units == "imperial") "lb" else "kg",
                value = if (units == "imperial") trimNum(weightKg * 2.205) else "$weightKg",
                onDone = { raw ->
                    raw.toFloatOrNull()?.let { v ->
                        vm.setWeightKg(if (units == "imperial") (v / 2.205).toInt() else v.toInt())
                    }
                },
            )
            NumberRow(
                label = "Step length",
                unit = if (units == "imperial") "in" else "cm",
                value = if (units == "imperial") "%.1f".format(stepLenCm / 2.54) else "$stepLenCm",
                onDone = { raw ->
                    raw.toFloatOrNull()?.let { v ->
                        val cm = if (units == "imperial") (v * 2.54).toInt() else v.toInt()
                        vm.setStepLenCm(cm)
                    }
                },
            )
            Text(
                "Estimates only. Tally counts steps exactly; distance and calories are approximations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Units") {
            ChipRow(
                options = listOf("metric" to "Metric", "imperial" to "Imperial"),
                selected = units,
                onSelect = vm::setUnits,
            )
        }

        Section("Appearance") {
            ChipRow(
                options = listOf(
                    "system" to "System",
                    "light" to "Light",
                    "dark" to "Dark",
                    "amoled" to "Black (AMOLED)",
                ),
                selected = theme,
                onSelect = vm::setTheme,
            )
            Text(
                "Black paints pixels fully off on OLED screens, which can save battery. " +
                    "It looks the same as Dark on other screens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Step detection sensitivity") {
            ChipRow(
                options = listOf(
                    "L" to "Low",
                    "M" to "Medium",
                    "H" to "High",
                ),
                selected = sensitivity,
                onSelect = vm::setSensitivity,
            )
            Text(
                text = when (sensitivity) {
                    "L" -> "Low: only clear, steady walking counts. Use this if slow shuffling is being counted."
                    "H" -> "High: catches gentle movement too. Use this if real steps are being missed."
                    else -> "Medium: right for most people and most phones."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Sensitivity sets how much movement makes a minute count as " +
                    "active. It never changes your step count.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Workouts") {
            ToggleRow(
                title = "Treadmill mode",
                subtitle = "Steps only, no GPS. Distance = steps × your step length.",
                checked = treadmill,
                onChecked = vm::setTreadmill,
            )
        }

        Section("Health Connect") {
            ToggleRow(
                title = "Read steps from Health Connect",
                subtitle = if (hcMode != "OFF") {
                    "On: fills gaps when your phone was off or in a locker. " +
                        "Never overwrites a larger count on this phone."
                } else {
                    "Off. Tally reads nothing."
                },
                checked = hcMode != "OFF",
                onChecked = { on ->
                    vm.setHcMode(if (on) (if (hcMode == "RW") "RW" else "R") else "OFF")
                },
            )
            ToggleRow(
                title = "Share steps with Health Connect",
                subtitle = if (hcMode == "RW") {
                    "On: other apps can read Tally's count."
                } else {
                    "Off: read-only. Turning this on also turns read on."
                },
                checked = hcMode == "RW",
                onChecked = { on -> vm.setHcMode(if (on) "RW" else "R") },
            )
            ToggleRow(
                title = "Auto sync in background",
                subtitle = "Once on and permission is granted, it stays on — " +
                    "even if you later remove the permission in Health Connect. " +
                    "Turn it off here to stop.",
                checked = hcAuto,
                onChecked = { on ->
                    if (!on) {
                        scope.launch { runCatching { repo.setHcAuto(false) } }
                    } else {
                        scope.launch {
                            val ok = runCatching { repo.latchHcAuto() }.getOrDefault(false)
                            hcAutoNote = if (ok) {
                                null
                            } else {
                                "Health Connect permission is not granted yet, " +
                                    "so auto sync was not turned on."
                            }
                        }
                    }
                },
            )
            hcAutoNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "All three are off until you turn them on. Turning read or share " +
                    "on never deletes anything, and turning them off stops all " +
                    "syncing immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Tracking") {
            ToggleRow(
                title = "Pause step counting",
                subtitle = if (paused) "Paused. Your history stays put."
                else "Counting now.",
                checked = paused,
                onChecked = vm::setPaused,
            )
            var showClearConfirm by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Clear today's manual corrections") }
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("Clear manual corrections?") },
                    text = { Text("Today's sensor steps stay. Only your manual fix goes back to 0.") },
                    confirmButton = {
                        TextButton(
                            onClick = { showClearConfirm = false; vm.clearManual() },
                            modifier = Modifier.then(MinTouch),
                        ) { Text("Clear fix") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showClearConfirm = false },
                            modifier = Modifier.then(MinTouch),
                        ) { Text("Keep") }
                    },
                )
            }
        }

        BatteryCard(
            context = context,
            batteryAsked = batteryAsked,
            onAsked = vm::setBatteryAsked,
        )

        Section("Your data") {
            Text(
                "Everything lives on this phone. Export and backups are files you keep — " +
                    "Tally sends nothing anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = vm::exportCsv,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Export steps as CSV") }
            OutlinedButton(
                onClick = {
                    pdfBusy = true
                    pdfNote = null
                    scope.launch {
                        try {
                            val days = runCatching { repo.history(365).first() }
                                .getOrDefault(emptyList())
                            val file = PdfExport.exportDays(context, days)
                            pdfNote = "Summary saved to ${file.name} in the Tally folder."
                        } catch (e: Exception) {
                            pdfNote = "PDF export failed (${e.message})."
                        } finally {
                            pdfBusy = false
                        }
                    }
                },
                enabled = !busy && !pdfBusy,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Export summary as PDF") }
            pdfNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = vm::backup,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Back up (ZIP with settings)") }
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Restore from backup") }
        }

        Section("Intro") {
            Text(
                "Replay the first-run pages any time — permissions, battery setup, how counting works.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = vm::replayIntro,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Show intro again") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChecked(!checked) }, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NumberRow(label: String, unit: String, value: String, onDone: (String) -> Unit) {
    // Draft keyed on the field, committed on Done only; invalid input shows an
    // error instead of silently dropping.
    var text by remember(label) { mutableStateOf(value) }
    var error by remember(label) { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.filter { c -> c.isDigit() || c == '.' }.take(7)
                error = null
            },
            label = { Text("$label ($unit)") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (text.toFloatOrNull() == null) {
                        error = "Enter a number like 170, then Done"
                    } else {
                        onDone(text)
                    }
                },
            ),
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: "Saved: $value · tap Done to apply") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BatteryCard(context: Context, batteryAsked: Boolean, onAsked: () -> Unit) {
    val pm = remember {
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    val exempt = remember {
        runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
    var nowExempt by remember(exempt) { mutableStateOf(exempt) }
    Section("Battery") {
        Text(
            text = if (nowExempt) {
                "Tally is exempt from battery optimization. Counting keeps running overnight."
            } else {
                "Tally is NOT exempt from battery optimization. " +
                    "Some phones will stop the step counter overnight until you allow it."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                onAsked() // remember we asked — no nagging, per batteryAsked
                nowExempt = openBatteryExemption(context)
                    || runCatching {
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                    }.getOrDefault(false)
            },
            modifier = Modifier.fillMaxWidth().then(MinTouch),
        ) { Text(if (nowExempt) "Background running allowed" else "Allow running in background") }
        if (batteryAsked && !nowExempt) {
            Text(
                "You chose to skip this before. Counting still works; " +
                    "some phones just pause it overnight.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "If your brand kills apps anyway, also check below. " +
                "This is a phone-maker issue, not a Tally bug.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OemCard(
            brand = "Xiaomi / Redmi / POCO",
            steps = OemXiaomiSteps,
            note = OemXiaomiNote,
        )
        OemCard(
            brand = "Huawei / Honor",
            steps = OemHuaweiSteps,
            note = OemHuaweiNote,
        )
        OemCard(
            brand = "Samsung",
            steps = OemSamsungSteps,
            note = OemSamsungNote,
        )
        OemCard(
            brand = "Oppo / Vivo / Realme",
            steps = OemOppoSteps,
            note = null,
        )
    }
}

// OEM steps duplicated in SettingsScreen.kt and OnboardingScreen.kt — keep in sync.
private val OemXiaomiSteps = listOf(
    "Open Recents, find Tally, then lock it: tap the padlock icon (or swipe down on the card).",
    "Settings → Apps → Tally → Autostart: turn ON.",
    "Settings → Battery → Tally: set to No restrictions.",
    "MIUI 14: also allow Background autostart for Tally.",
)
private const val OemXiaomiNote =
    "Some MIUI versions offer “Turn off MIUI optimization” under Developer options. " +
        "It can help, but it resets some system settings — only try it if counting still stops."
private val OemHuaweiSteps = listOf(
    "Settings → Battery → App launch → Tally: turn OFF auto, choose Manage manually, turn all switches ON.",
    "Battery optimization → Tally: set to Allow (ignore battery optimization).",
    "Older EMUI: Settings → Protected apps: turn ON for Tally.",
)
private const val OemHuaweiNote =
    "EMUI 9+ advanced only: PowerGenie can still stop apps. " +
        "If you know what adb is: adb shell pm uninstall --user 0 com.huawei.powergenie"
private val OemSamsungSteps = listOf(
    "Settings → Battery: turn Adaptive battery OFF (or exempt Tally).",
    "Settings → Battery → Background usage limits → Never sleeping apps: add Tally.",
    "Settings → Apps → Tally → Battery: set to Unrestricted.",
    "Settings → Apps → Tally → Alarms & reminders: Allow, so the counter can restart on time.",
)
private const val OemSamsungNote =
    "Warning: Samsung sometimes puts apps back to sleep after a system update " +
        "or about 3 days idle. If counting stops, check this list again."
private val OemOppoSteps = listOf(
    "Settings → Startup manager (or Autostart): allow Tally to start automatically.",
    "Settings → Battery → Tally: allow background activity.",
    "Battery optimization → Tally: set to Not optimized.",
    "Open Recents and lock Tally (padlock) so it is not swiped away.",
)
private const val OemTestLine =
    "Test it: lock your phone for 10 minutes, walk a little, then check the count."

@Composable
private fun OemCard(brand: String, steps: List<String>, note: String?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide $brand steps" else "Show $brand steps",
                ) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(brand, style = MaterialTheme.typography.titleSmall)
            Text(
                if (expanded) "Hide" else "Show steps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                steps.forEachIndexed { i, step ->
                    Text(
                        "${i + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    OemTestLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun trimNum(v: Double): String =
    if (v == v.toInt().toDouble()) "${v.toInt()}" else "%.1f".format(v)

/** Returns true if exemption is (now) granted. Never throws. */
private fun openBatteryExemption(context: Context): Boolean {
    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)) {
        return true
    }
    // No REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (Play-restricted):
    // open the system list and let the user pick Tally. NEW_TASK because the
    // context may not be an Activity.
    return try {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        false // user decides in system settings; card re-checks next visit
    } catch (e: Exception) {
        // No battery settings on this device; nothing more we can honestly do.
        false
    }
}
