package com.tally.steps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tally.steps.ui.screens.AwardsScreen
import com.tally.steps.ui.screens.HistoryScreen
import com.tally.steps.ui.screens.OnboardingScreen
import com.tally.steps.ui.screens.SettingsScreen
import com.tally.steps.ui.screens.TodayScreen
import com.tally.steps.ui.screens.WorkoutScreen
import com.tally.steps.ui.theme.TallyTheme
import com.tally.steps.engine.StepCounterService
import com.tally.steps.engine.SyncWorker

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("today", "Today", Icons.Filled.Home),
    Tab("history", "History", Icons.Filled.DateRange),
    Tab("workout", "Workout", Icons.Filled.FitnessCenter),
    Tab("awards", "Awards", Icons.Filled.EmojiEvents),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 enforces edge-to-edge; M3 Scaffold/TopAppBar/NavBar
        // already pad for system bars and display cutouts.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TallyTheme {
                // Single DataStore owner is PrefsStore (TallyApp.prefs) — no
                // second delegate here (two instances on one file = crash).
                val app = application as TallyApp
                val prefs by app.prefs.onboardingDone
                    .collectAsStateWithLifecycle(initialValue = null)
                val done: Boolean? = prefs
                when (done) {
                    null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    false -> OnboardingScreen()
                    true -> TallyNav()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TallyNav() {
    // Fresh install / grant path: BootReceiver only runs after a reboot, so
    // start counting + schedule the hourly safety net here once onboarding
    // is done and Activity Recognition is granted. Idempotent.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val arGranted = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED
        if (arGranted) {
            runCatching { StepCounterService.start(context.applicationContext) }
            runCatching { SyncWorker.schedule(context.applicationContext) }
        }
    }
    val navController = rememberNavController()
    Scaffold(
        topBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val current = backStack?.destination
            val title = Tabs.firstOrNull { tab ->
                current?.hierarchy?.any { it.route == tab.route } == true
            }?.label ?: "Tally"
            TopAppBar(title = { Text(title) })
        },
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                Tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(padding),
        ) {
            composable("today") { TodayScreen() }
            composable("history") {
                HistoryScreen(
                    onGoToday = {
                        navController.navigate("today") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("workout") { WorkoutScreen() }
            composable("awards") {
                AwardsScreen(
                    onGoToday = {
                        navController.navigate("today") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
