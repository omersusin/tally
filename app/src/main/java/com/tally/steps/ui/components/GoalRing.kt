package com.tally.steps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tally.steps.ui.theme.tabulated
import java.text.NumberFormat

/**
 * Goal ring, 184dp home size. Static draw — no animation, so
 * reduce-motion needs nothing to disable. Plain and steady.
 *
 * A 20dp track with rounded caps gives it real presence; the paused
 * state keeps its dashed outline treatment.
 *
 * Overflow (steps > goal) renders a full ring plus a warm "+X over"
 * pill — the true count, never clamped in text, clearly celebrated.
 */
@Composable
fun GoalRing(
    steps: Int,
    goal: Int,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val fmt = NumberFormat.getIntegerInstance()
    val safeGoal = goal.coerceAtLeast(1)
    val fraction = (steps.toFloat() / safeGoal).coerceIn(0f, 1f)
    val overflow = (steps - safeGoal).coerceAtLeast(0)
    val label = "${fmt.format(steps)} of ${fmt.format(goal)} steps" +
        if (overflow > 0) ", ${fmt.format(overflow)} over goal" else "" +
        if (paused) ", counting paused" else ""
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val success = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .size(184.dp)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
        ) {
            val stroke = 20.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                    pathEffect = if (paused) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
                ),
            )
            drawArc(
                color = if (overflow > 0) success else progress,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        if (overflow > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    text = "+${fmt.format(overflow)} over",
                    style = MaterialTheme.typography.titleMedium.tabulated(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
