package com.tally.steps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tally.steps.ui.theme.tabulated
import java.text.NumberFormat

/**
 * Goal ring with the live count at its center: one glance, no duplicated
 * numbers. Static draw — no animation, so reduce-motion needs nothing to
 * disable. Polite live-region on the number: announces on change only.
 *
 * Overflow (steps > goal) renders a full ring plus a warm "+X over" pill —
 * the true count, never clamped in text, clearly celebrated. Paused keeps a
 * dashed outline and a "held at N" caption.
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
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(232.dp)
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Text(
                    text = fmt.format(steps),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = if (paused) "Paused — held" else "of ${fmt.format(goal)} goal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
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
