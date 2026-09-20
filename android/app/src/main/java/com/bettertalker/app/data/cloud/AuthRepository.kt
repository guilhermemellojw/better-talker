package com.bettertalker.app.data.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Login Google -> Firebase Auth. Inativo sem google-services.json (100% local). */
class AuthRepository(private val ctx: Context) {

    private val _user = MutableStateFlow<FirebaseUser?>(null)
    val user = _user.asStateFlow()

    private var listener: FirebaseAuth.AuthStateListener? = null

    fun isConfigured(): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    private fun authOrNull(): FirebaseAuth? =
        if (isConfigured()) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    fun start() {
        val auth = authOrNull() ?: return
        _user.value = auth.currentUser
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
            _user.value = authOrNull()?.currentUser
            SyncScheduler.requestSync(ctx)
            null
        } catch (e: Exception) {
            e.message?.take(200) ?: "Falha no login."
        }
    }

    fun signOut() {
        runCatching { authOrNull()?.signOut() }
        _user.value = null
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
