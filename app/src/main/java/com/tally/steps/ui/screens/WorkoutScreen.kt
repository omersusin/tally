package com.tally.steps.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.ui.workout.TargetKind
import com.tally.steps.ui.workout.WorkoutConfig
import com.tally.steps.ui.workout.WorkoutType
import com.tally.steps.ui.workout.WorkoutUiState
import com.tally.steps.ui.workout.WorkoutViewModel

private val MinTouch = Modifier.heightIn(min = 48.dp)

/**
 * S04 workout. Start takes at most 2 taps (pick type, tap Start — or 1 tap
 * via "Start free walk"). All types run step-only for now.
 */
@Composable
fun WorkoutScreen(vm: WorkoutViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()

    // Step-only: no location permission (GPS arrives later). Type switch only.
    fun setType(type: WorkoutType) {
        vm.setConfig(ui.config.copy(type = type, gpsAvailable = false))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (ui.phase) {
            WorkoutUiState.Phase.IDLE, WorkoutUiState.Phase.DONE -> {
                if (ui.phase == WorkoutUiState.Phase.DONE && ui.lastSummary != null) {
                    SummaryCard(ui = ui, onDismiss = vm::reset)
                }
                ConfigCard(
                    config = ui.config,
                    onType = ::setType,
                    onConfig = vm::setConfig,
                )
                Button(
                    onClick = { vm.start() },
                    modifier = Modifier.fillMaxWidth().then(MinTouch),
                ) {
                    Text("Start ${ui.config.type.label.lowercase()} workout")
                }
                OutlinedButton(
                    onClick = { vm.quickStart(WorkoutType.WALK) },
                    modifier = Modifier.fillMaxWidth().then(MinTouch),
                ) {
                    Text("Start free walk now")
                }
                Text(
                    text = "Counts steps with your phone's sensor. Distance is an estimate " +
                        "from your step length. Tally never uploads anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WorkoutUiState.Phase.ACTIVE, WorkoutUiState.Phase.PAUSED -> {
                ActiveCard(ui = ui)
                if (ui.phase == WorkoutUiState.Phase.ACTIVE) {
                    Button(
                        onClick = { vm.pause() },
                        modifier = Modifier.fillMaxWidth().then(MinTouch),
                    ) { Text("Pause") }
                } else {
                    if (ui.autoPaused) {
                        Text(
                            text = "Auto-paused: no steps for a while. " +
                                "Resume when you keep moving.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { vm.resume() },
                        modifier = Modifier.fillMaxWidth().then(MinTouch),
                    ) { Text("Resume") }
                }
                OutlinedButton(
                    onClick = { vm.finish() },
                    modifier = Modifier.fillMaxWidth().then(MinTouch),
                ) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun ConfigCard(
    config: WorkoutConfig,
    onType: (WorkoutType) -> Unit,
    onConfig: (WorkoutConfig) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WorkoutType.entries.forEach { t ->
                    FilterChip(
                        selected = config.type == t,
                        onClick = { onType(t) },
                        label = { Text(t.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            if (config.type == WorkoutType.TREADMILL) {
                Text(
                    text = "Treadmill mode: steps only. " +
                        "Distance = steps × your step length.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Distance comes from your step length. GPS tracking arrives later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Target", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TargetKind.entries.forEach { k ->
                    FilterChip(
                        selected = config.targetKind == k,
                        onClick = { onConfig(config.copy(targetKind = k)) },
                        label = { Text(k.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            when (config.targetKind) {
                TargetKind.STEPS -> NumberField(
                    label = "Step target",
                    value = config.targetSteps.toString(),
                    onValue = { v -> v?.let { onConfig(config.copy(targetSteps = it)) } },
                )
                TargetKind.DISTANCE -> NumberField(
                    label = "Distance target (m)",
                    value = config.targetDistanceM.toString(),
                    onValue = { v -> v?.let { onConfig(config.copy(targetDistanceM = it)) } },
                )
                TargetKind.TIME -> NumberField(
                    label = "Time target (min)",
                    value = config.targetTimeMin.toString(),
                    onValue = { v -> v?.let { onConfig(config.copy(targetTimeMin = it)) } },
                )
                TargetKind.FREE -> Unit
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = config.autoBreak,
                        onClick = { onConfig(config.copy(autoBreak = !config.autoBreak)) },
                        role = Role.Switch,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-break")
                    Text(
                        "Auto-pause after 60 s without steps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.autoBreak,
                    onCheckedChange = { onConfig(config.copy(autoBreak = it)) },
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValue(it.toIntOrNull()?.coerceIn(1, 1_000_000))
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActiveCard(ui: WorkoutUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (ui.phase == WorkoutUiState.Phase.PAUSED) "Paused" else "Recording…",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${ui.sessionSteps} steps",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = "About ${formatDistance(ui.distanceM)} · ${formatTime(ui.elapsedMs)}" +
                    if (ui.pausedMs > 0) " (paused ${formatTime(ui.pausedMs)})" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val frac = ui.targetFraction
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (ui.targetHit) "Target reached. Nice work."
                    else "${(frac * 100).toInt()}% of target",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Free workout — no target, stop whenever you like.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(ui: WorkoutUiState, onDismiss: () -> Unit) {
    val s = ui.lastSummary ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Workout saved", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${s.type.label}: ${s.steps} steps, about ${formatDistance(s.distanceM)}, " +
                    "${formatTime(s.elapsedMs)} moving" +
                    if (s.pausedMs > 0) " (${formatTime(s.pausedMs)} paused)" else "" + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.targetHit) Text("You hit your target.")
            ui.saveNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Back to setup") }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

private fun formatDistance(m: Float): String =
    if (m < 1000) "${m.toInt()} m" else "%.2f km".format(m / 1000)

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
