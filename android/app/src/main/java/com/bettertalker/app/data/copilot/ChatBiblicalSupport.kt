package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.util.RefCanonicalizer
import com.bettertalker.app.data.util.RefDetector

/**
 * Fail-safe bíblico para a resposta de chat (P0) — contrato determinístico e testável.
 *
 * Detecta, na resposta gerada pelo modelo, citações bíblicas (livros + versículos)
 * que não têm suporte em EVIDÊNCIA DOCUMENTAL elegível (corpus injectado). Texto do
 * usuário, S-34, BE/TH e memórias do Mentor não são fonte documental — é falta de
 * retorno, não desconfiança.
 *
 * Não impõe bloqueio: gera o notice visível (⚠️) que informa que a citação não
 * consta do acervo local. A resposta segue após informação correta, como desejado.
 *
 * Puro/testável: sem Android, sem IO, sem rede, sem LLM.
 */
object ChatBiblicalSupport {

    /** Citação bíblica detectada na resposta, sem suporte documental no corpus. */
    data class BiblicalGap(val label: String, val chapter: Int, val verse: Int) {
        val ref: String get() = "$label $chapter:$verse"
    }

    /**
     * Detecta, na resposta, versículos bíblicos citados mas não presentes no
     * corpus documental (canonicalizado — espelho do GroundednessVerifier).
     *
     * Sem correspondência no corpus, nem hash relevante/ranking — a verdade é
     * o corpus. A resposta pode conter citações a partir da base do modelo.
     */
    fun biblicalGaps(
        answer: String,
        documentaryCorpus: List<String>,
    ): List<BiblicalGap> {
        if (answer.isBlank()) return emptyList()
        val canonCorpus = documentaryCorpus
            .map(GroundednessVerifier::normalizeForComparison)
            .flatMap(RefCanonicalizer::canonicalize)
            .toSet()
        return RefDetector.detectBible(answer)
            .map { ref ->
                val form = RefCanonicalizer.canonicalBook("${ref.label} ${ref.chapter}:${ref.verse}")
                ref to canonCorpus.none { it.contains(form) }
            }
            .filter { it.second }
            .map { BiblicalGap(label = it.first.label, chapter = it.first.chapter, verse = it.first.verse) }
            .distinctBy { Triple(it.label, it.chapter, it.verse) }
    }

    /** Notice visível — mesma família dos avisos do chat. Puro/testável. */
    fun notice(gaps: List<BiblicalGap>): String? =
        gaps.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.ref }
            ?.let { refs ->
                "⚠️ Citação(ões) bíblicas sem retorno do acervo local: $refs. " +
                    "Use paráfrase, ou informe que consultei apenas o acervo local."
            }

    /**
     * Monta o notice final do chat = aviso existente + notice bíblico
     * (semelhante: noticeInparkStyle). Puro/testável.
     */
    fun noticeWithBiblicalSafety(
        existingNotice: String?,
        answer: String,
        corpus: List<String>,
    ): String? {
        val biblicalNotice = if (existingNotice == null) notice(biblicalGaps(answer, corpus)) else null
        return listOfNotNull(existingNotice, biblicalNotice).joinToString("\n\n").ifBlank { null }
    }
}
