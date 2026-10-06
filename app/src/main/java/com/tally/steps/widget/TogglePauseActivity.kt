package com.tally.steps.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tally.steps.TallyApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible tap target behind the widget's pause/resume line (Glance 1.2
 * has no callback actions, only activity launches). Flips the engine pause
 * pref — the service observes it and updates its notification — then
 * finishes. No UI, no history entry. Steps survive either way.
 */
class TogglePauseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? TallyApp
        if (app == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            runCatching {
                val paused = app.repository.paused().first()
                app.repository.setPaused(!paused)
            }
            finish()
        }
    }
}
