package com.bettertalker.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.BuildConfig
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.cloud.SyncStatus
import com.bettertalker.app.data.cloud.isConflictNoteTitle
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.images.ImageBackupRepository
import com.bettertalker.app.data.images.ImageBackupScheduler
import com.bettertalker.app.data.images.ImageBackupStats
import com.bettertalker.app.data.images.ImageRestoreReport
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class AccountViewModel(ctx: android.content.Context) : ViewModel() {
    private val app = ctx.applicationContext
    private val auth = AuthRepository(app)
    private val settings = SettingsStore(app)

    val user = auth.user
    val accountConflict = auth.accountConflict
    val lastSync = settings.lastSync.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    /** M7: flavor com sync beta + opt-in explícito (default desligado). */
    val syncBeta = BuildConfig.SYNC_BETA
    val syncBetaEnabled = settings.syncBetaEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 5.4: backup de imagens (login opt-in; notas continuam locais).
    val imageBackupEnabled = settings.imageBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // M2: primeiro sync deste aparelho pede escolha (Enviar/Baixar/Mesclar).
    private val db = com.bettertalker.app.data.db.DbProvider.get(app)
    val imageStats: StateFlow<ImageBackupStats> = combine(
        db.noteImageDao().observeCount(),
        db.imageBackupDao().observeUploadedCount(),
        db.imageBackupDao().observeFailedCount(),
        db.imageBackupDao().observeUploadedBytes(),
        db.imageBackupDao().observeLastError(),
    ) { total, uploaded, failed, bytes, lastError ->
        ImageBackupStats(
            total = total,
            uploaded = uploaded,
            failed = failed,
            uploadedBytes = bytes,
            lastError = lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImageBackupStats())

    val pendingCloudDeletes = db.imageBackupDao().observeDeletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _restoreBusy = MutableStateFlow(false)
    val restoreBusy = _restoreBusy.asStateFlow()
    private val _restoreReport = MutableStateFlow<ImageRestoreReport?>(null)
    val restoreReport = _restoreReport.asStateFlow()
    private val _restoreError = MutableStateFlow("")
    val restoreError = _restoreError.asStateFlow()

    private val _localNoteCount = MutableStateFlow(0)
    val showFirstSync: StateFlow<Boolean> = combine(
        user,
        settings.syncFirstPending,
        _localNoteCount,
    ) { u, pending, count ->
        // Público nunca tem Firestore/sync; evita herdar flag de um beta antigo.
        BuildConfig.SYNC_BETA && u != null && pending && count > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** M5: cópias "(conflito)" criadas pelo worker, para revisão na conta. */
    val conflicts: StateFlow<List<NoteEntity>> = db.noteDao().observe(null, "")
        .map { notes -> notes.filter { isConflictNoteTitle(it.title) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** F5g: estado volátil do sync (Idle/Running/Success/Error) — observável na tela. */
    val syncState: StateFlow<SyncStatus.State> = SyncStatus.state
    val configured = auth.isConfigured()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val _confirmWipe = MutableStateFlow(false)
    val confirmWipe = _confirmWipe.asStateFlow()
    private val _confirmLocalWipe = MutableStateFlow(false)
    val confirmLocalWipe = _confirmLocalWipe.asStateFlow()

    init {
        auth.start()
        viewModelScope.launch {
            if (BuildConfig.SYNC_BETA && settings.syncBetaEnabledValue()) {
                SyncScheduler.ensurePeriodic(app)
            }
        }
        viewModelScope.launch {
            // Recupera pendências de uma sessão anterior (app morto no meio).
            if (settings.imageBackupEnabledValue()) {
                ImageBackupScheduler.enqueueAllPending(app)
            }
            ImageBackupScheduler.enqueuePendingDeletes(app)
        }
        viewModelScope.launch { refreshLocalNoteCountAndSkipFirstSyncIfEmpty() }
    }

    /**
     * M2/M7: sem notas locais não há o que decidir no primeiro sync. Também
     * cobre o login feito depois do init da tela (quando a contagem inicial
     * era 0 e o pendente ainda não existia).
     */
    private suspend fun refreshLocalNoteCountAndSkipFirstSyncIfEmpty() {
        val count = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { db.noteDao().allIncludingTrashed().size }.getOrDefault(0)
        }
        _localNoteCount.value = count
        if (settings.syncFirstPendingValue() && count == 0) {
            settings.setSyncFirstPending(false)
            SyncScheduler.requestSync(app)
            SyncScheduler.ensurePeriodic(app)
        }
    }

    override fun onCleared() { auth.stop() }

    fun signIn(uiContext: android.content.Context? = null) = viewModelScope.launch {
        if (!settings.syncBetaEnabledValue()) {
            _error.value = "Ative a sincronização beta antes de entrar."
            return@launch
        }
        _busy.value = true
        val err = auth.signIn(uiContext ?: app)
        // Com conflito de conta, o diálogo dedicado explica e oferece a
        // limpeza; evita snackbar duplicado.
        _error.value = if (auth.accountConflict.value != null) "" else err ?: ""
        if (err == null && auth.accountConflict.value == null) {
            refreshLocalNoteCountAndSkipFirstSyncIfEmpty()
        }
        _busy.value = false
    }

    fun signOut() = auth.signOut()

    /** M2: aplica a escolha do primeiro sync. */
    fun firstSync(mode: String) = viewModelScope.launch {
        if (!settings.syncBetaEnabledValue()) return@launch
        settings.setSyncFirstPending(false)
        SyncScheduler.requestFirstSync(app, mode)
        SyncScheduler.ensurePeriodic(app)
    }

    /** M7: ativar liga o opt-in; desativar sai da conta e cancela workers. */
    fun setSyncBetaEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setSyncBetaEnabled(enabled)
        if (enabled) {
            _message.value = "Sincronização beta ativada."
            SyncScheduler.ensurePeriodic(app)
            if (!settings.syncFirstPendingValue()) SyncScheduler.requestSync(app)
        } else {
            auth.signOut()
            SyncScheduler.cancelAll(app)
            SyncStatus.set(SyncStatus.State.Idle)
            _message.value = "Sincronização beta desativada. As notas continuam neste aparelho."
        }
    }

    fun syncNow() = viewModelScope.launch {
        if (!settings.syncBetaEnabledValue()) {
            _message.value = "Ative a sincronização beta para sincronizar."
            return@launch
        }
        SyncScheduler.requestSync(app)
    }

    /**
     * 5.4: login opt-in para o backup de imagens. Não liga o sincronização
     * beta nem exige o flavor beta; notas/chat continuam locais.
     */
    fun signInImages(uiContext: android.content.Context? = null) = viewModelScope.launch {
        if (!auth.isConfigured()) {
            _error.value = "Firebase não configurado neste build."
            return@launch
        }
        _busy.value = true
        val err = auth.signIn(uiContext ?: app)
        if (err == null && auth.accountConflict.value == null) {
            settings.setImageBackupEnabled(true)
            ImageBackupScheduler.enqueueAllPending(app)
            ImageBackupScheduler.enqueuePendingDeletes(app)
            _message.value = "Backup de imagens ativado."
        } else {
            _error.value = err ?: ""
        }
        _busy.value = false
    }

    fun signOutImages() = viewModelScope.launch {
        auth.signOut()
        settings.setImageBackupEnabled(false)
        ImageBackupScheduler.cancelAll(app)
        _message.value = "Saiu da conta. As notas e imagens continuam neste aparelho."
    }

    fun setImageBackupEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled && auth.currentUser() == null) {
            _error.value = "Entre com Google para ativar o backup de imagens."
            return@launch
        }
        settings.setImageBackupEnabled(enabled)
        if (enabled) {
            ImageBackupScheduler.enqueueAllPending(app)
            _message.value = "Backup de imagens ativado."
        } else {
            ImageBackupScheduler.cancelAll(app)
            _message.value = "Backup de imagens pausado."
        }
    }

    fun backupPendingImages() = viewModelScope.launch {
        if (auth.currentUser() == null) {
            _error.value = "Entre com Google para enviar as imagens."
            return@launch
        }
        if (!settings.imageBackupEnabledValue()) {
            _error.value = "Ative o backup de imagens antes de enviar."
            return@launch
        }
        ImageBackupScheduler.enqueueAllPending(app)
        _message.value = "Imagens pendentes na fila de envio."
    }

    /** 5.4.1: baixa da conta as imagens que faltam no aparelho. */
    fun restoreImages() = viewModelScope.launch {
        if (_restoreBusy.value) return@launch
        val uid = auth.currentUser()?.uid
        if (uid == null) {
            _restoreError.value = "Entre com Google para baixar as imagens."
            return@launch
        }
        _restoreBusy.value = true
        _restoreError.value = ""
        runCatching {
            ImageBackupRepository.create(app, db).restore(uid)
        }.onSuccess { report ->
            _restoreReport.value = report
            _message.value = "Imagens: ${report.summary}"
            // Idempotente: reenvia qualquer arquivo restaurado sem metadata.
            ImageBackupScheduler.enqueueAllPending(app)
        }.onFailure {
            _restoreError.value = it.message?.take(160)
                ?: "Falha ao baixar as imagens."
        }
        _restoreBusy.value = false
    }

    fun retryCloudDeletes() = viewModelScope.launch {
        ImageBackupScheduler.enqueuePendingDeletes(app)
        _message.value = "Remoções da nuvem na fila."
    }

    fun consumeError() { _error.value = "" }
    fun consumeMessage() { _message.value = "" }

    fun cancelAccountConflict() = auth.clearAccountConflict()

    /**
     * Guard mínimo de multi-conta: apaga o banco local + arquivos de
     * publicação e tenta entrar de novo. A conta anterior continua na nuvem.
     */
    fun wipeLocalAndRetry(uiContext: android.content.Context? = null) = viewModelScope.launch {
        _busy.value = true
        try {
            clearLocalData(resetUid = true)
            auth.clearLastUidBinding()
            auth.clearAccountConflict()
            val err = auth.signIn(uiContext ?: app)
            _error.value = err ?: ""
            if (err == null) refreshLocalNoteCountAndSkipFirstSyncIfEmpty()
        } catch (e: Exception) {
            _error.value = e.message?.take(160) ?: "Falha ao apagar os dados locais."
        }
        _busy.value = false
    }

    fun askLocalWipe() { _confirmLocalWipe.value = true }
    fun cancelLocalWipe() { _confirmLocalWipe.value = false }

    /** M5: apaga só o aparelho; a conta/sessão continua vinculada. */
    fun wipeLocal() = viewModelScope.launch {
        _confirmLocalWipe.value = false
        _busy.value = true
        try {
            clearLocalData(resetUid = false)
            _localNoteCount.value = 0
            SyncStatus.set(SyncStatus.State.Idle)
            _message.value = "Dados locais apagados."
        } catch (e: Exception) {
            _error.value = e.message?.take(160) ?: "Falha ao apagar os dados locais."
        }
        _busy.value = false
    }

    private suspend fun clearLocalData(resetUid: Boolean) =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.bettertalker.app.data.db.DbProvider.get(app).clearAllTables()
            java.io.File(app.filesDir, "publicacoes").deleteRecursively()
            java.io.File(app.filesDir, "images").deleteRecursively()
            if (resetUid) settings.setLastUid(null)
            settings.setLastSync(0L)
            settings.clearLastNoteId()
        }

    fun askWipe() { _confirmWipe.value = true }
    fun cancelWipe() { _confirmWipe.value = false }

    /** Apaga TODOS os dados do usuário na nuvem (local preservado). */
    fun wipeCloud() = viewModelScope.launch {
        _confirmWipe.value = false
        _busy.value = true
        val err = auth.wipeCloud()
        _error.value = err ?: ""
        if (err == null) {
            // O Storage sumiu: as linhas locais não podem continuar
            // "uploaded" (o card mostraria backup que não existe).
            db.imageBackupDao().clearAll()
            settings.setImageBackupEnabled(false)
            ImageBackupScheduler.cancelAll(app)
            _restoreReport.value = null
            _message.value = if (BuildConfig.SYNC_BETA) {
                "Dados da nuvem apagados."
            } else {
                "Backup de imagens apagado. O envio automático foi pausado."
            }
        }
        _busy.value = false
    }

    fun lastSyncText(ts: Long): String =
        if (ts == 0L) "Nunca sincronizado"
        else "Último sync: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))}"

    class Factory(private val ctx: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountViewModel(ctx) as T
    }
}
