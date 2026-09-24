package com.bettertalker.app.data.ai

/**
 * Aponta para o .task hospedado (Fase 0: litert-community Qwen2.5-1.5B
 * multi-prefill q8 ekv1280, ~1.598 GB, Apache-2.0).
 * Preencher URL + SHA-256 quando hospedado; sem isso o download não inicia.
 */
object LlmModelConfig {
    const val FILE_NAME = "qwen15-q8.task"
    const val EXPECTED_BYTES = 1597913616L
    const val DOWNLOAD_URL = ""
    const val SHA256 = ""

    fun configured(): Boolean = DOWNLOAD_URL.isNotBlank() && SHA256.isNotBlank()
}
