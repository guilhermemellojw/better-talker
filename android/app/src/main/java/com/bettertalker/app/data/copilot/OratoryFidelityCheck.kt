package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.llm.inSuggestion
import com.bettertalker.app.data.llm.suggestionSpans
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.util.RefCanonicalizer

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
        // #16-fix-f: ranges do S-34 ("Jó 38:31-33") autorizam também os
        // versículos individuais que o modelo cita (38:31, 38:32, 38:33).
        val allowedKeys = (allowedRefs + allowedRefs.flatMap { RefCanonicalizer.expandRanges(it) })
            .map { normalizeRef(it) }

        val invented = mutableListOf<String>()
        val leakedRefs = mutableListOf<String>()
        // T2 — vazamento de referência do ponto seguinte DENTRO de 〈sugestão〉
        // não é falha factual (criação pode citar a conexão), mas referência
        // inventada e número sem apoio continuam contando (regra de ouro).
        val spans = suggestionSpans(generatedText)
        for (m in VERSE_RE.findAll(generatedText)) {
            val key = normalizeRef(m.value)
            // #16-fix-a: contenção bidirecional — cobre tanto o rótulo longo
            // contendo a citação quanto a citação abreviada do modelo.
            if (!allowedKeys.any { it.contains(key) || key.contains(it) }) {
                val other = belongsToOtherSection(m.value, spec)
                if (other && inSuggestion(m.range.first, spans)) continue
                if (other) leakedRefs += m.value else invented += m.value
            }
        }
        for (m in PUB_RE.findAll(generatedText)) {
            val key = normalizeRef(m.value)
            if (!allowedKeys.any { it.contains(key) || key.contains(it) }) {
                val other = belongsToOtherSection(m.value, spec)
                if (other && inSuggestion(m.range.first, spans)) continue
                if (other) leakedRefs += m.value else invented += m.value
            }
        }

        // Números: só os que existem no conteúdo autorizado contam como apoiados.
        val unsupported = NUMBER_RE.findAll(generatedText)
            .filter { m ->
                // #16-fix-a: número precedido de contexto de prosa
                // ("versículo 32", "capítulo 3") não é dado inventado.
                val before = generatedText.substring(0, m.range.first)
                    .lowercase().replace('í', 'i').replace('á', 'a')
                    .filter { it.isLetterOrDigit() || it == ' ' }
                    .takeLast(24)
                !NUMBER_PROSE_CONTEXT.containsMatchIn(before)
            }
            .map { it.groupValues[1] }
            .filter { n -> n.length > 1 && !isAuthorizedNumber(n, spec, generatedText) }
            .distinct()
            .toList()

        return Report(invented.distinct(), leakedRefs.distinct(), unsupported)
    }

    /**
     * #16-fix-c: número com apoio real NÃO é dado inventado quando:
     * 1) faz parte de código de estudo ("S-34", "S-38") no texto;
     * 2) é ano derivado de publicação do spec ("w11" → "2011", "g14" → "2014");
     * 3) aparece no conteúdo autorizado (corpo, subpontos, refs, fontes).
     * Puro/testável.
     */
    fun isAuthorizedNumber(number: String, spec: OratoryGeneration.Spec, text: String): Boolean {
        if (number.isBlank()) return true
        // 1) código de estudo/apostila: o dígito é identificador, não dado.
        if (Regex("""(?i)(?:^|[^\p{L}\d])s-""" + Regex.escape(number) + """\b""")
                .containsMatchIn(text)) return true
        // 2) anos expandidos das publicações citadas no spec.
        if (number in publicationYears(spec)) return true
        // 3) conteúdo autorizado do turno (comportamento anterior preservado).
        return authorizedCorpus(spec).contains(number)
    }

    /**
     * Conteúdo autorizado do turno: corpo, subpontos, rótulos das refs
     * ("(Jó 38:31-33)"), textos das refs e fontes — com os ranges
     * expandidos (#16-fix-f: o "32" dentro de "31-33" é autorizado).
     */
    private fun authorizedCorpus(spec: OratoryGeneration.Spec): String = buildString {
        append(spec.current?.content.orEmpty()).append(' ')
        spec.current?.subsections?.forEach { append(it).append(' ') }
        spec.current?.references?.forEach { r -> append(r.label).append(' ') }
        spec.current?.references?.forEach { r -> r.text?.let { append(it).append(' ') } }
        spec.contentSources.forEach { append(it.text).append(' ') }
        append(RefCanonicalizer.expandRanges(toString()).joinToString(" "))
    }

    /**
     * Anos que as publicações do spec autorizam ("w11 1/7" → 2011,
     * "g14 5/15" → 2014): o modelo expande o código para a data por extenso.
     */
    private fun publicationYears(spec: OratoryGeneration.Spec): Set<String> {
        val labels = buildList {
            spec.current?.references?.forEach { add(it.label) }
            spec.contentSources.forEach { add(it.reference); add(it.label) }
        }
        return labels.flatMap { label ->
            Regex("""(?i)\b[a-z]{1,2}(\d{2})\b""").findAll(label)
                .map { "20" + it.groupValues[1] }
                .toList()
        }.toSet()
    }

    /**
     * #16-fix-a: contexto de prosa que NÃO é dado — "versículo 32",
     * "capítulo 3", "página 12" (o número pertence à ref, não é invenção).
     */
    private val NUMBER_PROSE_CONTEXT =
        Regex("(versiculo|versiculos|cap|capitulo|pag|pagina)\\s*$")

    /**
     * #16-fix-f: canonicalização movida para [RefCanonicalizer] (compartilhada
     * com o verificador). Mantém o nome local para os call sites.
     */
    private fun normalizeRef(text: String): String = RefCanonicalizer.canonicalBook(text)

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
