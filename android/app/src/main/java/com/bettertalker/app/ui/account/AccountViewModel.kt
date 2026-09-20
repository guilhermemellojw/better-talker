package com.bettertalker.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.prefs.SettingsStore
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val lastSync = settings.lastSync.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val configured = auth.isConfigured()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()
    private val _confirmWipe = MutableStateFlow(false)
    val confirmWipe = _confirmWipe.asStateFlow()

    init {
        auth.start()
        SyncScheduler.ensurePeriodic(app)
    }

    override fun onCleared() { auth.stop() }

    fun signIn() = viewModelScope.launch {
        _busy.value = true
        _error.value = auth.signIn() ?: ""
        _busy.value = false
    }

    fun signOut() = auth.signOut()
    fun syncNow() = SyncScheduler.requestSync(app)
    fun consumeError() { _error.value = "" }

    fun askWipe() { _confirmWipe.value = true }
    fun cancelWipe() { _confirmWipe.value = false }

    /** Apaga TODOS os dados do usuário na nuvem (local preservado). */
    fun wipeCloud() = viewModelScope.launch {
        _confirmWipe.value = false
        _busy.value = true
        try {
            val uid = auth.currentUser()?.uid ?: throw Exception("Sem login")
            val root = FirebaseFirestore.getInstance().collection("users").document(uid)
            for (col in listOf("notes", "folders", "attachments", "tombstones")) {
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
