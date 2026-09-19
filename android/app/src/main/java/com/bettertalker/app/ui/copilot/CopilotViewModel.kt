package com.bettertalker.app.ui.copilot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.BasePub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CopilotViewModel(db: AppDatabase, private val noteId: String? = null) : ViewModel() {
    private val repo = CopilotRepository(db)
    private val _query = MutableStateFlow("")
    private val _busy = MutableStateFlow(false)
    private val _summary = MutableStateFlow("")
    private val _ideas = MutableStateFlow<List<IdeaCard>>(emptyList())
    private val _insert = MutableStateFlow("")
    private val _missing = MutableStateFlow<List<BasePub>>(emptyList())
    val query = _query.asStateFlow()
    val busy = _busy.asStateFlow()
    val summary = _summary.asStateFlow()
    val ideas = _ideas.asStateFlow()
    val insertText = _insert.asStateFlow()
    val missing = _missing.asStateFlow()

    init { refreshBases() }

    fun setQuery(v: String) { _query.value = v }
    fun consumeInsert() { _insert.value = "" }

    fun refreshBases() = viewModelScope.launch {
        val slots = repo.missingBases().toSet()
        _missing.value = BASE_PUBS.filter { slots.contains(it.slot) }
    }

    fun summarize() = viewModelScope.launch {
        _busy.value = true
        val hits = repo.askScoped(_query.value.ifBlank { "discurso" }, noteId)
        _summary.value = repo.summary(hits)
        _busy.value = false
    }

    fun ideas() = viewModelScope.launch {
        _busy.value = true
        val topic = _query.value.ifBlank { "discurso" }
        val hits = repo.askScoped(topic, noteId)
        _ideas.value = repo.ideasFor(topic, hits)
        _busy.value = false
    }

    fun insert(card: IdeaCard) {
        _insert.value = "## ${card.title}\n\n${card.body}\n\n> ${card.snippet}" +
            (if (card.source.isNotEmpty()) "\n> Fonte: ${card.source}" else "")
    }

    class Factory(private val db: AppDatabase, private val noteId: String? = null) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CopilotViewModel(db, noteId) as T
    }
}
