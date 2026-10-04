package com.tally.steps.widget

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import com.tally.steps.MainActivity
import com.tally.steps.ui.workout.EngineBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val CountKey = intPreferencesKey("today_steps")
private val GoalKey = intPreferencesKey("today_goal")

/**
 * S07 home-screen widget: today's count + goal. Tapping anywhere opens the app.
 * The engine/worker pushes fresh numbers via [TallyWidget.update] — Glance
 * redraws from stored state, so the widget never blocks on the database.
 *
 * MANIFEST + res (app-surface owner — AndroidManifest is NOT this agent's file):
 * <receiver android:name=".widget.TallyWidgetReceiver" android:exported="false">
 *   <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
 *   </intent-filter>
 *   <meta-data android:name="android.appwidget.provider"
 *     android:resource="@xml/tally_widget_info" />
 * </receiver>
 * plus res/xml/tally_widget_info.xml describing min size (~110x40dp).
 */
class TallyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Content()
        }
    }

    @Composable
    private fun Content() {
        val steps = currentState(CountKey) ?: -1
        val goal = currentState(GoalKey) ?: 6000
        val openApp = actionStartActivity(
            ComponentName(LocalContext.current, MainActivity::class.java),
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp)
                .clickable(openApp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (steps < 0) "—" else "$steps",
            )
            Text(
                text = if (steps < 0) {
                    "Tap to open Tally"
                } else {
                    "of $goal steps today"
                },
            )
        }
    }

    companion object {
        /**
         * Worker hook: call after each flush/rollover/sync and on boot.
         * Reads today's Day from the engine and pushes it to every widget
         * instance. Never throws — a stale count beats a crashed worker.
         */
        suspend fun update(context: Context) {
            val app = context.applicationContext as? Application ?: return
            val repo = EngineBridge.stepRepository(app)
            val day = runCatching { repo.today().first() }.getOrNull()
            val manager = runCatching { GlanceAppWidgetManager(context) }.getOrNull()
                ?: return
            val ids = runCatching { manager.getGlanceIds(TallyWidget::class.java) }
                .getOrDefault(emptyList())
            ids.forEach { id ->
                runCatching {
                    updateAppWidgetState(
                        context,
                        PreferencesGlanceStateDefinition,
                        id,
                    ) { prefs ->
                        prefs.toMutablePreferences().apply {
                            if (day != null) {
                                this[CountKey] = day.steps
                                this[GoalKey] = day.goal
                            }
                        }
                    }
                    TallyWidget().update(context, id)
                }
            }
        }

        /** Fire-and-forget variant for non-suspend call sites (e.g. BootReceiver). */
        fun requestUpdate(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                update(context.applicationContext)
            }
        }
    }
}

/** Receiver class name referenced by the manifest snippet above. */
class TallyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TallyWidget()
}
