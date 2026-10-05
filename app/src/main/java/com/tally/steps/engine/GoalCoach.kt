package com.tally.steps.engine

import java.time.LocalDate

/**
 * Pure goal coaching: no Android, no Room, no flows. Plain. Steady. Truthful —
 * it suggests nothing when the data is thin, and stays quiet when the current
 * goal is already close enough.
 */
object GoalCoach {

    /** Smallest history we trust for a suggestion: fewer active days is noise. */
    const val MIN_ACTIVE_DAYS = 4

    /** Within this fraction of the current goal, saying anything is nagging. */
    const val QUIET_BAND = 0.10

    const val MIN_GOAL = 1_000
    const val MAX_GOAL = 50_000
    const val GOAL_STEP = 500

    /** Quiet suggestion shown on Today: what you walk, and what goal fits it. */
    data class GoalNudge(val dailyAverage: Int, val suggestedGoal: Int)

    /**
     * Minimal day mark for streak math. Screens map their Day rows to this so
     * this file never imports Room or Android.
     */
    data class DayMark(val epochDay: Long, val goalMet: Boolean, val restDay: Boolean)

    /**
     * Suggest a goal from recent daily totals, or null when silence is honest:
     * fewer than [MIN_ACTIVE_DAYS] non-zero days, or the median-based pick is
     * within ±[QUIET_BAND] of [currentGoal]. Median (not mean) so one big hike
     * doesn't drag the goal up. Rounded to [GOAL_STEP], clamped to
     * [MIN_GOAL]..[MAX_GOAL]. Never throws.
     */
    fun suggestGoal(last7: List<Int>, currentGoal: Int): Int? =
        nudgeFor(last7, currentGoal)?.suggestedGoal

    /** Same rule as [suggestGoal], plus the walked average for the card text. */
    fun nudgeFor(last7: List<Int>, currentGoal: Int): GoalNudge? {
        val active = last7.takeLast(7).filter { it > 0 }
        if (active.size < MIN_ACTIVE_DAYS) return null
        val median = median(active)
        val rounded = ((median + GOAL_STEP / 2) / GOAL_STEP * GOAL_STEP)
            .coerceIn(MIN_GOAL, MAX_GOAL)
        if (currentGoal > 0 &&
            kotlin.math.abs(rounded - currentGoal) <= currentGoal * QUIET_BAND
        ) return null
        val average = (active.sum() / active.size / 100 * 100).coerceAtLeast(0)
        return GoalNudge(dailyAverage = average, suggestedGoal = rounded)
    }

    /**
     * Current streak length, counting goal days connected THROUGH rest days.
     * Returns (days, restDaysUsed): e.g. goal, goal, rest, goal → (4, 1).
     * A day that met its goal counts as a goal day even if also marked rest
     * (marking forbids that, but the math doesn't double-count). Missing rows
     * and unmarked misses end the run; future rows are ignored. Anchored at
     * [todayEpoch]: an undecided today (no goal yet) doesn't break yesterday's
     * run. Never throws.
     */
    fun currentStreak(
        days: List<DayMark>,
        todayEpoch: Long = LocalDate.now().toEpochDay(),
    ): Pair<Int, Int> {
        val byDay = days.filter { it.epochDay <= todayEpoch }.associateBy { it.epochDay }
        var cursor =
            if (byDay[todayEpoch]?.goalMet == true) todayEpoch else todayEpoch - 1
        var run = 0
        var rest = 0
        while (true) {
            val d = byDay[cursor] ?: break
            if (d.goalMet) {
                run++
            } else if (d.restDay) {
                run++
                rest++
            } else {
                break
            }
            cursor--
        }
        return run to rest
    }

    /**
     * Pace projection for "at this pace" honesty: linear extrapolation of
     * today's steps to midnight. Returns (projectedTotal, minutesToGoal?) —
     * minutesToGoal is null when the pace never reaches [goal] or goal <= 0.
     * Before 5 minutes of day have passed the pace is noise → returns today's
     * steps with null ETA. Never throws.
     */
    fun projectDay(
        stepsNow: Int,
        goal: Int,
        dayStartMs: Long,
        nowMs: Long,
    ): Pair<Int, Int?> {
        val elapsedMin = ((nowMs - dayStartMs) / 60_000L).coerceAtLeast(0)
        if (elapsedMin < 5) return stepsNow to null
        val perMin = stepsNow.toDouble() / elapsedMin
        val remainingMin = (1440 - elapsedMin).coerceAtLeast(0)
        val projected = (stepsNow + perMin * remainingMin).toInt().coerceAtLeast(0)
        val etaMin = if (goal > 0 && perMin > 0 && stepsNow < goal) {
            ((goal - stepsNow) / perMin).toInt().coerceAtLeast(0)
        } else {
            null
        }
        return projected to etaMin
    }

    private fun median(values: List<Int>): Int {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
