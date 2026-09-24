package com.bettertalker.app.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Inferência on-device (MediaPipe) com as travas de [LlmConfig].
 * Sem modelo baixado, o chat usa o motor determinístico.
 */
class LlmService(private val ctx: Context) {
    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null

    fun modelPath(): String =
        java.io.File(java.io.File(ctx.filesDir, "models"), LlmModelConfig.FILE_NAME).absolutePath

    fun isReady(): Boolean = java.io.File(modelPath()).exists()

    /** Gera resposta completa. Cancelável (cancela a geração async). */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        val path = modelPath()
        if (!java.io.File(path).exists()) {
            return@withContext Result.failure(IllegalStateException("Modelo não baixado."))
        }
        try {
            ensureSession(path)
            val s = session ?: return@withContext Result.failure(IllegalStateException("Sessão indisponível."))
            s.addQueryChunk(prompt)
            val fut = s.generateResponseAsync()
            try {
                while (!fut.isDone) {
                    ensureActive()
                    delay(100)
                }
                Result.success(fut.get())
            } catch (e: CancellationException) {
                runCatching { s.cancelGenerateResponseAsync() }
                throw e
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun ensureSession(path: String) {
        if (session != null) return
        close()
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(LlmConfig.MAX_TOKENS)
            .build()
        llm = LlmInference.createFromOptions(ctx, opts)
        val sOpts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(LlmConfig.TEMPERATURE)
            .setTopK(LlmConfig.TOP_K)
            .setTopP(LlmConfig.TOP_P)
            .build()
        session = LlmInferenceSession.createFromOptions(llm, sOpts)
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { llm?.close() }
        session = null
        llm = null
    }
}
