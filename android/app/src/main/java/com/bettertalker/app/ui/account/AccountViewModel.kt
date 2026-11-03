package com.bettertalker.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.cloud.SyncStatus
import com.bettertalker.app.data.cloud.isConflictNoteTitle
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.prefs.SettingsStore
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AccountViewModel(ctx: android.content.Context) : ViewModel() {
    private val app = ctx.applicationContext
    private val auth = AuthRepository(app)
    private val settings = SettingsStore(app)

    val user = auth.user
    val accountConflict = auth.accountConflict
    val lastSync = settings.lastSync.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // M2: primeiro sync deste aparelho pede escolha (Enviar/Baixar/Mesclar).
    private val db = com.bettertalker.app.data.db.DbProvider.get(app)
    private val _localNoteCount = MutableStateFlow(0)
    val showFirstSync: StateFlow<Boolean> = combine(
        user,
        settings.syncFirstPending,
        _localNoteCount,
    ) { u, pending, count -> u != null && pending && count > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
    private val _confirmWipe = MutableStateFlow(false)
    val confirmWipe = _confirmWipe.asStateFlow()
    private val _confirmLocalWipe = MutableStateFlow(false)
    val confirmLocalWipe = _confirmLocalWipe.asStateFlow()

    init {
        auth.start()
        SyncScheduler.ensurePeriodic(app)
        viewModelScope.launch {
            val count = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { db.noteDao().allIncludingTrashed().size }.getOrDefault(0)
            }
            _localNoteCount.value = count
            // Sem notas locais não há o que decidir: segue o merge normal.
            if (settings.syncFirstPendingValue() && count == 0) {
                settings.setSyncFirstPending(false)
                SyncScheduler.requestSync(app)
            }
        }
    }

    override fun onCleared() { auth.stop() }

    fun signIn(uiContext: android.content.Context? = null) = viewModelScope.launch {
        _busy.value = true
        val err = auth.signIn(uiContext ?: app)
        // Com conflito de conta, o diálogo dedicado explica e oferece a
        // limpeza; evita snackbar duplicado.
        _error.value = if (auth.accountConflict.value != null) "" else err ?: ""
        _busy.value = false
    }

    fun signOut() = auth.signOut()

    /** M2: aplica a escolha do primeiro sync. */
    fun firstSync(mode: String) = viewModelScope.launch {
        settings.setSyncFirstPending(false)
        SyncScheduler.requestFirstSync(app, mode)
    }
    fun syncNow() = SyncScheduler.requestSync(app)
    fun consumeError() { _error.value = "" }

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
            _error.value = auth.signIn(uiContext ?: app) ?: ""
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
            _error.value = "Dados locais apagados."
        } catch (e: Exception) {
            _error.value = e.message?.take(160) ?: "Falha ao apagar os dados locais."
        }
        _busy.value = false
    }

    private suspend fun clearLocalData(resetUid: Boolean) =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.bettertalker.app.data.db.DbProvider.get(app).clearAllTables()
            java.io.File(app.filesDir, "publicacoes").deleteRecursively()
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
        try {
            val uid = auth.currentUser()?.uid ?: throw Exception("Sem login")
            val root = FirebaseFirestore.getInstance().collection("users").document(uid)
            for (col in listOf(
                "notes", "folders", "attachments", "outlines",
                "sections", "subPoints", "tombstones",
            )) {
                val snap: QuerySnapshot = suspendTask { cb -> root.collection(col).get().addOnCompleteListener(cb) }
                if (snap.documents.isNotEmpty()) {
                    val batch = FirebaseFirestore.getInstance().batch()
                    snap.documents.forEach { batch.delete(it.reference) }
                    suspendTask<Void> { cb -> batch.commit().addOnCompleteListener(cb) }
                }
            }
        } catch (e: Exception) {
            _error.value = e.message?.take(160) ?: "Falha ao apagar nuvem."
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

private suspend fun <T> suspendTask(
    block: (com.google.android.gms.tasks.OnCompleteListener<T>) -> Unit
): T = suspendCancellableCoroutine { cont ->
    block(com.google.android.gms.tasks.OnCompleteListener { task: Task<T> ->
        if (task.isCanceled) cont.cancel()
        else if (task.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            cont.resume(task.result as T) {}
        } else cont.resumeWithException(task.exception ?: Exception("Firebase falhou"))
    })
}
