package com.bettertalker.app.data.voice

import android.content.Context
import java.io.File

/**
 * Ownership de TTS no escopo do PROCESSO.
 *
 * Antes, cada ViewModel chamava `shutdown()` no `onCleared()` e sair de uma
 * tela matava o TTS/Piper para o app inteiro. O [VoiceRouter] e o
 * [PiperModelStore] agora são singletons criados com `applicationContext`:
 *
 * - Telas/ViewModels usam [router]/[store] e só chamam `stop()` ao sair.
 * - Nenhuma tela chama `shutdown()`.
 * - [shutdown] existe apenas para testes/desligamento final explícito.
 *
 * O `VoiceRouter` mantém um `CoroutineScope` de processo; isso é intencional
 * (o engine sobrevive à navegação e à rotação).
 */
object TtsLifecycle {
    private val lock = Any()
    private val users = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var cachedStore: PiperModelStore? = null

    @Volatile
    private var cachedRouter: VoiceRouter? = null

    /** Quantas composições de tela estão usando o TTS (para parar ao sair). */
    val activeUsers: Int get() = users.get()

    fun acquire() {
        users.incrementAndGet()
    }

    /**
     * Saída de uma tela. No último usuário, para a fala — NUNCA derruba o
     * engine, que é de processo. Release extra é tolerado (clampa em 0).
     */
    fun release() {
        val remaining = users.decrementAndGet()
        if (remaining <= 0) {
            users.set(0)
            cachedRouter?.stop()
        }
    }

    /** Store único (modelo Piper em filesDir/piper/jeff). */
    fun store(context: Context): PiperModelStore {
        cachedStore?.let { return it }
        synchronized(lock) {
            cachedStore?.let { return it }
            val app = context.applicationContext
            val created = PiperModelStore(
                modelDir = File(app.filesDir, "piper/jeff"),
                openAsset = { path -> app.assets.open(path) },
            )
            cachedStore = created
            return created
        }
    }

    /** Router único do processo; reutilizado por Chat, Ensaio e Tribuna. */
    fun router(context: Context): VoiceRouter {
        cachedRouter?.let { return it }
        synchronized(lock) {
            cachedRouter?.let { return it }
            val app = context.applicationContext
            val store = store(app)
            val created = VoiceRouter(
                native = TextToSpeechManager(app),
                piper = PiperTtsManager(store.modelDir),
                modelDir = store.modelDir,
            )
            cachedRouter = created
            return created
        }
    }

    /** Para fala em andamento sem derrubar o engine (saída de tela/app). */
    fun stop() {
        cachedRouter?.stop()
    }

    /** Apenas testes/desligamento final explícito. Nunca chamar por tela. */
    fun shutdown() {
        synchronized(lock) {
            cachedRouter?.shutdown()
            cachedRouter = null
            cachedStore = null
            users.set(0)
        }
    }

    /** Zera o contador entre testes; não toca no engine. */
    internal fun resetForTest() {
        users.set(0)
    }
}
