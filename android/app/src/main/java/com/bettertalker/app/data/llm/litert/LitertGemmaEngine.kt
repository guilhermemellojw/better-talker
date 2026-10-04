package com.bettertalker.app.data.llm.litert

import android.content.Context
import com.bettertalker.app.data.llm.GemmaEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * F2 — motor on-device do Gemma 4 E2B via LiteRT-LM.
 *
 * Medições no A34 (benchmark separado): GPU com contexto 4096 = prefill ~242
 * tok/s (2k em ~8,4 s) e decode ~9 tok/s; CPU = prefill ~70 tok/s.
 *
 * Regras:
 * - Lazy: só carrega no primeiro uso (fora da main thread).
 * - GPU primeiro (ctx [MAX_NUM_TOKENS]); CPU como fallback.
 * - `maxNumTokens` é OBRIGATÓRIO no GPU deste aparelho: com contexto default
 *   o caminho de conversa falha no Mali/OpenCL ("Failed to create custom
 *   tensor buffer").
 * - Conversa nova por chamada = contexto limpo; thinking desligado.
 */
class LitertGemmaEngine private constructor(private val appContext: Context) : GemmaEngine {

    companion object {
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MAX_NUM_TOKENS = 4096

        @Volatile
        private var instance: LitertGemmaEngine? = null

        fun get(context: Context): LitertGemmaEngine =
            instance ?: synchronized(this) {
                instance ?: LitertGemmaEngine(context.applicationContext).also { instance = it }
            }

        /**
         * F2 (dev): aceita o modelo no diretório externo do app (push direto
         * via adb) e, se ausente, no interno `files/models` (destino do fluxo
         * de download). Devolve o caminho existente; senão, o interno.
         */
        fun modelFile(context: Context): File {
            val internal = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)
            if (internal.isFile) return internal
            val external = context.getExternalFilesDir("models")?.let { File(it, MODEL_FILE) }
            if (external != null && external.isFile) return external
            return internal
        }

        fun isModelPresent(context: Context): Boolean = modelFile(context).isFile
    }

    private val mutex = Mutex()
    private var engine: Engine? = null

    @Volatile
    var backendLabel: String = ""
        private set

    private suspend fun ensureLoaded() {
        if (engine != null) return
        mutex.withLock {
            if (engine != null) return
            val path = modelFile(appContext).absolutePath
            require(File(path).isFile) { "modelo local ausente" }
            withContext(Dispatchers.IO) {
                engine = try {
                    Engine(
                        EngineConfig(
                            modelPath = path,
                            backend = Backend.GPU(),
                            maxNumTokens = MAX_NUM_TOKENS,
                            cacheDir = appContext.cacheDir.path,
                        )
                    ).also { it.initialize() }.also { backendLabel = "GPU" }
                } catch (gpuError: Throwable) {
                    Engine(
                        EngineConfig(
                            modelPath = path,
                            backend = Backend.CPU(),
                            maxNumTokens = MAX_NUM_TOKENS,
                            cacheDir = appContext.cacheDir.path,
                        )
                    ).also { it.initialize() }.also { backendLabel = "CPU" }
                }
            }
        }
    }

    /** Expõe o carregamento lazy para medição de fase (no-op se já está). */
    override suspend fun warmup() = ensureLoaded()

    /** Gera texto com contexto limpo (conversa nova por chamada). */
    override fun generate(system: String, user: String, maxOutputTokens: Int): Flow<String> = flow {
        ensureLoaded()
        val e = engine ?: error("engine LiteRT não carregado")
        e.createConversation(
            ConversationConfig(
                systemInstruction = if (system.isBlank()) null else Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2),
            )
        ).use { conversation ->
            conversation.sendMessageAsync(
                user,
                maxOutputToken = maxOutputTokens.coerceIn(64, 2048),
                thinkingConfig = ThinkingConfig(false, 0),
            ).collect { msg ->
                val text = msg.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) emit(text)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        engine?.close()
        engine = null
        backendLabel = ""
    }
}
