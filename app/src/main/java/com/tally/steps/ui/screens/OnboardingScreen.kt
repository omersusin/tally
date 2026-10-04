package com.tally.steps.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tally.steps.data.PrefsStore
import kotlinx.coroutines.launch

private val MinTouch = Modifier.heightIn(min = 48.dp)

/**
 * S01 first-run: 3 pages max, Back/Next buttons, Skip on every page, and every
 * Allow button sits next to an equally visible Skip. No dark patterns: saying
 * no never nags, never shames, never blocks. Honest strings throughout.
 *
 * No-arg entry point: completion is persisted directly to DataStore
 * "tally_prefs" key "onboarding_done", the same file/key MainActivity's gate
 * reads. No callback needed.
 */
@Composable
fun OnboardingScreen() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { PrefsStore.getInstance(context) }
    val batteryAsked by prefs.batteryAsked.collectAsState(initial = false)

    fun finish() {
        scope.launch { runCatching { prefs.setOnboardingDone(true) } }
    }

    var activityGranted by remember { mutableStateOf(hasActivityRecognition(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotifications(context)) }
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { activityGranted = it || hasActivityRecognition(context) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsGranted = it || hasNotifications(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { finish() }, modifier = Modifier.then(MinTouch)) {
                Text("Skip")
            }
        }

        when (page) {
            0 -> ValuePage()
            1 -> PermissionsPage(
                activityGranted = activityGranted,
                notificationsGranted = notificationsGranted,
                onActivity = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }.onFailure { activityGranted = hasActivityRecognition(context) }
                    } else {
                        activityGranted = true
                    }
                },
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        runCatching {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }.onFailure { notificationsGranted = hasNotifications(context) }
                    } else {
                        notificationsGranted = true
                    }
                },
            )
            else -> BatteryPage(
                alreadyAsked = batteryAsked,
                onAsked = { scope.launch { prefs.setBatteryAsked(true) } },
                onAllow = { openBatteryExemption(context) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (page > 0) {
                    OutlinedButton(
                        onClick = { page-- },
                        modifier = Modifier.weight(1f).then(MinTouch),
                    ) { Text("Back") }
                }
                if (page < 2) {
                    Button(
                        onClick = { page++ },
                        modifier = Modifier.weight(1f).then(MinTouch),
                    ) { Text("Next") }
                } else {
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.weight(1f).then(MinTouch),
                    ) { Text("Start counting") }
                }
            }
            Text(
                text = "Page ${page + 1} of 3. You can skip all of this — " +
                    "Tally counts with whatever access it already has.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValuePage() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tally counts your steps.", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Put the phone in your pocket and walk. " +
                "That is the whole product: a step count, a daily goal, and your history.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Offline and private: no account, no login, no ads, no trackers. " +
                "Your days never leave this phone unless YOU export them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Free forever, no premium tier. If a permission below sounds unnecessary, skip it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionsPage(
    activityGranted: Boolean,
    notificationsGranted: Boolean,
    onActivity: () -> Unit,
    onNotifications: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Two permissions, both optional.", style = MaterialTheme.typography.headlineSmall)
        PermissionRow(
            title = "Physical activity",
            body = "Lets Tally read the step counter. Without it, workout sessions " +
                "still work but the daily count may miss steps.",
            granted = activityGranted,
            allowLabel = "Allow activity access",
            onAllow = onActivity,
        )
        PermissionRow(
            title = "Notifications",
            body = "One quiet notification keeps counting alive in the background " +
                "and can show your live count. Without it, counting still works " +
                "but Android may stop it sooner.",
            granted = notificationsGranted,
            allowLabel = "Allow notifications",
            onAllow = onNotifications,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    allowLabel: String,
    onAllow: () -> Unit,
) {
    var declined by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (granted) "Allowed" else if (declined) "Skipped" else "Not allowed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow, modifier = Modifier.then(MinTouch)) {
                    Text(allowLabel)
                }
                OutlinedButton(
                    onClick = { declined = true },
                    modifier = Modifier.then(MinTouch),
                ) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun BatteryPage(alreadyAsked: Boolean, onAsked: () -> Unit, onAllow: () -> Unit) {
    var askedLocal by rememberSaveable { mutableStateOf(false) }
    val asked = alreadyAsked || askedLocal
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("One battery setting.", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android tries to sleep apps to save battery, and some brands " +
                "(Xiaomi, Oppo, Vivo, Realme) do it aggressively overnight. " +
                "Exempting Tally keeps the counter running. Tally itself uses " +
                "almost no battery — it reads a hardware counter, not GPS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    askedLocal = true
                    onAsked()
                    onAllow()
                },
                modifier = Modifier.then(MinTouch),
            ) { Text("Allow in background") }
            OutlinedButton(
                onClick = {
                    askedLocal = true
                    onAsked()
                },
                modifier = Modifier.then(MinTouch),
            ) { Text("Skip") }
        }
        if (asked) {
            Text(
                "Done — either way, counting starts now. " +
                    "You can change this later in Settings → Battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun hasActivityRecognition(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun hasNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openBatteryExemption(context: Context) {
    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)) {
        return
    }
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: ActivityNotFoundException) {
            // Nothing honest left to try on this device.
        }
    }
}
