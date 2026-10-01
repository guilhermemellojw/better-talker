package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.OutlineGenerationRequest
import com.bettertalker.app.domain.planning.OutlineGenerator
import com.bettertalker.app.domain.planning.OutlineProposal
import kotlinx.coroutines.CancellationException

/**
 * Implementação real de [OutlineGenerator] sobre um [LlmProvider] remoto.
 *
 * Fluxo: prompt via [OutlinePromptBuilder] → [LlmRequest] com
 * `responseFormat = JSON_SCHEMA` + [OUTLINE_SCHEMA] → `generate` →
 * parse via [OutlineResponseParser]. Qualquer falha (erro tipado do provider,
 * texto vazio, JSON inválido) retorna null; só [CancellationException] propaga
 * (padrão 1.3b/2.1, structured concurrency preservada).
 *
 * Limitação conhecida: se o provider configurado for GeminiProvider,
 * JSON_SCHEMA comporta-se como TEXT (sem garantia estrutural) — o Gemini
 * ignora o schema. Recomendado usar QwenProvider (Groq).
 */
class OutlineGeneratorImpl(
    private val llmProvider: LlmProvider,
    private val promptBuilder: OutlinePromptBuilder = DefaultOutlinePromptBuilder(),
    private val responseParser: OutlineResponseParser = DefaultOutlineResponseParser(),
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : OutlineGenerator {

    override suspend fun generate(request: OutlineGenerationRequest): OutlineProposal? {
        val prompt = promptBuilder.build(request)
        val llmRequest = LlmRequest(
            text = prompt,
            responseFormat = ResponseFormat.JSON_SCHEMA,
            jsonSchema = OUTLINE_SCHEMA,
            maxAttempts = 1, // sem retry automático — evita queimar cota em 429
            timeoutMs = 30_000L, // 30s (free tier é rápido)
            maxOutputTokens = 1500, // cabe no TPM de 8K
        )
        val response = try {
            llmProvider.generate(llmRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Diagnóstico interno; o usuário segue vendo só o texto genérico.
            log("esboço falhou: ${e::class.simpleName}: ${e.message}")
            return null
        }
        if (response.text.isBlank()) return null
        return responseParser.parse(response.text, request)
    }

    internal companion object {
        /**
         * JSON Schema do esboço (draft compatível com o modo estrito do Groq,
         * mesmo envelope de EDIT_PROPOSAL_SCHEMA do QwenProvider: name + strict).
         * O modo estrito exige `additionalProperties:false` E `required` com
         * TODAS as propriedades em cada objeto, senão o Groq devolve HTTP 400.
         */
        const val OUTLINE_SCHEMA: String =
            "{\"name\":\"outline\",\"strict\":true,\"schema\":" +
                "{\"type\":\"object\",\"properties\":" +
                "{\"title\":{\"type\":\"string\"}," +
                "\"summary\":{\"type\":\"string\"}," +
                "\"sections\":{\"type\":\"array\",\"items\":" +
                "{\"type\":\"object\",\"properties\":" +
                "{\"title\":{\"type\":\"string\"}," +
                "\"minutes\":{\"type\":\"integer\"}," +
                "\"mainIdea\":{\"type\":\"string\"}," +
                "\"bibleRefs\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
                "\"publicationRefs\":{\"type\":\"array\",\"items\":" +
                "{\"type\":\"object\",\"properties\":" +
                "{\"symbol\":{\"type\":\"string\"}," +
                "\"page\":{\"type\":\"integer\"}," +
                "\"paragraph\":{\"type\":\"integer\"}}," +
                "\"required\":[\"symbol\",\"page\",\"paragraph\"]," +
                "\"additionalProperties\":false}}," +
                "\"methodPrinciple\":{\"type\":\"string\"}}," +
                "\"required\":[\"title\",\"minutes\",\"mainIdea\",\"bibleRefs\"," +
                "\"publicationRefs\",\"methodPrinciple\"]," +
                "\"additionalProperties\":false}}}," +
                "\"required\":[\"title\",\"summary\",\"sections\"]," +
                "\"additionalProperties\":false}}"
    }
}
