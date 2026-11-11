package com.bettertalker.app.data.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 5.8: login Google -> Firebase Auth, disponível nos dois flavors. */
class AuthRepository(private val ctx: Context) {

    private val _user = MutableStateFlow<CloudUser?>(null)
    val user = _user.asStateFlow()

    /**
     * UID da conta que tentou entrar em um aparelho já vinculado a outra.
     * A UI usa isso para oferecer "apagar dados locais e tentar de novo".
     */
    private val _accountConflict = MutableStateFlow<String?>(null)
    val accountConflict = _accountConflict.asStateFlow()

    private val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx)

    /** Espelho em memória do vínculo; o DataStore guarda o valor durável. */
    @Volatile
    private var lastUidHint: String? = null

    private var listener: FirebaseAuth.AuthStateListener? = null

    fun isConfigured(): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    private fun authOrNull(): FirebaseAuth? =
        if (isConfigured()) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    fun start() {
        val auth = authOrNull() ?: return
        val current = auth.currentUser
        _user.value = current?.toCloudUser()
        // Upgrade de versão anterior: mantém o vínculo em memória para
        // bloquear troca silenciosa; o SyncWorker persiste no 1º sync.
        lastUidHint = current?.uid
        listener = FirebaseAuth.AuthStateListener { _user.value = it.currentUser?.toCloudUser() }
        auth.addAuthStateListener(listener!!)
    }

    fun stop() {
        val auth = authOrNull()
        listener?.let { l -> runCatching { auth?.removeAuthStateListener(l) } }
        listener = null
    }

    fun currentUser(): CloudUser? = authOrNull()?.currentUser?.toCloudUser()

    @android.annotation.SuppressLint("DiscouragedApi")
    private fun webClientId(): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        if (id == 0) return null
        return runCatching { ctx.getString(id) }.getOrNull()
    }

    /**
     * Entra com Google. Retorna null em sucesso ou mensagem de erro.
     *
     * [uiContext] precisa ser uma Activity (Credential Manager lança o seletor
     * de contas como UI; contexto de aplicação falha no Android 12 com
     * "failed to launch the selector ui").
     */
    suspend fun signIn(uiContext: android.content.Context = ctx): String? {
        if (!isConfigured()) return "Firebase não configurado neste build."
        val serverId = webClientId() ?: return "Client ID OAuth ausente (google-services.json)."
        _accountConflict.value = null
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverId)
                .build()
            val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val res = CredentialManager.create(uiContext).getCredential(uiContext, req)
            val google = GoogleIdTokenCredential.createFrom(res.credential.data)
            val fbCred = GoogleAuthProvider.getCredential(google.idToken, null)
            awaitTask { cb -> authOrNull()!!.signInWithCredential(fbCred).addOnCompleteListener(cb) }

            val signed = authOrNull()?.currentUser
            val uid = signed?.uid
            val lastUid = settings.lastUidValue() ?: lastUidHint
            val freshBinding = lastUid == null
            if (accountSwitchBlocked(lastUid, uid)) {
                // Guard mínimo de multi-conta: não deixa as notas locais
                // subirem para a conta nova. O usuário pode apagar os dados
                // locais e tentar de novo.
                runCatching { authOrNull()?.signOut() }
                _user.value = null
                _accountConflict.value = uid
                return "Este aparelho está vinculado a outra conta. " +
                    "Apague os dados locais para entrar com outra conta."
            }
            if (uid != null) {
                lastUidHint = uid
                settings.setLastUid(uid)
                // M2: no primeiro vínculo deste aparelho, não decidir sozinho.
                if (freshBinding) settings.setSyncFirstPending(true)
            }
            _user.value = signed?.toCloudUser()
            // Primeiro vínculo com notas locais: espera a escolha
            // Enviar/Baixar/Mesclar antes de agendar qualquer sync.
            if (!freshBinding) {
                SyncScheduler.requestSync(ctx)
                SyncScheduler.ensurePeriodic(ctx)
            }
            null
        } catch (e: GetCredentialCancellationException) {
            null // usuário cancelou: sem erro
        } catch (e: NoCredentialException) {
            "Sem credenciais: adicione uma conta Google no aparelho e confira o " +
                "SHA-1 deste build registrado no Firebase (mostrado abaixo)."
        } catch (e: Exception) {
            e.message?.take(200) ?: "Falha no login."
        }
    }

    fun signOut() {
        val auth = authOrNull()
        val uid = auth?.currentUser?.uid
        runCatching { auth?.signOut() }
        _user.value = null
        _accountConflict.value = null
        if (uid != null) {
            // Upgrade de versão anterior: persiste o vínculo mesmo se o
            // usuário sair antes do primeiro sync. O check de hint evita
            // ressuscitar o vínculo depois de um wipe local.
            if (lastUidHint == null) lastUidHint = uid
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                if (lastUidHint == uid && settings.lastUidValue() == null) {
                    settings.setLastUid(uid)
                }
            }
        }
    }

    fun clearAccountConflict() {
        _accountConflict.value = null
    }

    /** Limpeza local explícita: desfaz o vínculo deste aparelho. */
    fun clearLastUidBinding() {
        lastUidHint = null
    }

    /** 5.8: apaga só o backup de imagens, sem tocar no Firestore de notas. */
    suspend fun wipeImageBackups(): String? {
        val uid = currentUser()?.uid ?: return "Sem login"
        return com.bettertalker.app.data.images.ImageCloudWiper.wipe(uid)
    }

    /** Apaga TODOS os dados do usuário na nuvem (local preservado). */
    suspend fun wipeCloud(): String? {
        val uid = currentUser()?.uid ?: return "Sem login"
        return try {
            val root = FirebaseFirestore.getInstance().collection("users").document(uid)
            for (col in listOf(
                "notes", "folders", "attachments", "outlines",
                "sections", "subPoints", "tombstones",
            )) {
                val snap: QuerySnapshot = suspendTask { cb ->
                    root.collection(col).get().addOnCompleteListener(cb)
                }
                if (snap.documents.isNotEmpty()) {
                    val batch = FirebaseFirestore.getInstance().batch()
                    snap.documents.forEach { batch.delete(it.reference) }
                    suspendTask<Void> { cb -> batch.commit().addOnCompleteListener(cb) }
                }
            }
            // 5.4: o Storage de imagens também pertence à conta.
            com.bettertalker.app.data.images.ImageCloudWiper.wipe(uid)
        } catch (e: Exception) {
            e.message?.take(160) ?: "Falha ao apagar nuvem."
        }
    }

    companion object {
        suspend fun <T> awaitTask(
            block: (com.google.android.gms.tasks.OnCompleteListener<T>) -> Unit
        ): T = suspendTask(block)

        private suspend fun <T> suspendTask(
            block: (com.google.android.gms.tasks.OnCompleteListener<T>) -> Unit
        ): T = suspendCancellableCoroutine { cont ->
            block(com.google.android.gms.tasks.OnCompleteListener { task ->
                if (task.isCanceled) cont.cancel()
                else if (task.isSuccessful) {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(task.result as T) {}
                } else cont.resumeWithException(task.exception ?: Exception("Firebase falhou"))
            })
        }
    }
}

private fun FirebaseUser.toCloudUser(): CloudUser = CloudUser(uid = uid, email = email)
