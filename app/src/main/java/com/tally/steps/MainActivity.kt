package com.tally.steps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

@Composable
private fun TallyNav() {
    val navController = rememberNavController()
    Scaffold(
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
            composable("history") { HistoryScreen() }
            composable("workout") { WorkoutScreen() }
            composable("awards") { AwardsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
