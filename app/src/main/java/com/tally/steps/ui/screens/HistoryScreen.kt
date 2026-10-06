package com.tally.steps.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.data.Day
import com.tally.steps.engine.GoalCoach
import com.tally.steps.ui.components.BarChart
import com.tally.steps.ui.components.HistoryRange
import com.tally.steps.ui.components.RangeSwitch
import com.tally.steps.ui.history.HistoryViewModel
import com.tally.steps.ui.theme.TallyElevation
import com.tally.steps.ui.theme.tabulated
import com.tally.steps.ui.workout.EngineBridge
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** S03 History: Canvas bars + segmented range + day detail + streak + text fallback. */
@Composable
fun HistoryScreen(
    vm: HistoryViewModel = viewModel(),
    onGoToday: () -> Unit = {},
) {
    val allDays by vm.days.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(HistoryRange.WEEK) }
    // Reset the selection whenever the range changes (rememberSaveable keyed on range).
    var selectedEpoch by rememberSaveable(range) { mutableStateOf<Long?>(null) }

    val visible: List<Day> = when (range) {
        HistoryRange.WEEK -> allDays.takeLast(7)
        HistoryRange.MONTH -> allDays.takeLast(30)
    }
    val detail: Day? = visible.find { it.epochDay == selectedEpoch } ?: visible.lastOrNull()

    if (allDays.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No history yet. Come back after your first walk.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onGoToday,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("See today")
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RangeSwitch(
            selected = range,
            onSelect = {
                range = it
                vm.setLimit(it.days)
            },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                WeekSummary(allDays = allDays)
                BarChart(
                    days = visible,
                    selectedEpoch = selectedEpoch ?: visible.lastOrNull()?.epochDay,
                    onSelect = { selectedEpoch = it },
                )
            }
        }

        detail?.let { DayDetail(it) }

        StreakSummary(days = allDays)

        WorkoutHistory()
    }
}

@Composable
private fun WorkoutHistory(
    vm: HistoryViewModel = viewModel(),
) {
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    if (workouts.isEmpty()) return
    val fmt = NumberFormat.getIntegerInstance()
    val zone = ZoneId.systemDefault()
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Workouts", style = MaterialTheme.typography.titleMedium)
            workouts.take(10).forEach { w ->
                val date = Instant.ofEpochMilli(w.startMs).atZone(zone).toLocalDate()
                    .format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                val mins = ((w.endMs - w.startMs - w.pausedMs) / 60_000L).coerceAtLeast(0)
                Text(
                    text = "$date · ${w.type} · ${fmt.format(w.steps)} steps · " +
                        "${"%.1f".format(w.distanceM / 1000f)} km · $mins min" +
                        if (w.gpsPolyline == null) " · no map" else "",
                    style = MaterialTheme.typography.bodyMedium.tabulated(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayDetail(day: Day) {
    val fmt = NumberFormat.getIntegerInstance()
    val date = runCatching {
        LocalDate.ofEpochDay(day.epochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }.getOrDefault("")
    val context = LocalContext.current
    val repo = remember { EngineBridge.stepRepository(context.applicationContext as Application) }
    val scope = rememberCoroutineScope()
    val todayEpoch = LocalDate.now().toEpochDay()
    var restMessage by remember(day.epochDay) { mutableStateOf<String?>(null) }
    // Eligible: past day that missed its goal and isn't rest yet. Otherwise the
    // button stays hidden — no dead buttons, no nag.
    val restEligible = !day.restDay && day.epochDay < todayEpoch &&
        day.goal > 0 && day.steps < day.goal
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = date, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${fmt.format(day.steps)} steps of ${fmt.format(day.goal)} goal",
                style = MaterialTheme.typography.bodyLarge.tabulated(),
            )
            Text(
                text = "${"%.1f".format(day.distanceM / 1000f)} km · " +
                    "${day.kcal.toInt()} kcal · ${day.activeMin} min active" +
                    if (day.floors > 0) " · ${day.floors} floors" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (day.manualDelta != 0) {
                Text(
                    text = "Includes ${if (day.manualDelta > 0) "+" else ""}${fmt.format(day.manualDelta)} manual fix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (day.restDay) {
                Text(
                    text = "Rest day — streak preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (restEligible) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            restMessage = if (repo.markRestDay(day.epochDay)) {
                                "Rest day saved — streak preserved."
                            } else {
                                "Only one rest day per week."
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Mark as rest day")
                }
            }
            restMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 7-day total + daily average + vs prior week. Plain totals, no judgement. */
@Composable
private fun WeekSummary(allDays: List<Day>) {
    val fmt = NumberFormat.getIntegerInstance()
    val today = LocalDate.now().toEpochDay()
    val last7 = allDays.filter { it.epochDay <= today }.takeLast(7)
    if (last7.isEmpty()) return
    val total = last7.sumOf { it.steps + it.manualDelta }
    val avg = total / last7.size
    val prior = allDays.filter { it.epochDay < (last7.firstOrNull()?.epochDay ?: today) }.takeLast(7)
    val priorTotal = prior.sumOf { it.steps + it.manualDelta }
    val delta = if (prior.size == 7 && priorTotal > 0) {
        val pct = ((total - priorTotal) * 100.0 / priorTotal).toInt()
        if (pct == 0) "same as last week" else if (pct > 0) "+$pct% vs last week" else "$pct% vs last week"
    } else {
        null
    }
    Text(
        text = "Last 7 days: ${fmt.format(total)} steps · ~${fmt.format(avg)}/day" +
            (delta?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium.tabulated(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
/** Status text, never color-only: "5-day streak" or the honest no-streak line. */
@Composable
private fun StreakSummary(days: List<Day>) {
    val (run, rest) = remember(days) {
        GoalCoach.currentStreak(
            days.map { GoalCoach.DayMark(it.epochDay, it.goal > 0 && it.steps >= it.goal, it.restDay) },
        )
    }
    val restNote = if (rest > 0) {
        " Includes $rest rest day${if (rest == 1) "" else "s"}."
    } else {
        ""
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = TallyElevation.Card),
    ) {
        Text(
            text = if (run > 0) "$run-day streak. Steady walking.$restNote"
            else "No streak yet — every walk counts.",
            style = MaterialTheme.typography.bodyLarge.tabulated(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
