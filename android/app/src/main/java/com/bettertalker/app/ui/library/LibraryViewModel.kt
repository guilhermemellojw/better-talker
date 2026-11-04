package com.bettertalker.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.catalog.CitedPublication
import com.bettertalker.app.data.catalog.collectCitedPublications
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.repo.LibraryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A8: qualquer campo que muda a resolução de Citadas entra na assinatura. */
internal fun citationLibrarySignature(items: List<AttachmentEntity>): List<String> =
    items.map { "${it.id}|${it.status}|${it.indexed}|${it.baseSlot}|${it.fileName}" }

class LibraryViewModel(private val db: AppDatabase, private val repo: LibraryRepository) : ViewModel() {
    val items = repo.observe()
    private val _cited = MutableStateFlow<List<CitedPublication>>(emptyList())
    val cited = _cited.asStateFlow()

    init {
        refreshCited()
        // A8: o acervo muda fora da tela (worker indexa, download conclui,
        // reparo troca o arquivo). Recalcula Citadas com debounce para não
        // rodar a coleta pesada a cada linha do observer.
        viewModelScope.launch {
            repo.observe()
                .map { list -> citationLibrarySignature(list) }
                .distinctUntilChanged()
                .drop(1) // a coleta inicial já rodou acima
                .collectLatest {
                    delay(350)
                    refreshCitedNow()
                }
        }
    }

    private val _toast = MutableStateFlow("")
    val toast = _toast.asStateFlow()

    private suspend fun refreshCitedNow() {
        runCatching { collectCitedPublications(db, repo.all()) }
            .onSuccess { _cited.value = it }
    }

    fun refreshCited() = viewModelScope.launch { refreshCitedNow() }

    fun delete(id: String) = viewModelScope.launch {
        repo.delete(id)
        refreshCited()
    }
    fun reindex(id: String) = viewModelScope.launch { repo.reindex(id) }
    fun retryRegister(id: String) = viewModelScope.launch {
        if (!repo.retryRegister(id)) _toast.value = "Sem download para retentar. Baixe de novo."
    }
    fun redownloadDirect(id: String) = viewModelScope.launch {
        if (!repo.redownloadDirect(id)) _toast.value = "Reabra a página e baixe de novo."
    }
    fun repairMissing(id: String, uri: Uri) = viewModelScope.launch {
        try {
            repo.repairMissing(id, uri)
            _toast.value = "Arquivo restaurado; indexando."
            refreshCited()
        } catch (e: com.bettertalker.app.data.repo.ImportException) {
            _toast.value = e.message ?: "Falha ao restaurar o arquivo."
        } catch (_: Exception) {
            _toast.value = "Falha ao restaurar o arquivo."
        }
    }
    fun markBase(id: String, slot: String?) = viewModelScope.launch { repo.markBaseSlot(id, slot) }
    fun linkToNote(id: String, noteId: String) = viewModelScope.launch {
        repo.linkToNote(id, noteId)
        _toast.value = "Vinculado à nota."
    }
    fun consumeToast() { _toast.value = "" }

    fun importUri(uri: Uri, noteId: String? = null) =
        viewModelScope.launch {
            try {
                repo.importUri(uri, noteId)
                refreshCited()
            } catch (e: com.bettertalker.app.data.repo.ImportException) {
                _toast.value = e.message ?: "Falha ao importar."
            } catch (_: Exception) {
                _toast.value = "Falha ao importar o arquivo."
            }
        }

    class Factory(private val db: AppDatabase, private val repo: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(db, repo) as T
    }
}
