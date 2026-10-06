package com.tally.steps.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
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

        // Scrollable middle: small screens and large fonts must never clip content.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
    var showAllBrands by rememberSaveable { mutableStateOf(false) }
    val asked = alreadyAsked || askedLocal
    // Your brand first; the rest behind one toggle — first-run stays short.
    val ownBrand = remember { matchBrand(Build.MANUFACTURER, Build.BRAND) }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("One battery setting.", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android tries to sleep apps to save battery, and some brands " +
                "(Xiaomi, Huawei, Samsung, Oppo, Vivo, Realme) do it aggressively overnight. " +
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
                "Noted. Counting starts now — " +
                    "you can change this later in Settings → Battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "If your brand stops apps anyway, these are the exact settings to check:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ownBrand != null) {
            OemCard(brand = ownBrand.first, steps = ownBrand.second, note = ownBrand.third)
        }
        if (showAllBrands || ownBrand == null) {
            OemBrand.entries
                .filter { ownBrand == null || it.brand != ownBrand.first }
                .forEach { OemCard(brand = it.brand, steps = it.steps, note = it.note) }
        } else {
            OutlinedButton(
                onClick = { showAllBrands = true },
                modifier = Modifier.fillMaxWidth().then(MinTouch),
            ) { Text("Show steps for other brands") }
        }
    }
}

/** This phone's maker, matched to one of our guides — null when unknown. */
private data class OemGuide(val brand: String, val steps: List<String>, val note: String?)

private fun matchBrand(vararg names: String): Triple<String, List<String>, String?>? {
    val hay = names.joinToString(" ").lowercase()
    return when {
        listOf("xiaomi", "redmi", "poco").any { it in hay } ->
            Triple("Xiaomi / Redmi / POCO", OemXiaomiSteps, OemXiaomiNote)
        listOf("huawei", "honor").any { it in hay } ->
            Triple("Huawei / Honor", OemHuaweiSteps, OemHuaweiNote)
        "samsung" in hay -> Triple("Samsung", OemSamsungSteps, OemSamsungNote)
        listOf("oppo", "vivo", "realme", "oneplus").any { it in hay } ->
            Triple("Oppo / Vivo / Realme", OemOppoSteps, null)
        else -> null
    }
}

private object OemBrand {
    val entries: List<OemGuide> = listOf(
        OemGuide("Xiaomi / Redmi / POCO", OemXiaomiSteps, OemXiaomiNote),
        OemGuide("Huawei / Honor", OemHuaweiSteps, OemHuaweiNote),
        OemGuide("Samsung", OemSamsungSteps, OemSamsungNote),
        OemGuide("Oppo / Vivo / Realme", OemOppoSteps, null),
    )
}

// OEM steps duplicated in SettingsScreen.kt and OnboardingScreen.kt — keep in sync.
private val OemXiaomiSteps = listOf(
    "Open Recents, find Tally, then lock it: tap the padlock icon (or swipe down on the card).",
    "Settings → Apps → Tally → Autostart: turn ON.",
    "Settings → Battery → Tally: set to No restrictions.",
    "MIUI 14: also allow Background autostart for Tally.",
)
private const val OemXiaomiNote =
    "Some MIUI versions offer “Turn off MIUI optimization” under Developer options. " +
        "It can help, but it resets some system settings — only try it if counting still stops."
private val OemHuaweiSteps = listOf(
    "Settings → Battery → App launch → Tally: turn OFF auto, choose Manage manually, turn all switches ON.",
    "Battery optimization → Tally: set to Allow (ignore battery optimization).",
    "Older EMUI: Settings → Protected apps: turn ON for Tally.",
)
private const val OemHuaweiNote =
    "EMUI 9+ advanced only: PowerGenie can still stop apps. " +
        "If you know what adb is: adb shell pm uninstall --user 0 com.huawei.powergenie"
private val OemSamsungSteps = listOf(
    "Settings → Battery: turn Adaptive battery OFF (or exempt Tally).",
    "Settings → Battery → Background usage limits → Never sleeping apps: add Tally.",
    "Settings → Apps → Tally → Battery: set to Unrestricted.",
    "Settings → Apps → Tally → Alarms & reminders: Allow, so the counter can restart on time.",
)
private const val OemSamsungNote =
    "Warning: Samsung sometimes puts apps back to sleep after a system update " +
        "or about 3 days idle. If counting stops, check this list again."
private val OemOppoSteps = listOf(
    "Settings → Startup manager (or Autostart): allow Tally to start automatically.",
    "Settings → Battery → Tally: allow background activity.",
    "Battery optimization → Tally: set to Not optimized.",
    "Open Recents and lock Tally (padlock) so it is not swiped away.",
)
private const val OemTestLine =
    "Test it: lock your phone for 10 minutes, walk a little, then check the count."

@Composable
private fun OemCard(brand: String, steps: List<String>, note: String?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide $brand steps" else "Show $brand steps",
                ) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(brand, style = MaterialTheme.typography.titleSmall)
            Text(
                if (expanded) "Hide" else "Show steps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                steps.forEachIndexed { i, step ->
                    Text(
                        "${i + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    OemTestLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    // No REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (Play-restricted):
    // open the system list and let the user pick Tally. NEW_TASK because the
    // context may not be an Activity.
    try {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (e: Exception) {
        // Nothing honest left to try on this device.
    }
}
