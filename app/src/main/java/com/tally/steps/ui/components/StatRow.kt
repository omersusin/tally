package com.tally.steps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 3-up stats: distance / calories / active minutes.
 * One grouped a11y label, 48dp minimum row height.
 */
@Composable
fun StatRow(
    distanceM: Float,
    kcal: Float,
    activeMin: Int,
    modifier: Modifier = Modifier,
) {
    val distanceText = if (distanceM >= 1000f) {
        String.format(Locale.US, "%.1f km", distanceM / 1000f)
    } else {
        "${distanceM.toInt()} m"
    }
    val kcalText = "${kcal.toInt()} kcal"
    val minText = "$activeMin min"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Distance $distanceText, energy $kcalText, active time $minText"
            }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatCell(
            icon = { Icon(Icons.Filled.Route, contentDescription = null, modifier = Modifier.size(20.dp)) },
            value = distanceText,
            label = "Distance",
        )
        StatCell(
            icon = { Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(20.dp)) },
            value = kcalText,
            label = "Energy",
        )
        StatCell(
            icon = { Icon(Icons.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(20.dp)) },
            value = minText,
            label = "Active",
        )
    }
}

@Composable
private fun StatCell(
    icon: @Composable () -> Unit,
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
