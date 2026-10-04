package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmAction
import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DossierFidelityCheck
import com.bettertalker.app.domain.planning.SectionDraft
import com.bettertalker.app.domain.planning.SectionGenerator
import kotlinx.coroutines.CancellationException

/**
 * F2.3 — gera o mini discurso do tópico como UM texto contínuo.
 *
 * Diferente do [SectionGeneratorImpl] (JSON_SCHEMA, remotos), este caminho
 * usa texto puro, porque o Gemma local (LiteRT) não segue schema. É o
 * gerador injetado quando o provider selecionado é o local.
 *
 * O [LlmProvider] local já aplica o [com.bettertalker.app.data.llm.GroundednessVerifier]
 * após a geração — o "verifier protege" continua valendo aqui.
 *
 * Falhas tratáveis (resposta vazia, exceção) → null. CancellationException
 * propaga (structured concurrency).
 */
class MiniSpeechGenerator(
    private val llmProvider: LlmProvider,
    private val promptBuilder: DossierPromptBuilder = DefaultDossierPromptBuilder(),
    private val maxOutputTokens: Int = 700,
    private val timeoutMs: Long = 180_000L,
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : SectionGenerator {

    override suspend fun generate(dossier: Dossier): SectionDraft? {
        val prompt = promptBuilder.buildMiniSpeech(dossier)
        val res = try {
            llmProvider.generate(
                LlmRequest(
                    text = prompt,
                    action = LlmAction.CHAT,
                    responseFormat = ResponseFormat.TEXT,
                    maxAttempts = 1, // sem retry automático — evita queimar cota
                    timeoutMs = timeoutMs,
                    maxOutputTokens = maxOutputTokens,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("mini discurso falhou: ${e::class.simpleName}: ${e.message}")
            return null
        }
        val text = res.text.trim()
        if (text.isBlank()) return null
        val html = paragraphsToHtml(text)
        return SectionDraft(
            textHtml = html,
            usedSources = emptyList(),
            validation = DossierFidelityCheck.check(html, emptyList(), dossier),
            possiblyTruncated = res.meta.finishReason == "length",
        )
    }

    /**
     * Converte texto puro em HTML simples (`<p>` por parágrafo). Quebra por
     * linha em branco; sem linhas em branco, cada linha vira um parágrafo.
     */
    internal fun paragraphsToHtml(text: String): String {
        val rawParagraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text.trim()) }
        return rawParagraphs.joinToString("") { "<p>${escapeHtml(it)}</p>" }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
