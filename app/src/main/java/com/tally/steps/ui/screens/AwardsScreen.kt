package com.tally.steps.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tally.steps.data.Day
import com.tally.steps.ui.workout.EngineBridge
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Badge state is always shown as text, never color alone. */
enum class BadgeState(val label: String, val mark: String) {
    LOCKED("Locked", "○"),
    UNLOCKED("Unlocked", "●"),
    NEW("New", "★"),
}

data class Badge(
    val id: String,
    val name: String,
    val how: String,
    val state: BadgeState,
)

/** Streak status is always shown as text, never color alone. */
enum class StreakStatus(val label: String) {
    ACTIVE("Active streak"),
    AT_RISK("At risk"),
    BROKEN("Streak broken"),
    NONE("No streak yet"),
}

data class StreakInfo(val days: Int, val status: StreakStatus, val note: String)

data class AwardsUiState(
    val badges: List<Badge> = emptyList(),
    val streak: StreakInfo = StreakInfo(0, StreakStatus.NONE, ""),
    val ready: Boolean = false,
)

/**
 * S05 awards, computed locally from the engine's Day history so awards never
 * depend on a separate table: a "goal day" is steps >= goal with goal > 0 —
 * the same rule Today celebrates.
 */
class AwardsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = EngineBridge.stepRepository(getApplication())

    private val _ui = MutableStateFlow(AwardsUiState())
    val ui: StateFlow<AwardsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            // Recompute whenever today or history changes (e.g. goal hit while open).
            combine(repo.today(), repo.history(365)) { today, days ->
                val all = if (days.none { it.epochDay == today.epochDay }) {
                    listOf(today) + days
                } else {
                    days.map { if (it.epochDay == today.epochDay) today else it }
                }
                AwardsUiState(badges = badges(all), streak = streak(all), ready = true)
            }.collect { _ui.value = it }
        }
    }

    private fun isGoalDay(d: Day): Boolean =
        d.goal > 0 && d.steps >= d.goal

    private fun badges(days: List<Day>): List<Badge> {
        val todayEpoch = LocalDate.now().toEpochDay()
        // "New" = unlocked within the last 3 days.
        fun state(unlockedEpoch: Long?): BadgeState = when {
            unlockedEpoch == null -> BadgeState.LOCKED
            todayEpoch - unlockedEpoch <= 3 -> BadgeState.NEW
            else -> BadgeState.UNLOCKED
        }
        val firstSteps = days.filter { it.steps > 0 }.minOfOrNull { it.epochDay }
        val first5k = days.filter { it.steps >= 5_000 }.minOfOrNull { it.epochDay }
        val first10k = days.filter { it.steps >= 10_000 }.minOfOrNull { it.epochDay }
        val first20k = days.filter { it.steps >= 20_000 }.minOfOrNull { it.epochDay }
        val best = days.maxOfOrNull { it.steps } ?: 0
        val goalDays = days.count { isGoalDay(it) }
        val first7 = streakStartFor(days, 7)
        val first30 = streakStartFor(days, 30)
        return listOf(
            Badge("first-steps", "First steps", "Record any steps on any day.", state(firstSteps)),
            Badge("5k", "5,000 in a day", "Reach 5,000 steps in one day.", state(first5k)),
            Badge("10k", "10,000 in a day", "Reach 10,000 steps in one day.", state(first10k)),
            Badge("20k", "20,000 in a day", "Reach 20,000 steps in one day.", state(first20k)),
            Badge(
                "goal-7", "7 goal days",
                "Hit your daily goal on 7 different days (total $goalDays so far).",
                state(if (goalDays >= 7) days.filter { isGoalDay(it) }.map { it.epochDay }.sorted()[6] else null),
            ),
            Badge("streak-7", "7-day streak", "Hit your goal 7 days in a row.", state(first7)),
            Badge("streak-30", "30-day streak", "Hit your goal 30 days in a row.", state(first30)),
            Badge(
                "best-day", "Personal best",
                if (best > 0) "Your best day so far: $best steps." else "No steps recorded yet.",
                if (best > 0) BadgeState.UNLOCKED else BadgeState.LOCKED,
            ),
        )
    }

    /** Earliest epoch day on which an N-day goal streak was first completed. */
    private fun streakStartFor(days: List<Day>, n: Int): Long? {
        val goalEpochs = days.filter { isGoalDay(it) }.map { it.epochDay }.toSortedSet()
        if (goalEpochs.size < n) return null
        val sorted = goalEpochs.sorted()
        for (i in 0..sorted.size - n) {
            if (sorted[i + n - 1] - sorted[i] == (n - 1).toLong()) return sorted[i + n - 1]
        }
        return null
    }

    private fun streak(days: List<Day>): StreakInfo {
        val goalEpochs = days.filter { isGoalDay(it) }.map { it.epochDay }.toSortedSet()
        if (goalEpochs.isEmpty()) {
            return StreakInfo(
                0, StreakStatus.NONE,
                "Hit your daily goal to start a streak. " +
                    "Missing your goal two days in a row ends it.",
            )
        }
        val today = LocalDate.now().toEpochDay()
        val todayHit = today in goalEpochs
        var run = 0
        var cursor = if (todayHit) today else today - 1
        while (cursor in goalEpochs) {
            run++
            cursor--
        }
        return when {
            run == 0 -> StreakInfo(
                0, StreakStatus.BROKEN,
                "Your last goal day was ${today - goalEpochs.max()} day(s) ago. " +
                    "Start a new streak today — yesterday doesn't count against you twice.",
            )
            !todayHit -> StreakInfo(
                run, StreakStatus.AT_RISK,
                "$run day${if (run == 1) "" else "s"} and counting — " +
                    "but today isn't a goal day yet. Walk to keep it alive.",
            )
            else -> StreakInfo(
                run, StreakStatus.ACTIVE,
                "$run day${if (run == 1) "" else "s"} in a row, including today. " +
                    "One missed day pauses it; two missed days end it.",
            )
        }
    }
}

@Composable
fun AwardsScreen(vm: AwardsViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    if (!ui.ready) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Checking your days…")
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
        StreakCard(info = ui.streak)
        Text("Badges", style = MaterialTheme.typography.titleMedium)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 2000.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            items(ui.badges, key = { it.id }) { badge -> BadgeCard(badge = badge) }
        }
        Text(
            text = "Badges are earned from days already on this phone. " +
                "Nothing here changes your step count.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StreakCard(info: StreakInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(info.status.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (info.days > 0) "${info.days} day${if (info.days == 1) "" else "s"}" else "—",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = info.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeCard(badge: Badge) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${badge.state.mark} ${badge.name}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = badge.state.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = badge.how,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
