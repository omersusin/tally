package com.tally.steps.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.data.Workout
import com.tally.steps.ui.components.WorkoutMap
import com.tally.steps.ui.workout.TargetKind
import com.tally.steps.ui.workout.WorkoutConfig
import com.tally.steps.ui.workout.WorkoutType
import com.tally.steps.ui.workout.WorkoutUiState
import com.tally.steps.ui.workout.WorkoutViewModel

private val MinTouch = Modifier.heightIn(min = 48.dp)

private fun hasWorkoutLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

/**
 * S04 workout. Start takes at most 2 taps (pick type, tap Start — or 1 tap
 * via "Start free walk"). GPS types record a live route when location is
 * granted; denied (or treadmill) falls back to step-only — honestly labelled.
 */
@Composable
fun WorkoutScreen(vm: WorkoutViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val points by vm.trackPoints.collectAsState()
    val history by vm.workouts.collectAsState()
    val context = LocalContext.current
    var locGranted by remember { mutableStateOf(hasWorkoutLocation(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasWorkoutLocation(context)
        locGranted = granted
        vm.setGpsAvailable(granted)
        // Mid-session grant: join the service pipe from here (no-op unless ACTIVE).
        if (granted) vm.retryGps()
    }

    fun setType(type: WorkoutType) {
        vm.setConfig(
            ui.config.copy(
                type = type,
                gpsAvailable = type.usesGps && (locGranted || hasWorkoutLocation(context)),
            ),
        )
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
                    SummaryCard(ui = ui, gpsPoints = points.size, onDismiss = vm::reset)
                }
                if (ui.config.type.usesGps && !ui.config.gpsAvailable) {
                    GpsCard(
                        granted = locGranted,
                        onEnable = {
                            if (hasWorkoutLocation(context)) {
                                locGranted = true
                                vm.setGpsAvailable(true)
                            } else {
                                launcher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            }
                        },
                    )
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
                    onClick = { vm.quickStart() },
                    modifier = Modifier.fillMaxWidth().then(MinTouch),
                ) {
                    Text("Start now (free target)")
                }
                Text(
                    text = "Counts steps with your phone's sensor. Distance is an estimate " +
                        "from your step length. Tally never uploads anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WorkoutHistoryList(
                    history = history,
                    notice = ui.notice,
                    onDismissNotice = vm::clearNotice,
                    onDelete = vm::deleteWorkout,
                    onExportGpx = vm::exportGpx,
                )
            }
            WorkoutUiState.Phase.ACTIVE, WorkoutUiState.Phase.PAUSED -> {
                var showDiscard by remember { mutableStateOf(false) }
                ActiveCard(ui = ui, gpsPoints = points.size)
                // Actions BEFORE the map: reachable without scrolling past it mid-stride.
                if (ui.phase == WorkoutUiState.Phase.ACTIVE) {
                    Button(
                        onClick = { vm.pause() },
                        modifier = Modifier.fillMaxWidth().then(MinTouch),
                    ) { Text("Pause") }
                } else {
                    if (ui.autoPaused) {
                        Text(
                            text = "Auto-break: no steps for a minute. " +
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.finish() },
                        modifier = Modifier.weight(1f).then(MinTouch),
                    ) { Text(if (ui.targetHit) "Finish — target hit" else "Finish") }
                    OutlinedButton(
                        onClick = { showDiscard = true },
                        modifier = Modifier.weight(1f).then(MinTouch),
                    ) { Text("Discard") }
                }
                if (showDiscard) {
                    AlertDialog(
                        onDismissRequest = { showDiscard = false },
                        title = { Text("Discard this workout?") },
                        text = {
                            Text(
                                if (ui.sessionSteps == 0) {
                                    "No steps recorded yet. Discarding deletes " +
                                        "the session; today's count is untouched."
                                } else {
                                    "This deletes the session (including its route). " +
                                        "The ${ui.sessionSteps} steps stay in today's count."
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showDiscard = false; vm.discard() },
                                modifier = Modifier.then(MinTouch),
                            ) { Text("Discard session") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showDiscard = false },
                                modifier = Modifier.then(MinTouch),
                            ) { Text("Keep going") }
                        },
                    )
                }
                if (ui.targetHit && ui.phase == WorkoutUiState.Phase.ACTIVE) {
                    Text(
                        text = "Target hit — nice. The timer keeps running until you finish.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.config.gpsAvailable && ui.config.type.usesGps) {
                    WorkoutMap(points = points)
                } else if (ui.config.type.usesGps) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Step-only: no route is recording.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Grant location to draw the route from here — " +
                                    "steps so far are kept.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    if (hasWorkoutLocation(context)) {
                                        locGranted = true
                                        vm.setGpsAvailable(true)
                                        vm.retryGps()
                                    } else {
                                        launcher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().then(MinTouch),
                            ) { Text("Enable route tracking") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GpsCard(granted: Boolean, onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Route tracking", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (granted) {
                    "Location granted — your route records with this workout."
                } else {
                    "Allow location to draw your route. Without it this " +
                        "workout still counts steps, just no map."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth().then(MinTouch),
                ) { Text("Enable GPS route") }
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
                    text = if (config.gpsAvailable) {
                        "GPS route on. Bad fixes (accuracy over 20 m, jumps " +
                            "faster than 3.5 m/s) are dropped automatically."
                    } else {
                        "Step-only until location is allowed. " +
                            "Distance comes from your step length."
                    },
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
                        "Pauses the timer after 60 s without steps.",
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
    // Local draft keyed on the field, committed on Done only: typing "20000"
    // never briefly sets the target to 2 mid-keystroke.
    var text by remember(label) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter { c -> c.isDigit() }.take(7) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { text.toIntOrNull()?.coerceIn(1, 1_000_000)?.let(onValue) },
        ),
        supportingText = {
            Text(
                text = "Saved target: $value · tap Done to apply",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActiveCard(ui: WorkoutUiState, gpsPoints: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = workoutStatusDescription(ui, gpsPoints)
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (ui.phase == WorkoutUiState.Phase.PAUSED) "Paused" else "Recording ${ui.config.type.label.lowercase()}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${ui.sessionSteps} steps",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = "About ${formatDistance(ui.distanceM)} · ${formatTime(ui.elapsedMs)} moving" +
                    if (ui.pausedMs > 0) " · ${formatTime(ui.pausedMs)} paused" else "",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (ui.config.gpsAvailable && ui.config.type.usesGps) {
                Text(
                    text = if (gpsPoints > 0) {
                        if (gpsPoints == 1) "1 GPS point on the route." else "$gpsPoints GPS points on the route."
                    } else {
                        "Waiting for GPS lock… steps still count."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val frac = ui.targetFraction
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = targetRemainingText(ui),
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

/** Absolute remaining target for a mid-stride glance: "1,340 of 2,000 steps left". */
private fun targetRemainingText(ui: WorkoutUiState): String {
    if (ui.targetHit) return "Target reached. Nice work."
    val fmt = java.text.NumberFormat.getIntegerInstance()
    return when (ui.config.targetKind) {
        TargetKind.STEPS -> {
            val left = (ui.config.targetSteps - ui.sessionSteps).coerceAtLeast(0)
            "${fmt.format(left)} of ${fmt.format(ui.config.targetSteps)} steps left"
        }
        TargetKind.DISTANCE -> {
            val leftM = (ui.config.targetDistanceM - ui.distanceM).coerceAtLeast(0f)
            "${formatDistance(leftM)} of ${formatDistance(ui.config.targetDistanceM.toFloat())} left"
        }
        TargetKind.TIME -> {
            val leftMs = (ui.config.targetTimeMin * 60_000L - ui.elapsedMs).coerceAtLeast(0L)
            "${formatTime(leftMs)} of ${ui.config.targetTimeMin} min left"
        }
        TargetKind.FREE -> ""
    }
}

private fun workoutStatusDescription(ui: WorkoutUiState, gpsPoints: Int): String {
    val phase = if (ui.phase == WorkoutUiState.Phase.PAUSED) "Paused" else "Recording"
    return "$phase ${ui.config.type.label}: ${ui.sessionSteps} steps, " +
        "about ${formatDistance(ui.distanceM)}, ${formatTime(ui.elapsedMs)} moving" +
        if (ui.pausedMs > 0) ", ${formatTime(ui.pausedMs)} paused" else ""
}

@Composable
private fun SummaryCard(ui: WorkoutUiState, gpsPoints: Int, onDismiss: () -> Unit) {
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
            if (gpsPoints >= 2) {
                Text(
                    text = "Route saved with $gpsPoints GPS points.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/** Past sessions, newest first. Deleting never touches day totals (sensor truth). */
@Composable
private fun WorkoutHistoryList(
    history: List<Workout>,
    notice: String?,
    onDismissNotice: () -> Unit,
    onDelete: (Workout) -> Unit,
    onExportGpx: (Workout) -> Unit,
) {
    if (history.isEmpty() && notice == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Past workouts", style = MaterialTheme.typography.titleMedium)
            notice?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissNotice) { Text("OK") }
                }
            }
            if (history.isEmpty()) {
                Text(
                    text = "Nothing yet — your finished workouts will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            history.take(20).forEach { w ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${w.type.replaceFirstChar { c -> c.uppercase() }} · " +
                            "${java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(w.startMs))} · " +
                            "${w.steps} steps · ${formatDistance(w.distanceM)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (w.gpsPolyline != null) {
                            TextButton(
                                onClick = { onExportGpx(w) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Export route") }
                        }
                        var confirmDelete by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Delete") }
                        if (confirmDelete) {
                            AlertDialog(
                                onDismissRequest = { confirmDelete = false },
                                title = { Text("Delete this workout?") },
                                text = {
                                    Text("The route and session go away. Day totals stay.")
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            confirmDelete = false
                                            onDelete(w)
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("Delete workout") }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { confirmDelete = false },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("Keep") }
                                },
                            )
                        }
                    }
                }
            }
            if (history.size > 20) {
                Text(
                    text = "Showing the 20 most recent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
