package com.tally.steps.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

/**
 * The big honest number. 72sp with tabular figures so the count
 * doesn't jitter as digits change. Polite live-region: announces
 * on change only, no reader spam.
 */
@Composable
fun GiantCount(
    steps: Int,
    goal: Int,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val fmt = NumberFormat.getIntegerInstance()
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = fmt.format(steps),
            fontSize = 72.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displayLarge.copy(
                fontFeatureSettings = "tnum",
                lineHeight = 76.sp,
            ),
        )
        Text(
            text = if (paused) "Paused — held at ${fmt.format(steps)}"
            else "of ${fmt.format(goal)} goal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
