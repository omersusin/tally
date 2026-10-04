package com.tally.steps.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.ui.settings.SettingsViewModel

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
                Text(
                    text = it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Section("Daily goal") {
            Text("$goal steps a day", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = goal.toFloat(),
                onValueChange = { vm.setGoal(it.toInt()) },
                valueRange = 1000f..20000f,
                steps = 18,
                modifier = Modifier.fillMaxWidth(),
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
                "Sensitivity only affects the fallback sensor and workout rhythm. " +
                    "It never rewrites your phone's own step counter.",
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
                title = "Sync with Health Connect",
                subtitle = when (hcMode) {
                    "OFF" -> "Off. Tally reads and writes nothing."
                    "R" -> "Read only: fills gaps when your phone was off or in a locker."
                    else -> "Read and write: also shares Tally's count with other apps."
                },
                checked = hcMode != "OFF",
                onChecked = { vm.setHcMode(if (it) "R" else "OFF") },
            )
            Text(
                "Off until you turn it on. Turning it on never deletes anything, " +
                    "and turning it off stops all syncing immediately.",
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
            OutlinedButton(
                onClick = vm::clearManual,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Clear today's manual corrections") }
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
    var text by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("$label ($unit)") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            singleLine = true,
            supportingText = { Text("Tap ✓ on the keyboard to save") },
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
            steps = "Settings → Apps → Tally → Autostart ON; " +
                "Battery saver → No restrictions; " +
                "lock Tally in the Recents screen.",
        )
        OemCard(
            brand = "Oppo / Vivo / Realme",
            steps = "Settings → Battery → Tally → Allow background activity; " +
                "enable Tally in Startup manager / Autostart.",
        )
    }
}

@Composable
private fun OemCard(brand: String, steps: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(brand, style = MaterialTheme.typography.titleSmall)
            Text(
                steps,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    return try {
        context.startActivity(direct)
        false // user decides in the system dialog; card re-checks next visit
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: ActivityNotFoundException) {
            // No battery settings on this device; nothing more we can honestly do.
        }
        false
    }
}
