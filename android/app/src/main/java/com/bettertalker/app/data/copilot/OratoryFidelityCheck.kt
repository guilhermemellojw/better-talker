package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.llm.inSuggestion
import com.bettertalker.app.data.llm.suggestionSpans
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
        val authorized = buildString {
            append(spec.current?.content.orEmpty()).append(' ')
            spec.current?.subsections?.forEach { append(it).append(' ') }
            spec.current?.references?.forEach { r -> r.text?.let { append(it).append(' ') } }
            spec.contentSources.forEach { append(it.text).append(' ') }
        }
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
            .filter { n ->
                // Números de referência (capítulo:versículo) e de código já tratados acima.
                !authorized.contains(n) && n.length > 1
            }
            .distinct()
            .toList()

        return Report(invented.distinct(), leakedRefs.distinct(), unsupported)
    }

    /**
     * #16-fix-a: nomes de livros por extenso → abreviação canônica (o S-34 usa
     * "Sal"; o modelo escreve "Salmo"). Aplicado antes da contenção — só
     * quando o nome do livro precede um DÍGITO (a ref), para não poluir
     * palavras ("chamamos" ≠ "cham"+"amos").
     */
    private val BOOK_NORMALIZE: Map<String, String> = mapOf(
        "salmo" to "sal", "salmos" to "sal",
        "daniel" to "da",
        "1corintios" to "1co", "2corintios" to "2co",
        "genesis" to "gen", "exodo" to "ex",
        "levitico" to "le", "numeros" to "num",
        "deuteronomio" to "de", "josue" to "jos",
        "juizes" to "jz", "rute" to "rt",
        "1samuel" to "1sa", "2samuel" to "2sa",
        "1reis" to "1re", "2reis" to "2re",
        "1cronicas" to "1cr", "2cronicas" to "2cr",
        "esdras" to "esd", "neemias" to "ne",
        "ester" to "est",
        "proverbios" to "pr", "eclesiastes" to "ec",
        "cantico" to "ca", "isaias" to "is",
        "jeremias" to "je", "lamentacoes" to "la",
        "ezequiel" to "ez", "oseias" to "os",
        "joel" to "jl", "amos" to "am",
        "obadias" to "ob", "jonas" to "jon",
        "miqueias" to "miq", "habacuque" to "hab",
        "sofonias" to "sof", "ageu" to "ag",
        "zacarias" to "za", "malaquias" to "mal",
        "mateus" to "mt", "marcos" to "mr",
        "lucas" to "lu", "joao" to "jo",
        "atos" to "at", "romanos" to "ro",
        "galatas" to "gal", "efesios" to "ef",
        "filipenses" to "fil", "colossenses" to "col",
        "1tessalonicenses" to "1te", "2tessalonicenses" to "2te",
        "1timoteo" to "1ti", "2timoteo" to "2ti",
        "tito" to "tit", "filemom" to "flm",
        "hebreus" to "he", "tiago" to "tg",
        "1pedro" to "1pe", "2pedro" to "2pe",
        "1joao" to "1jo", "2joao" to "2jo", "3joao" to "3jo",
        "judas" to "ju",
        "revelacao" to "re", "apocalipse" to "re",
    )

    /** Regras pré-compiladas (nome + dígito), maiores primeiro ("1joao" antes de "joao"). */
    private val BOOK_RULES: List<Pair<Regex, String>> = BOOK_NORMALIZE.entries
        .map { (full, abbrev) -> Regex(Regex.escape(full) + "(?=\\d)") to abbrev }
        .sortedByDescending { it.first.pattern.length }

    /**
     * #16-fix-a: contexto de prosa que NÃO é dado — "versículo 32",
     * "capítulo 3", "página 12" (o número pertence à ref, não é invenção).
     */
    private val NUMBER_PROSE_CONTEXT =
        Regex("(versiculo|versiculos|cap|capitulo|pag|pagina)\\s*$")

    /**
     * Normaliza para comparar "Tiago 2:17" com "Leia Tiago 2:17." — minúsculas,
     * sem acentos (o modelo escreve "Coríntios" com í; a regra usa "corintios"),
     * sem espaços/pontuação, com nomes de livros reduzidos à abreviação
     * canônica (#16-fix-a).
     */
    private fun normalizeRef(text: String): String {
        var base = text.lowercase()
            .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a')
            .replace('é', 'e').replace('ê', 'e')
            .replace('í', 'i')
            .replace('ó', 'o').replace('ô', 'o').replace('õ', 'o')
            .replace('ú', 'u')
            .replace('ç', 'c')
            .filter { it.isLetterOrDigit() }
        for ((re, abbrev) in BOOK_RULES) base = base.replace(re, abbrev)
        return base
    }

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
