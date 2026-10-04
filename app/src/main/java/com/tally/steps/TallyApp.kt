package com.tally.steps

import android.app.Application
import com.tally.steps.data.PrefsStore
import com.tally.steps.engine.StepRepository

class TallyApp : Application() {
    /** Backed by the engine's getInstance() singletons; do not construct directly. */
    val prefs: PrefsStore by lazy { PrefsStore.getInstance(this) }
    val repository: StepRepository by lazy { StepRepository.getInstance(this) }
}
