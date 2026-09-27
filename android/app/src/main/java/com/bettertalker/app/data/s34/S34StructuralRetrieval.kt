package com.bettertalker.app.data.s34

import com.bettertalker.app.data.util.normalizeText

/**
 * Fase 19-B.4 — retrieval estrutural por `sectionId` + ponto atual.
 *
 * Domínio puro: recebe o S34Document já parseado (B.2) e devolve uma view
 * ESCOPADA. Regras inegociáveis:
 * - escopo é fronteira dura: outra seção do mesmo outline e qualquer outra
 *   fonte ficam fora — sem fallback global silencioso;
 * - ordem é estrutural (sourceLine do documento), NUNCA score;
 * - a query só marca `matched` (informativo), jamais reordena ou remove;
 * - referência permanece vinculada ao dono (seção ou subseção);
 * - sem LLM, sem rede, sem IO.
 *
 * Não conhece Room/Dexie: a fiação de persistência é do retriever fino.
 */
object S34StructuralRetrieval {

    enum class EntryKind { SECTION, SUBSECTION, REFERENCE }

    /** Entrada ordenada do ponto em foco. */
    data class ScopedEntry(
        val kind: EntryKind,
        /** sec-N | sec-N-M | "<outlineId>-ref-<n>" */
        val id: String,
        /** Dono estrutural: a própria seção (SECTION), a subseção (SUBSECTION)
         * ou quem contém a referência (REFERENCE). Nunca null. */
        val ownerId: String,
        val text: String,
        /** Linha 1-based no S-34 original — a ordem vem daqui. */
        val sourceLine: Int,
        val refType: S34RefType? = null,
        val matched: Boolean = false
    )

    /** Ponto em foco, com tudo o que lhe pertence, em ordem documental. */
    data class ScopedView(
        val outlineId: String,
        val sectionId: String,
        /** order explícito da seção (1-based). */
        val documentOrder: Int,
        val title: String,
        val minutes: Int?,
        val entries: List<ScopedEntry>
    ) {
        /** Só o que casou com a query — informativo, ordem preservada. */
        val matchedEntries: List<ScopedEntry> get() = entries.filter { it.matched }
    }

    /** Resumo ordenado para navegação de "ponto atual" (sem conteúdo). */
    data class SectionRef(val id: String, val order: Int, val title: String)

    sealed interface Resolution {
        data class Resolved(val outlineId: String, val sectionId: String, val title: String) : Resolution
        /** Sem dica: escopo é o documento inteiro (explícito, não fallback). */
        data object NoHint : Resolution
        /** Dica dada e não encontrada: NUNCA cai no documento inteiro. */
        data class Unmatched(val hint: String) : Resolution
    }

    /**
     * Resolve o ponto atual a partir de uma dica textual (ex.: título do
     * bloco ativo). Determinístico: igualdade normalizada primeiro; depois
     * contenção ÚNICA (ambígua => Unmatched, não escolhe no chute).
     */
    fun resolveSection(doc: S34Document, hint: String?): Resolution {
        val raw = hint?.trim().orEmpty()
        if (raw.isBlank()) return Resolution.NoHint
        val h = normalizeText(raw)
        if (h.isBlank()) return Resolution.NoHint
        doc.sections.firstOrNull { normalizeText(it.title) == h }?.let {
            return Resolution.Resolved(doc.id, it.id, it.title)
        }
        val contained = doc.sections.filter { s ->
            val t = normalizeText(s.title)
            t.isNotEmpty() && (h.contains(t) || t.contains(h))
        }
        return when (contained.size) {
            1 -> Resolution.Resolved(doc.id, contained[0].id, contained[0].title)
            else -> Resolution.Unmatched(raw)
        }
    }

    /** Lista ordenada de pontos (navegação), sempre em ordem documental. */
    fun sectionRefs(doc: S34Document): List<SectionRef> =
        doc.sections.sortedBy { it.order }.map { SectionRef(it.id, it.order, it.title) }

    /**
     * Escopa a view ao ponto. `null` = seção inexistente NESTE outline
     * (nunca resolve para outra seção ou outro documento).
     */
    fun scopeToSection(doc: S34Document, sectionId: String): ScopedView? {
        val section = doc.sections.firstOrNull { it.id == sectionId } ?: return null
        val entries = buildList {
            if (section.content.isNotBlank()) {
                add(ScopedEntry(
                    kind = EntryKind.SECTION,
                    id = section.id,
                    ownerId = section.id,
                    text = section.content,
                    sourceLine = section.sourceLine
                ))
            }
            section.references.forEach { r -> add(r.toEntry(section.id)) }
            section.subsections.sortedBy { it.order }.forEach { sub ->
                add(ScopedEntry(
                    kind = EntryKind.SUBSECTION,
                    id = sub.id,
                    ownerId = sub.id,
                    text = sub.content,
                    sourceLine = sub.sourceLine
                ))
                sub.references.forEach { r -> add(r.toEntry(sub.id)) }
            }
        }.sortedWith(compareBy({ it.sourceLine }, { it.id }))
        return ScopedView(
            outlineId = doc.id,
            sectionId = section.id,
            documentOrder = section.order,
            title = section.title,
            minutes = section.minutes,
            entries = entries
        )
    }

    /**
     * Marca o que casa com a query (tokens normalizados, interseção não
     * vazia). NÃO reordena, NÃO remove, NÃO promove — score não existe aqui.
     */
    fun markMatches(view: ScopedView, query: String): ScopedView {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return view
        return view.copy(entries = view.entries.map { e ->
            e.copy(matched = terms.any { t -> normalizeText(e.text).contains(t) })
        })
    }

    /** Vista do documento inteiro, em ordem — SEM conteúdo de outros S-34. */
    fun scopeToDocument(doc: S34Document): List<ScopedView> =
        doc.sections.sortedBy { it.order }.map { s -> scopeToSection(doc, s.id)!! }

    private fun S34Reference.toEntry(ownerId: String) = ScopedEntry(
        kind = EntryKind.REFERENCE,
        id = "$ownerId-ref-$order",
        ownerId = ownerId,
        text = rawText,
        sourceLine = sourceLine,
        refType = type
    )

    /** Tokens de conteúdo (sem stopwords curtas) para o casamento. */
    fun queryTerms(query: String): List<String> =
        normalizeText(query).split(" ").filter { it.length >= 3 }.distinct()
}
