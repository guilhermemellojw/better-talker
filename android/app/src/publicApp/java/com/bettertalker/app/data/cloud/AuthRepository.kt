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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 5.4 — login opt-in do build público.
 *
 * Usa Firebase Auth apenas para identificar o dono das imagens no Storage.
 * Notas, chat e ajustes continuam locais; Firestore não existe neste flavor.
 */
class AuthRepository(private val ctx: Context) {

    private val _user = MutableStateFlow<CloudUser?>(null)
    val user = _user.asStateFlow()

    private val _accountConflict = MutableStateFlow<String?>(null)
    val accountConflict = _accountConflict.asStateFlow()

    private val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx)

    @Volatile
    private var lastUidHint: String? = null

    private var listener: FirebaseAuth.AuthStateListener? = null

    fun isConfigured(): Boolean =
        runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    private fun authOrNull(): FirebaseAuth? =
        if (isConfigured()) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    fun start() {
        val auth = authOrNull() ?: return
        val current = auth.currentUser
        _user.value = current?.toCloudUser()
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
     * [uiContext] precisa ser Activity (Credential Manager abre seletor).
     */
    suspend fun signIn(uiContext: android.content.Context = ctx): String? {
        if (!isConfigured()) return "Firebase não configurado neste build."
        val auth = authOrNull() ?: return "Firebase não configurado neste build."
        val serverId = webClientId()
            ?: return "Client ID OAuth ausente (google-services.json)."
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
            awaitTask { cb -> auth.signInWithCredential(fbCred).addOnCompleteListener(cb) }

            val signed = auth.currentUser
            val uid = signed?.uid
            val lastUid = settings.lastUidValue() ?: lastUidHint
            if (accountSwitchBlocked(lastUid, uid)) {
                runCatching { auth.signOut() }
                _user.value = null
                _accountConflict.value = uid
                return "Este aparelho está vinculado a outra conta. " +
                    "Apague os dados locais para entrar com outra conta."
            }
            if (uid != null) {
                lastUidHint = uid
                settings.setLastUid(uid)
            }
            _user.value = signed?.toCloudUser()
            null
        } catch (e: GetCredentialCancellationException) {
            null
        } catch (e: NoCredentialException) {
            "Sem credenciais: adicione uma conta Google no aparelho e confira o " +
                "SHA-1 deste build registrado no Firebase."
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

    fun clearLastUidBinding() {
        lastUidHint = null
    }

    /** Apaga o backup de imagens da conta (o local não é tocado). */
    suspend fun wipeCloud(): String? {
        val uid = currentUser()?.uid ?: return "Sem login"
        return com.bettertalker.app.data.images.ImageCloudWiper.wipe(uid)
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

private fun FirebaseUser.toCloudUser(): CloudUser = CloudUser(uid = uid, email = email)
