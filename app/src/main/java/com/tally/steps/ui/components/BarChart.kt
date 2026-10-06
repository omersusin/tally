package com.tally.steps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tally.steps.data.Day
import com.tally.steps.ui.theme.tabulated
import java.text.NumberFormat
import java.time.format.DateTimeFormatter

enum class HistoryRange(val days: Int, val label: String) {
    WEEK(7, "Week"),
    MONTH(30, "Month"),
}

/**
 * Week / Month segmented control. Real buttons, 48dp tall,
 * full-width pill. Arrow-key navigation comes free from the
 * segmented-button group semantics.
 */
@Composable
fun RangeSwitch(
    selected: HistoryRange,
    onSelect: (HistoryRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        HistoryRange.entries.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index, HistoryRange.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(range.label) },
            )
        }
    }
}

/**
 * Static Canvas bars — no grow animation, so reduce-motion is
 * satisfied by construction. Today renders in accent over a soft
 * highlight column; a dashed goal line marks the target. A text
 * fallback list below carries the same data with table semantics
 * for screen readers.
 */
@Composable
fun BarChart(
    days: List<Day>,
    selectedEpoch: Long? = null,
    onSelect: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val fmt = NumberFormat.getIntegerInstance()
    val maxSteps = (days.maxOfOrNull { it.steps } ?: 0).coerceAtLeast(1)
    val goalRef = days.lastOrNull()?.goal ?: 0
    // Headroom so the goal line never clips when the goal tops every bar.
    val scale = maxOf(maxSteps, goalRef).coerceAtLeast(1)
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val today = java.time.LocalDate.now().toEpochDay()

    val summary = if (days.isEmpty()) "No step data"
    else "Step chart, ${days.size} days, most recent ${fmt.format(days.last().steps)} steps"

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = summary },
        ) {
            if (days.isEmpty()) return@Canvas
            val gap = 8.dp.toPx()
            val barW = ((size.width - gap * (days.size + 1)) / days.size).coerceAtLeast(4.dp.toPx())
            val plotH = size.height - 8.dp.toPx()
            val barH = { steps: Int -> plotH * (steps.toFloat() / scale) }
            // Selected-today emphasis: a soft full-height column behind
            // today's bar, drawn first so bars sit on top of it.
            days.forEachIndexed { i, day ->
                if (day.epochDay == today) {
                    val left = gap + i * (barW + gap)
                    drawRoundRect(
                        color = highlight,
                        topLeft = Offset(left - gap / 2, 0f),
                        size = Size(barW + gap, size.height),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    )
                }
            }
            days.forEachIndexed { i, day ->
                val h = barH(day.steps)
                val left = gap + i * (barW + gap)
                val top = size.height - h
                drawRoundRect(
                    color = if (day.epochDay == today) accent else muted,
                    topLeft = Offset(left, top),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
            }
            // Goal line: dashed, in outline — never the today-bar accent.
            if (goalRef > 0) {
                val y = size.height - plotH * (goalRef.toFloat() / scale)
                drawLine(
                    color = outlineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                )
            }
        }
        BarChartFallbackList(days = days, selectedEpoch = selectedEpoch, onSelect = onSelect)
    }
}

/** Screen-reader (and honest-data) fallback: every bar as plain text. Tapping a row selects that day below. */
@Composable
fun BarChartFallbackList(
    days: List<Day>,
    selectedEpoch: Long? = null,
    onSelect: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val fmt = NumberFormat.getIntegerInstance()
    val dateFmt = DateTimeFormatter.ofPattern("MMM d")
    val today = java.time.LocalDate.now().toEpochDay()
    Column(modifier = modifier.padding(top = 8.dp)) {
        days.forEach { day ->
            val date = runCatching { java.time.LocalDate.ofEpochDay(day.epochDay).format(dateFmt) }
                .getOrDefault("")
            val label = "$date: ${fmt.format(day.steps)} steps of ${fmt.format(day.goal)} goal" +
                if (day.epochDay == today) " · today" else ""
            if (onSelect != null) {
                androidx.compose.material3.TextButton(
                    onClick = { onSelect(day.epochDay) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = (if (day.epochDay == selectedEpoch) "Selected, " else "") + label,
                        style = MaterialTheme.typography.bodySmall.tabulated(),
                        color = if (day.epochDay == selectedEpoch) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.tabulated(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
