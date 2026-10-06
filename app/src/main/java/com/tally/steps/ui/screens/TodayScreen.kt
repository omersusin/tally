package com.tally.steps.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.ui.components.GoalRing
import com.tally.steps.ui.components.StatRow
import com.tally.steps.ui.theme.TallyElevation
import com.tally.steps.ui.theme.tabulated
import com.tally.steps.ui.today.TodayViewModel
import com.tally.steps.ui.workout.EngineBridge
import com.tally.steps.engine.GoalCoach
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/** S02 Today. Plain. Steady. Truthful. */
@Composable
fun TodayScreen(
    vm: TodayViewModel = viewModel(),
) {
    val day by vm.day.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    // Sensor-missing comes from StepRepository.hasSensor() via the ViewModel —
    // never from a Day.source sentinel (the engine never writes one).
    val sensorMissing by vm.sensorMissing.collectAsStateWithLifecycle()
    var showTargetPicker by rememberSaveable { mutableStateOf(false) }
    val fmt = NumberFormat.getIntegerInstance()

    val current = day
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Counting…", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val goalReached = current.goal > 0 && current.steps >= current.goal
    val floors by vm.floors.collectAsStateWithLifecycle()
    val vetoNote by vm.vetoNote.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            PauseResumeFab(paused = paused, onToggle = { vm.setPaused(!paused) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GoalRing(steps = current.steps, goal = current.goal, paused = paused)

            if (current.steps == 0 && current.manualDelta == 0) {
                Text(
                    text = "No steps yet. Put the phone in your pocket and walk.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            if (goalReached) {
                Text(
                    text = "Goal met. That's the true count — nicely walked.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (paused) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Counting paused. Your true count is held.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { vm.setPaused(false) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Resume") }
                    }
                }
            }
            if (sensorMissing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No step sensor found",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "This device reports no step counter, so the count stays at 0. Manual fixes below still work.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            StatRow(
                distanceM = current.distanceM,
                kcal = current.kcal,
                activeMin = current.activeMin,
                units = units,
            )

            if (current.manualDelta != 0) {
                Text(
                    text = "Includes ${if (current.manualDelta > 0) "+" else ""}${fmt.format(current.manualDelta)} manual fix (not from sensor).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val paceText = rememberPaceText(steps = current.steps, goal = current.goal)
            if (paceText != null) {
                Text(
                    text = paceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (vm.hasBarometer && floors > 0) {
                Text(
                    text = "$floors floor${if (floors == 1) "" else "s"} climbed today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (vetoNote != null) {
                Text(
                    text = vetoNote!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            var showCustomFix by rememberSaveable { mutableStateOf(false) }
            ManualEditRow(
                manualDelta = current.manualDelta,
                onPlus = { vm.nudgeManual(100) },
                onMinus = { vm.nudgeManual(-100) },
                onClear = { vm.clearManual() },
                onCustom = { showCustomFix = true },
            )
            if (showCustomFix) {
                CustomFixDialog(
                    onConfirm = { vm.nudgeManual(it); showCustomFix = false },
                    onDismiss = { showCustomFix = false },
                )
            }

            OutlinedButton(
                onClick = { showTargetPicker = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Goal: ${fmt.format(current.goal)} — Change")
            }

            GoalNudgeCard(onApplyGoal = { vm.setGoal(it) })
        }
    }

    if (showTargetPicker) {
        TargetPicker(
            current = current.goal,
            onConfirm = { vm.setGoal(it); showTargetPicker = false },
            onDismiss = { showTargetPicker = false },
        )
    }
}

@Composable
private fun rememberPaceText(steps: Int, goal: Int): String? {
    if (steps <= 0 || goalReached(steps, goal)) return null
    val zone = ZoneId.systemDefault()
    val dayStartMs = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    val (projected, etaMin) = GoalCoach.projectDay(
        stepsNow = steps,
        goal = goal,
        dayStartMs = dayStartMs,
        nowMs = System.currentTimeMillis(),
    )
    val fmt = NumberFormat.getIntegerInstance()
    val eta = etaMin?.let { " Goal in about ${formatEta(it)} at this pace." } ?: ""
    return "On pace for ~${fmt.format(projected)} by midnight.$eta"
}

private fun goalReached(steps: Int, goal: Int): Boolean = goal > 0 && steps >= goal

private fun formatEta(mins: Int): String {
    if (mins < 60) return "$mins min"
    val h = mins / 60
    val m = mins % 60
    return if (m == 0) "$h h" else "$h h $m min"
}

@Composable
private fun ManualEditRow(
    manualDelta: Int,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onClear: () -> Unit,
    onCustom: () -> Unit,
) {
    val fmt = NumberFormat.getIntegerInstance()
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Manual step fix, currently $manualDelta"
                },
        ) {
            OutlinedButton(
                onClick = onMinus,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Remove 100 manual steps")
            }
            Text(
                text = fmt.format(manualDelta),
                style = MaterialTheme.typography.titleMedium.tabulated(),
            )
            OutlinedButton(
                onClick = onPlus,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add 100 manual steps")
            }
            TextButton(
                onClick = onCustom,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Custom")
            }
            if (manualDelta != 0) {
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun PauseResumeFab(paused: Boolean, onToggle: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onToggle,
        icon = {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = null,
            )
        },
        text = { Text(if (paused) "Resume counting" else "Pause counting") },
        modifier = Modifier
            .heightIn(min = 56.dp)
            .semantics {
                contentDescription = if (paused) "Resume counting" else "Pause counting"
            },
    )
}

/** Custom manual fix: free amount, committed once on confirm — never mid-typing. */
@Composable
private fun CustomFixDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var raw by rememberSaveable { mutableStateOf("") }
    // Signed digits only; blank = nothing to commit (button stays disabled).
    val amount = raw.toIntOrNull()?.coerceIn(-10_000, 10_000)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom step fix") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Adds to today's manual fix (use − for too many). Sensor steps are never touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { v ->
                        if (v.length <= 7 && (v.isEmpty() || v == "-" || v.toIntOrNull() != null)) raw = v
                    },
                    label = { Text("Steps (e.g. 450 or -200)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { amount?.let(onConfirm) },
                enabled = amount != null && amount != 0,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Add fix") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel") }
        },
    )
}
/** Stepper dialog: − value + plus 5k / 8k / 10k presets. Real buttons, 48dp. */
@Composable
private fun TargetPicker(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf(current) }
    val fmt = NumberFormat.getIntegerInstance()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "Goal ${fmt.format(value)} steps"
                    },
                ) {
                    OutlinedButton(
                        onClick = { value = (value - 500).coerceAtLeast(1_000) },
                        enabled = value > 1_000,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Lower goal by 500")
                    }
                    Text(
                        text = fmt.format(value),
                        style = MaterialTheme.typography.headlineSmall.tabulated(),
                    )
                    OutlinedButton(
                        onClick = { value = (value + 500).coerceAtMost(50_000) },
                        enabled = value < 50_000,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Raise goal by 500")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5_000, 8_000, 10_000).forEach { preset ->
                        OutlinedButton(
                            onClick = { value = preset },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("${preset / 1_000}k")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Set goal") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Quiet once-a-day goal nudge. Shows only when GoalCoach has something worth
 * saying; Apply and Not now both record today so it never nags twice.
 * Plain text, never badge-shame. 48dp buttons with screen-reader labels.
 */
@Composable
private fun GoalNudgeCard(onApplyGoal: (Int) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val repo = remember { EngineBridge.stepRepository(app) }
    val prefs = remember { EngineBridge.prefsStore(app) }
    val scope = rememberCoroutineScope()
    val historyFlow = remember { repo.history(8) }
    val goalFlow = remember { prefs.goal }
    val nudgeDayFlow = remember { prefs.lastNudgeDay }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val goal by goalFlow.collectAsStateWithLifecycle(initialValue = 8_000)
    val lastNudge by nudgeDayFlow.collectAsStateWithLifecycle(initialValue = "")
    val today = LocalDate.now()
    val todayEpoch = today.toEpochDay()
    val nudge = remember(history, goal) {
        GoalCoach.nudgeFor(
            history.filter { it.epochDay < todayEpoch }
                .sortedBy { it.epochDay }
                .takeLast(7)
                .map { it.steps + it.manualDelta },
            goal,
        )
    }
    if (nudge == null || lastNudge == today.toString()) return
    val fmt = NumberFormat.getIntegerInstance()
    val todayStr = today.toString()
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Walking ~${fmt.format(nudge.dailyAverage)} a day lately. " +
                    "Raise goal to ${fmt.format(nudge.suggestedGoal)}?",
                style = MaterialTheme.typography.bodyLarge.tabulated(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onApplyGoal(nudge.suggestedGoal)
                        scope.launch { prefs.setLastNudgeDay(todayStr) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription =
                            "Apply suggested goal of ${fmt.format(nudge.suggestedGoal)} steps"
                    },
                ) { Text("Apply") }
                OutlinedButton(
                    onClick = { scope.launch { prefs.setLastNudgeDay(todayStr) } },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Dismiss goal suggestion"
                    },
                ) { Text("Not now") }
            }
        }
    }
}
