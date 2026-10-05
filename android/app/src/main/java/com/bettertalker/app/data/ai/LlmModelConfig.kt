package com.bettertalker.app.data.ai

/**
 * T1 — stack único de IA local: **Gemma 4 E2B via LiteRT-LM**.
 *
 * O arquivo `.litertlm` é distribuído pelo repositório oficial
 * `litert-community/gemma-4-E2B-it-litert-lm` (Hugging Face) sob Apache-2.0.
 * A URL aponta para uma **revisão imutável**; atualização de modelo é um bump
 * intencional destas constantes (URL + SHA-256 + bytes).
 *
 * Fonte de verdade única do nome do arquivo para download, engine e UI.
 */
object LlmModelConfig {
    /** Mesmo nome lido por `LitertGemmaEngine.modelFile()` (interno e externo). */
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"

    /** Revisão pinada (2026-08-31) — nunca "main", que pode mudar sem aviso. */
    const val REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"

    const val DOWNLOAD_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "$REVISION/$FILE_NAME"

    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val EXPECTED_BYTES = 2_588_147_712L

    /** Texto exibido na UI (tamanho do download). */
    const val DISPLAY_SIZE = "~2,59 GB"

    // Licença e atribuição (Apache-2.0): usadas no aceite e na tela de licenças.
    const val LICENSE_NAME = "Apache-2.0"
    const val LICENSE_URL = "https://ai.google.dev/gemma/apache_2"
    const val ATTRIBUTION = "Gemma 4 E2B — Google (Apache-2.0) · conversão litert-community"

    fun configured(): Boolean = DOWNLOAD_URL.isNotBlank() && SHA256.isNotBlank()
}
