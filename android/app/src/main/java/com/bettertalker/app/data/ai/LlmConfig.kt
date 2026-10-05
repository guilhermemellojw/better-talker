package com.bettertalker.app.data.ai

/**
 * Trava do parâmetro + portões do modelo local.
 * Temperatura zero + topK 1 = decodificação gulosa, determinística.
 * Valores testados em teste unitário (a sessão real só existe no aparelho).
 */
object LlmConfig {
    const val TEMPERATURE: Float = 0.0f
    const val TOP_K: Int = 1
    const val TOP_P: Float = 1.0f
    const val MAX_TOKENS: Int = 1024

    /** Piso de RAM para ativar o LLM (8 GB); abaixo, motor determinístico. */
    const val MIN_RAM_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Teto do arquivo do modelo (3 GiB; Gemma 4 E2B tem ~2,59 GB — o teto antigo de 2 GiB o rejeitava). */
    const val MAX_MODEL_BYTES: Long = 3L * 1024 * 1024 * 1024

    fun ramOk(totalMemBytes: Long): Boolean = totalMemBytes >= MIN_RAM_BYTES
    fun sizeOk(modelBytes: Long): Boolean = modelBytes in 1..MAX_MODEL_BYTES
}
