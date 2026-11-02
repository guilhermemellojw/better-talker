package com.bettertalker.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.DownloadGroup
import com.bettertalker.app.data.repo.LibraryRepository
import com.bettertalker.app.data.repo.groupDownloadCandidates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(db: AppDatabase, private val repo: LibraryRepository) : ViewModel() {
    val items = repo.observe()
    private val _toast = MutableStateFlow("")
    val toast = _toast.asStateFlow()
    data class ScanUi(
        val folderChosen: Boolean,
        val permissionLost: Boolean,
        val groups: List<DownloadGroup>,
        val error: String? = null,
    ) {
        val newGroups: List<DownloadGroup> get() = groups.filter { !it.alreadyInLibrary }
        val fileCount: Int get() = groups.sumOf { it.candidates.size }
    }

    private val _scan = MutableStateFlow<ScanUi?>(null)
    /** null = ainda não procurou; groups vazio = nada novo. */
    val scan = _scan.asStateFlow()

    fun scanDownloads() = viewModelScope.launch {
        val result = repo.scanAllDownloadsDetailed()
        val library = repo.all()
        _scan.value = ScanUi(
            folderChosen = result.folderChosen,
            permissionLost = result.permissionLost,
            groups = groupDownloadCandidates(result.candidates, library.map { it.fileName }.toSet(), library),
            error = result.error,
        )
        _toast.value = when {
            result.permissionLost -> "A permissão da pasta expirou. Escolha de novo."
            result.error != null -> "Não consegui ler a pasta: ${result.error}"
            !result.folderChosen -> "Escolha a pasta Downloads para ler os arquivos."
            _scan.value?.groups.isNullOrEmpty() -> "Nada novo em Downloads."
            else -> ""
        }
    }

    fun importGroup(group: DownloadGroup, noteId: String? = null) = viewModelScope.launch {
        try {
            repo.importUri(android.net.Uri.parse(group.best.uriString), noteId)
            _toast.value = "Importado: ${group.best.name}"
            rescanAfterImport()
        } catch (e: com.bettertalker.app.data.repo.ImportException) {
            _toast.value = e.message ?: "Falha ao importar."
        } catch (_: Exception) {
            _toast.value = "Falha ao importar o arquivo."
        }
    }

    fun importAllNew(noteId: String? = null) = viewModelScope.launch {
        val groups = _scan.value?.newGroups.orEmpty()
        var ok = 0
        for (group in groups) {
            try {
                repo.importUri(android.net.Uri.parse(group.best.uriString), noteId)
                ok++
            } catch (_: Exception) {
                // segue os demais; o resumo informa quantos entraram
            }
        }
        _toast.value = if (ok == 0) "Nenhum arquivo novo para importar." else "$ok importado(s)."
        rescanAfterImport()
    }

    fun dismissScan() { _scan.value = null }

    private suspend fun rescanAfterImport() {
        val result = repo.scanAllDownloadsDetailed()
        val library = repo.all()
        _scan.value = ScanUi(
            folderChosen = result.folderChosen,
            permissionLost = result.permissionLost,
            groups = groupDownloadCandidates(result.candidates, library.map { it.fileName }.toSet(), library),
            error = result.error,
        )
    }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun reindex(id: String) = viewModelScope.launch { repo.reindex(id) }
    fun retryRegister(id: String) = viewModelScope.launch {
        if (!repo.retryRegister(id)) _toast.value = "Sem download para retentar. Baixe de novo."
    }
    fun redownloadDirect(id: String) = viewModelScope.launch {
        if (!repo.redownloadDirect(id)) _toast.value = "Reabra a página e baixe de novo."
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
