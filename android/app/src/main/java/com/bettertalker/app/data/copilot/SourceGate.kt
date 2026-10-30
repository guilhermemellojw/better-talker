package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceAction
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.actionLabel
import com.bettertalker.app.data.util.citationReason
import com.bettertalker.app.data.util.gateKey
import com.bettertalker.app.data.util.isTrainingSource
import com.bettertalker.app.data.util.normalizeText

/**
 * Gate determinístico de fontes, antes de qualquer chamada ao LLM.
 *
 * - pergunta ESPECÍFICA que cita uma ref bloqueante (edição ausente, falha de
 *   indexação, ambiguidade ou download/indexação em andamento) → [Ask];
 *   o usuário baixa/reindexa/confirma ou escolhe continuar sem;
 * - pedido AMPLO → [Proceed] com aviso; o inventário do prompt já leva motivo
 *   e ação de cada ref, sem bloquear a conversa;
 * - ref já resolvida ou waived → [Proceed].
 *
 * Puro/testável: sem Android, sem DB, sem rede.
 */
sealed interface SourceGateDecision {
    data class Ask(
        val refs: List<NoteSource>,
        /** A pergunta original fica aguardando a decisão do usuário. */
        val question: String,
    ) : SourceGateDecision

    data class Proceed(
        /** Texto de aviso para prompt/UI; null quando não falta nada. */
        val warning: String?,
    ) : SourceGateDecision
}

object SourceGate {

    fun decide(
        message: String,
        sources: NoteSources?,
        waived: Set<String> = emptySet(),
    ): SourceGateDecision {
        if (sources == null || !sources.hasRefs) return SourceGateDecision.Proceed(null)
        val matched = matchingSources(message, sources)
            .filterNot { it.gateKey() in waived || it.ref in waived }
        val blocking = matched
            .filter { it.citation.blocksAnswer }
            .distinctBy { it.gateKey() }
        if (blocking.isNotEmpty()) {
            return SourceGateDecision.Ask(blocking, message)
        }
        return SourceGateDecision.Proceed(warningFor(sources))
    }

    /**
     * Aviso NÃO bloqueante para pedidos amplos. O prompt recebe o inventário
     * completo; este texto é a versão curta para telemetria/UI/testes.
     */
    fun warningFor(sources: NoteSources?): String? {
        val blocking = sources?.sources
            ?.filter { it.citation.blocksAnswer }
            .orEmpty()
        if (blocking.isEmpty()) return null
        return buildString {
            append("A nota cita fontes que ainda não estão disponíveis no acervo local. ")
            append("NÃO diga que a referência não existe; informe o motivo e a ação. ")
            append("Nunca invente o conteúdo nem use outra edição.\n")
            blocking.take(8).forEach { source ->
                append("- ").append(source.ref).append(": ")
                    .append(citationReason(source))
                if (source.citation.action != SourceAction.NONE) {
                    append(" — ação: ").append(actionLabel(source.citation.action))
                }
                append('\n')
            }
        }.trim()
    }

    /**
     * Bloco de transparência do turno "Continuar sem": registra no prompt que
     * o usuário dispensou aquelas fontes. Não inventa o conteúdo ausente.
     */
    fun waivedBlock(refs: List<NoteSource>): String? {
        if (refs.isEmpty()) return null
        return buildString {
            append("--- FONTES NÃO CONSULTADAS (o usuário optou por continuar sem) ---\n")
            refs.distinctBy { it.gateKey() }.forEach { source ->
                append("- ").append(source.ref).append(": ")
                    .append(citationReason(source))
                if (source.citation.action != SourceAction.NONE) {
                    append(" — ação pendente: ").append(actionLabel(source.citation.action))
                }
                append('\n')
            }
            append(
                "Não invente o conteúdo dessas fontes nem use outra edição. " +
                    "Se a resposta depender delas, diga que não foram consultadas.\n"
            )
            append("--- FIM DAS FONTES NÃO CONSULTADAS ---")
        }
    }

    private fun matchingSources(message: String, sources: NoteSources): List<NoteSource> {
        val pubRefs = RefDetector.detect(message)
        val bibleRefs = RefDetector.detectBible(message)
        val normMessage = normalizeText(message)
        return sources.sources.filter { source ->
            if (source.isTrainingSource()) return@filter false
            when (source.kind) {
                SourceKind.PUBLICATION ->
                    pubRefs.any { ref -> source.matchesPublication(ref) } ||
                        source.refMatches(normMessage)
                SourceKind.BIBLE ->
                    bibleRefs.any { bible ->
                        normalizeText(bible.label) == normalizeText(source.book.orEmpty()) &&
                            bible.chapter == source.chapter &&
                            bible.verse == source.verse
                    } || source.refMatches(normMessage)
            }
        }
    }

    private fun NoteSource.matchesPublication(ref: RefDetector.DetectedRef): Boolean {
        val own = refStatus?.ref
        val ownEditionKey = own?.editionKey ?: editionKey ?: return false
        if (ownEditionKey != ref.editionKey) return false
        if (own == null) return true
        // Unidade citada: se ambos têm §/capítulo/artigo, precisam casar. Um
        // pedido amplo ("w25.07") casa a edição inteira.
        if (ref.paragraph != null && own.paragraph != null &&
            ref.paragraph != own.paragraph
        ) {
            return false
        }
        if (ref.chapter != null && own.chapter != null &&
            normalizeText(ref.chapter) != normalizeText(own.chapter)
        ) {
            return false
        }
        if (ref.article != null && own.article != null &&
            normalizeText(ref.article) != normalizeText(own.article)
        ) {
            return false
        }
        return true
    }

    private fun NoteSource.refMatches(normMessage: String): Boolean {
        val normalizedRef = normalizeText(ref)
        if (normalizedRef.length >= 6 && normMessage.contains(normalizedRef)) return true
        val raw = refStatus?.ref?.raw?.let { normalizeText(it) }.orEmpty()
        return raw.length >= 6 && normMessage.contains(raw)
    }
}
