package com.tally.steps.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

/**
 * The big honest number — the screen's hero. Display type (ExtraBold,
 * tight tracking, tabular figures) comes from the theme; generous
 * whitespace above and below lets it breathe as the visual anchor.
 * Polite live-region: announces on change only, no reader spam.
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
        }.padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = fmt.format(steps),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (paused) "Paused — held at ${fmt.format(steps)}"
            else "of ${fmt.format(goal)} goal",
            style = MaterialTheme.typography.bodyMedium.copy(
                letterSpacing = 0.2.sp,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
