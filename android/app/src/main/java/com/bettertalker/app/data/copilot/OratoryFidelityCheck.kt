package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34RefType

/**
 * Fase 20-C — verificação objetiva da fidelidade da geração (§§37-39).
 *
 * Contagens, não julgamento: referências citadas que não estão autorizadas,
 * vazamento de referências de outro ponto e números sem apoio literal nas
 * fontes. Não há score global nem "melhor texto" (§51 F20-B).
 *
 * Complementa o F6: aqui é inspeção ESTRUTURAL do texto gerado contra o
 * `Spec` da F20-B; a análise de claims continua sendo a do F6 existente.
 *
 * Puro/testável: sem rede, LLM, banco.
 */
object OratoryFidelityCheck {

    data class Report(
        /** Referências citadas que não pertencem ao contexto autorizado. */
        val inventedReferences: List<String>,
        /** Referências de outros pontos que vazaram para este. */
        val leakedReferences: List<String>,
        /** Números citados sem apoio literal no conteúdo autorizado. */
        val unsupportedNumbers: List<String>
    ) {
        val ok: Boolean
            get() = inventedReferences.isEmpty() && leakedReferences.isEmpty() &&
                unsupportedNumbers.isEmpty()
    }

    /** "Tiago 2:17", "João 17:17", "w24.02" — formas que citamos no texto. */
    private val VERSE_RE = Regex("""\b((?:[1-3]\s+)?[A-Za-zÀ-ÿ]+)\s+(\d{1,3})\s*:\s*(\d{1,3})\b""")
    private val PUB_RE = Regex("""\b((?:w|wp|g|gn|be|th|lff)\s*\d{2}(?:[./]\d{1,2})?)\b""", RegexOption.IGNORE_CASE)
    private val NUMBER_RE = Regex("""\b(\d{1,4}(?:[.,]\d{1,2})?)\b""")

    /**
     * Inspeciona [generatedText] contra o [spec]. `authorizedText` é o texto
     * das fontes de conteúdo disponíveis (Bíblia/publicações/section body);
     * números que não aparecem lá são reportados.
     */
    fun check(generatedText: String, spec: OratoryGeneration.Spec): Report {
        val allowedRefs = spec.current?.references.orEmpty().map { it.label }
        val leaked = spec.orderedSections
            .filter { it.sectionId != spec.current?.sectionId }
            .map { it.sectionId }

        // Referências autorizadas normalizadas. O rótulo é a LINHA do S-34
        // ("Leia Tiago 2:17."), então a citação do modelo ("Tiago 2:17") é
        // autorizada por CONTERÇÃO, não por igualdade.
        val allowedKeys = allowedRefs.map { normalizeRef(it) }

        val invented = mutableListOf<String>()
        val leakedRefs = mutableListOf<String>()
        for (m in VERSE_RE.findAll(generatedText)) {
            val key = normalizeRef(m.value)
            if (!allowedKeys.any { it.contains(key) }) {
                // Se pertence a outro ponto do esboço, é vazamento; senão, invenção.
                if (belongsToOtherSection(m.value, spec)) leakedRefs += m.value else invented += m.value
            }
        }
        for (m in PUB_RE.findAll(generatedText)) {
            val key = normalizeRef(m.value)
            if (!allowedKeys.any { it.contains(key) }) {
                if (belongsToOtherSection(m.value, spec)) leakedRefs += m.value
                else invented += m.value
            }
        }

        // Números: só os que existem no conteúdo autorizado contam como apoiados.
        val authorized = buildString {
            append(spec.current?.content.orEmpty()).append(' ')
            spec.current?.subsections?.forEach { append(it).append(' ') }
            spec.current?.references?.forEach { r -> r.text?.let { append(it).append(' ') } }
            spec.contentSources.forEach { append(it.text).append(' ') }
        }
        val unsupported = NUMBER_RE.findAll(generatedText)
            .map { it.groupValues[1] }
            .filter { n ->
                // Números de referência (capítulo:versículo) e de código já tratados acima.
                !authorized.contains(n) && n.length > 1
            }
            .distinct()
            .toList()

        return Report(invented.distinct(), leakedRefs.distinct(), unsupported)
    }

    /**
     * Normaliza para comparar "Tiago 2:17" com "Leia Tiago 2:17." — só
     * alfanuméricos, sem espaços/pontuação.
     */
    private fun normalizeRef(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /** A referência citada pertence a OUTRO ponto do esboço? */
    private fun belongsToOtherSection(cited: String, spec: OratoryGeneration.Spec): Boolean {
        val key = normalizeRef(cited)
        // Nota: só o ponto SEGUINTE expõe referências no Spec; vazamento de
        // outros pontos aparece como "inventada" — o relatório distingue os
        // dois rótulos, mas ambos são falha de fidelidade.
        // Sem o texto completo dos outros pontos no Spec, usamos o rótulo
        // conhecido das referências que a estrutura expõe: vazamento só é
        // detectável quando o ponto seguinte/traversal traz suas referências.
        // F20-F1: os rótulos precisam da MESMA normalização da chave — antes
        // eram comparados crus ("Leia Hebreus 10:23." nunca contém
        // "hebreus1023"), o que tornava `leaked` inalcançável e classificava
        // tudo como invenção. Reproduzido em teste puro, sem rede.
        val otherLabels = buildList {
            spec.next?.references?.forEach { add(normalizeRef(it.label)) }
        }
        return otherLabels.any { n -> n.contains(key) || key.contains(n) }
    }

    /** Tipos presentes no texto gerado — útil para o relatório de aceitação. */
    fun citedTypes(generatedText: String): Set<S34RefType> {
        val out = mutableSetOf<S34RefType>()
        if (VERSE_RE.containsMatchIn(generatedText)) out += S34RefType.BIBLE
        if (PUB_RE.containsMatchIn(generatedText)) out += S34RefType.PUBLICATION
        return out
    }
}
