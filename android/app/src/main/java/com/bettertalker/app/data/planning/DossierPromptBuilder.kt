package com.bettertalker.app.data.planning

import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Monta o prompt do LLM a partir de um [Dossier].
 *
 * Puro: só consome o Dossier e devolve String. Sem I/O, sem Room.
 * Regras anti-alucinação explícitas (espelho de LlmPrompt.buildRagPrompt
 * e OratoryGeneration.BASE_RULES).
 *
 * O prompt varia por role (BODY/INTRO/CONCLUSION) — 3 instruções de
 * tarefa, com regras base compartilhadas.
 */
interface DossierPromptBuilder {
    fun build(dossier: Dossier): String
}

/**
 * Implementação default: prompt estruturado em seções markdown-ish
 * (`## SEÇÃO ATUAL`, `## TEXTOS BÍBLICOS`, …) com degradação graciosa
 * (seções vazias são omitidas).
 *
 * @param refCharLimit teto de chars por texto de ref (trunca com " […]").
 */
class DefaultDossierPromptBuilder(
    private val refCharLimit: Int = 1000,
) : DossierPromptBuilder {

    override fun build(dossier: Dossier): String {
        return buildString {
            appendLine(HEADER.trimIndent())
            appendLine()
            appendSection(dossier)
            appendOverview(dossier)
            appendBibleTexts(dossier)
            appendPublicationTexts(dossier)
            appendMethodPrinciples(dossier)
            appendTransition(dossier)
            appendUnresolved(dossier)
            appendTask(dossier.currentSection.role)
            appendJsonFormat()
        }.trim()
    }

    // ---------- seções ----------

    private fun StringBuilder.appendSection(d: Dossier) {
        appendLine("## SEÇÃO ATUAL")
        appendLine("Título: ${d.currentSection.title}")
        appendLine("Role: ${d.currentSection.role.name}")
        appendLine("Minutos: ${d.currentSection.minutes}")
        val subText = d.currentSubPoint?.outlineText?.takeIf { it.isNotBlank() }
            ?: "(cursor na seção)"
        appendLine("Sub-ponto: $subText")
        d.currentSubPoint?.instruction?.let {
            appendLine("Instrução: $it")
        }
        appendLine()
    }

    private fun StringBuilder.appendOverview(d: Dossier) {
        if (d.overview.isEmpty()) return
        appendLine("## ESTRUTURA DO DISCURSO")
        d.overview.sortedBy { it.order }.forEach { m ->
            appendLine("- [${m.role.name}] ${m.title} (${m.minutes} min)")
        }
        appendLine()
    }

    private fun StringBuilder.appendBibleTexts(d: Dossier) {
        val usable = d.bibleTexts.filter {
            it.text != null && (it.status == ReferenceStatus.RESOLVED ||
                it.status == ReferenceStatus.PARTIAL)
        }
        if (usable.isEmpty()) return
        appendLine("## TEXTOS BÍBLICOS (citação literal autorizada)")
        usable.forEach { bt ->
            val marker = if (bt.status == ReferenceStatus.PARTIAL) " (aprox.)" else ""
            appendLine("- ${bt.ref}$marker: \"${truncate(bt.text!!)}\"")
        }
        appendLine()
    }

    private fun StringBuilder.appendPublicationTexts(d: Dossier) {
        val usable = d.publicationTexts.filter {
            it.text != null && (it.status == ReferenceStatus.RESOLVED ||
                it.status == ReferenceStatus.PARTIAL)
        }
        if (usable.isEmpty()) return
        appendLine("## TRECHOS DE PUBLICAÇÕES (citação literal autorizada)")
        usable.forEach { pt ->
            val marker = if (pt.status == ReferenceStatus.PARTIAL) " (aprox.)" else ""
            val ref = buildString {
                append(pt.ref.symbol)
                pt.ref.page?.let { append(" p. $it") }
                pt.ref.paragraph?.let { append(" §$it") }
            }
            appendLine("- $ref$marker: \"${truncate(pt.text!!)}\"")
        }
        appendLine()
    }

    private fun StringBuilder.appendMethodPrinciples(d: Dossier) {
        if (d.methodPrinciples.isEmpty()) return
        appendLine("## COMO APRESENTAR (princípios de oratória — NÃO É FONTE FACTUAL)")
        d.methodPrinciples.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun StringBuilder.appendTransition(d: Dossier) {
        if (d.currentSection.role == SectionRole.BODY) return
        val t = d.transitionContext ?: return
        appendLine("## TRANSIÇÃO SUGERIDA (resumo do que já foi desenvolvido — NÃO COPIAR)")
        appendLine(t)
        appendLine()
    }

    private fun StringBuilder.appendUnresolved(d: Dossier) {
        if (d.unresolvedRefs.isEmpty()) return
        appendLine("## NÃO CITE ESTAS REFERÊNCIAS (não encontradas no acervo local)")
        d.unresolvedRefs.forEach { appendLine("- $it") }
        appendLine("Se precisar mencioná-las, faça como ref do esboço, sem inventar conteúdo.")
        appendLine()
    }

    private fun StringBuilder.appendTask(role: SectionRole) {
        appendLine("## TAREFA")
        when (role) {
            SectionRole.BODY -> appendLine(BODY_TASK.trimIndent())
            SectionRole.INTRO -> appendLine(INTRO_TASK.trimIndent())
            SectionRole.CONCLUSION -> appendLine(CONCLUSION_TASK.trimIndent())
        }
        appendLine()
    }

    private fun StringBuilder.appendJsonFormat() {
        appendLine("## FORMATO DE SAÍDA")
        appendLine("Responda APENAS com JSON válido, sem markdown:")
        appendLine("""{"text": "<p>...</p><p>...</p>", "usedSources": ["ref1", "ref2"]}""")
        appendLine()
        appendLine("- \"text\": HTML simples com <p> por parágrafo. Pode usar <strong>, <em>.")
        appendLine("- \"usedSources\": lista das refs do material acima que foram efetivamente usadas.")
        appendLine("  Se nenhuma, use [].")
    }

    // ---------- helpers ----------

    private fun truncate(text: String): String =
        if (text.length <= refCharLimit) text
        else text.take(refCharLimit) + " […]"

    private companion object {
        val HEADER = """
            Você é um assistente de redação de discursos.
            Gere APENAS o texto da seção indicada. NÃO invente fatos, citações,
            referências ou doutrina. Use SOMENTE o material fornecido abaixo.
        """

        val BODY_TASK = """
            Desenvolva o sub-ponto acima em 2-3 parágrafos curtos (~150-250 palavras).
            - Use os textos bíblicos literalmente quando citar.
            - NÃO repita o outlineText literalmente; desenvolva a ideia.
            - Se um texto bíblico não foi fornecido, NÃO invente; escreva
              "[desenvolver com base em {ref}]".
        """

        val INTRO_TASK = """
            Escreva uma abertura (~100-150 palavras) que:
            1. Capture a atenção (gancho ou pergunta).
            2. Apresente o tema.
            3. Transicione para o primeiro ponto.
            - Use os textos bíblicos literalmente se citar.
            - NÃO cubra argumentos dos pontos do corpo.
        """

        val CONCLUSION_TASK = """
            Escreva um fechamento (~100-150 palavras) que:
            1. Recapitule brevemente os pontos principais.
            2. Aplique à vida da assistência.
            3. Termine com um convite ou chamada.
            - Use os textos bíblicos literalmente se citar.
            - NÃO introduza argumentos novos.
        """
    }
}
