package com.tally.steps.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tally.steps.TallyApp
import com.tally.steps.data.Day
import com.tally.steps.engine.StepRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: StepRepository = (application as TallyApp).repository

    private val limit = MutableStateFlow(30)

    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<List<Day>> = limit
        .flatMapLatest { repository.history(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLimit(n: Int) {
        limit.value = n.coerceIn(1, 90)
    }
}
