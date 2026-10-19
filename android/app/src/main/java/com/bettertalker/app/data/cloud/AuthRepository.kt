package com.bettertalker.app.data.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Puro/testável: troca de conta bloqueada pelo guard de 1 conta/aparelho. */
internal fun accountSwitchBlocked(lastUid: String?, newUid: String?): Boolean =
    lastUid != null && newUid != null && lastUid != newUid

/** Login Google -> Firebase Auth. Inativo sem google-services.json (100% local). */
class AuthRepository(private val ctx: Context) {

    private val _user = MutableStateFlow<FirebaseUser?>(null)
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
        _user.value = current
        // Upgrade de versão anterior: mantém o vínculo em memória para
        // bloquear troca silenciosa; o SyncWorker persiste no 1º sync.
        lastUidHint = current?.uid
        listener = FirebaseAuth.AuthStateListener { _user.value = it.currentUser }
        auth.addAuthStateListener(listener!!)
    }

    fun stop() {
        val auth = authOrNull()
        listener?.let { l -> runCatching { auth?.removeAuthStateListener(l) } }
        listener = null
    }

    fun currentUser(): FirebaseUser? = authOrNull()?.currentUser

    private fun webClientId(): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        if (id == 0) return null
        return runCatching { ctx.getString(id) }.getOrNull()
    }

    /** Entra com Google. Retorna null em sucesso ou mensagem de erro. */
    suspend fun signIn(): String? {
        if (!isConfigured()) return "Firebase não configurado neste build."
        val serverId = webClientId() ?: return "Client ID OAuth ausente (google-services.json)."
        _accountConflict.value = null
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverId)
                .build()
            val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val res = CredentialManager.create(ctx).getCredential(ctx, req)
            val google = GoogleIdTokenCredential.createFrom(res.credential.data)
            val fbCred = GoogleAuthProvider.getCredential(google.idToken, null)
            awaitTask { cb -> authOrNull()!!.signInWithCredential(fbCred).addOnCompleteListener(cb) }

            val signed = authOrNull()?.currentUser
            val uid = signed?.uid
            val lastUid = settings.lastUidValue() ?: lastUidHint
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
            }
            _user.value = signed
            SyncScheduler.requestSync(ctx)
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

    companion object {
        suspend fun <T> awaitTask(
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
