package com.tally.steps.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.data.Day
import com.tally.steps.ui.components.BarChart
import com.tally.steps.ui.components.HistoryRange
import com.tally.steps.ui.components.RangeSwitch
import com.tally.steps.ui.history.HistoryViewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** S03 History: Canvas bars + segmented range + day detail + streak + text fallback. */
@Composable
fun HistoryScreen(
    vm: HistoryViewModel = viewModel(),
    onGoToday: () -> Unit = {},
) {
    val allDays by vm.days.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(HistoryRange.WEEK) }

    val visible: List<Day> = when (range) {
        HistoryRange.DAY -> allDays.takeLast(1)
        HistoryRange.WEEK -> allDays.takeLast(7)
        HistoryRange.MONTH -> allDays.takeLast(30)
    }
    val detail: Day? = visible.lastOrNull()

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RangeSwitch(
            selected = range,
            onSelect = {
                range = it
                vm.setLimit(it.days)
            },
        )

        BarChart(days = visible)

        detail?.let { DayDetail(it) }

        StreakSummary(days = allDays)
    }
}

@Composable
private fun DayDetail(day: Day) {
    val fmt = NumberFormat.getIntegerInstance()
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(day.epochDay * 86_400_000L)
        .atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = date, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${fmt.format(day.steps)} steps of ${fmt.format(day.goal)} goal",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${"%.1f".format(day.distanceM / 1000f)} km · " +
                    "${day.kcal.toInt()} kcal · ${day.activeMin} min active",
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
        }
    }
}

/** Status text, never color-only: "5-day streak" or the honest no-streak line. */
@Composable
private fun StreakSummary(days: List<Day>) {
    val sorted = days.sortedByDescending { it.epochDay }
    var streak = 0
    for (d in sorted) {
        if (d.goal > 0 && d.steps >= d.goal) streak++ else break
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (streak > 0) "$streak-day streak. Steady walking."
            else "No streak yet — every walk counts.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }
}
