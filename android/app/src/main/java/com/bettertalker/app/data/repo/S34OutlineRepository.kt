package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34Reference
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.data.s34.S34Subsection
import com.bettertalker.app.data.util.RefDetector

/**
 * Fase 19-B.3 — CRUD estrutural do OutlineDocument (§19).
 *
 * Contrato: save / get / getBySourceDocument / deleteBySourceDocument.
 * Recebe o documento JÁ parseado (S34Parser é a autoridade de estrutura);
 * aqui só mapeamento + idempotência + cascade. Sem retrieval, sem prompt.
 *
 * Depende só de S34Dao (não do AppDatabase inteiro): testável com fake.
 *
 * F19-B.4: as chaves de seção/subseção do parser ("sec-N", "sec-N-M") são
 * determinísticas POR DOCUMENTO — dois S-34 diferentes colidiriam na chave
 * primária. O armazenamento prefixa com o id do outline
 * ("<outlineId>:sec-N") e o `rebuild` devolve o id de domínio intacto,
 * preservando o contrato da B.2.
 */
class S34OutlineRepository(private val dao: com.bettertalker.app.data.db.S34Dao) {

    companion object {
        /** Versão do formato persistido (bump se o mapeamento mudar). */
        const val PARSER_VERSION = 2

        /** Chave de armazenamento: única entre documentos. */
        fun storageKey(outlineId: String, localId: String): String = "$outlineId:$localId"

        /** Id de domínio a partir da chave de armazenamento. */
        fun localId(storageId: String, outlineId: String): String =
            storageId.removePrefix("$outlineId:")
    }

    /**
     * Idempotente: mesma source + mesmo id => replace; mesma source + id
     * novo (conteúdo mudou) => substitui tudo (sem restos da versão velha).
     */
    suspend fun save(doc: S34Document, sourceAttachmentId: String) {
        val now = System.currentTimeMillis()
        val prev = dao.outlineBySource(sourceAttachmentId)
        // Remove versão anterior (qualquer id) antes de inserir a nova.
        if (prev != null) deleteRows(dao, prev.id)
        dao.putOutline(
            S34OutlineEntity(
                id = doc.id,
                sourceAttachmentId = sourceAttachmentId,
                symbol = doc.symbol,
                title = doc.title,
                objective = doc.objective,
                headerLinesJson = encodeStringList(doc.headerLines),
                createdAt = prev?.takeIf { it.id == doc.id }?.createdAt ?: now,
                updatedAt = now,
                parserVersion = PARSER_VERSION
            )
        )
        dao.putSections(doc.sections.map { s ->
            S34SectionEntity(
                storageKey(doc.id, s.id), doc.id, s.order, s.title, s.content, s.minutes, s.sourceLine
            )
        })
        dao.putSubsections(doc.sections.flatMap { s ->
            s.subsections.map { sub ->
                S34SubsectionEntity(
                    storageKey(doc.id, sub.id), storageKey(doc.id, s.id),
                    sub.order, sub.content, sub.sourceLine
                )
            }
        })
        dao.putReferences(doc.sections.flatMap { s ->
            s.references.map { r -> toEntity(doc.id, storageKey(doc.id, s.id), null, r) } +
                s.subsections.flatMap { sub ->
                    sub.references.map { r ->
                        toEntity(
                            doc.id,
                            storageKey(doc.id, s.id),
                            storageKey(doc.id, sub.id),
                            r
                        )
                    }
                }
        })
    }

    suspend fun get(outlineId: String): S34Document? {
        val o = dao.outlineById(outlineId) ?: return null
        return rebuild(o.id, o.symbol, o.title, o.objective, o.headerLinesJson)
    }

    suspend fun getBySource(sourceAttachmentId: String): S34Document? {
        val o = dao.outlineBySource(sourceAttachmentId) ?: return null
        return rebuild(o.id, o.symbol, o.title, o.objective, o.headerLinesJson)
    }

    /** Cascade total: outline + seções + subseções + referências. Sem lixo. */
    suspend fun deleteBySource(sourceAttachmentId: String) {
        val o = dao.outlineBySource(sourceAttachmentId) ?: return
        deleteRows(dao, o.id)
    }

    private suspend fun deleteRows(
        dao: com.bettertalker.app.data.db.S34Dao,
        outlineId: String
    ) {
        val sectionIds = dao.sectionsOf(outlineId).map { it.id }
        dao.deleteRefsOf(outlineId)
        if (sectionIds.isNotEmpty()) dao.deleteSubsOf(sectionIds)
        dao.deleteSectionsOf(outlineId)
        dao.deleteOutline(outlineId)
    }

    /** Reconstrução em 4 queries (outline + seções + subs + refs; sem N+1). */
    private suspend fun rebuild(
        outlineId: String,
        symbol: String,
        title: String,
        objective: String?,
        headerLinesJson: String
    ): S34Document {
        val sections = dao.sectionsOf(outlineId)
        val subsBySection = dao.subsectionsOf(sections.map { it.id }).groupBy { it.sectionId }
        val refsBySection = dao.referencesOf(outlineId).groupBy { it.sectionId }
        val refsBySub = dao.referencesOf(outlineId)
            .filter { it.subsectionId != null }
            .groupBy { it.subsectionId!! }
        // headerLines não persiste com provenance (texto não-estruturado por
        // definição); round-trip estrutural não depende dele.
        return S34Document(
            id = outlineId,
            symbol = symbol,
            title = title,
            objective = objective,
            headerLines = decodeStringList(headerLinesJson),
            sections = sections.map { s ->
                S34Section(
                    id = localId(s.id, outlineId),
                    order = s.order,
                    title = s.title,
                    minutes = s.minutes,
                    content = s.content,
                    subsections = (subsBySection[s.id] ?: emptyList()).map { sub ->
                        S34Subsection(
                            id = localId(sub.id, outlineId),
                            order = sub.order,
                            content = sub.content,
                            references = (refsBySub[sub.id] ?: emptyList()).map(::toDomain),
                            sourceLine = sub.sourceLine
                        )
                    },
                    references = (refsBySection[s.id] ?: emptyList())
                        .filter { it.subsectionId == null }
                        .map(::toDomain),
                    sourceLine = s.sourceLine
                )
            }
        )
    }

    private fun toEntity(
        outlineId: String,
        sectionId: String,
        subsectionId: String?,
        r: S34Reference
    ): S34ReferenceEntity {
        return S34ReferenceEntity(
            id = "$outlineId-ref-${r.order}",
            outlineId = outlineId,
            sectionId = sectionId,
            subsectionId = subsectionId,
            order = r.order,
            type = r.type.name,
            rawText = r.rawText,
            normalizedReference = r.normalizedReference,
            sourceLine = r.sourceLine,
            book = r.bible?.label,
            bookNorm = r.bible?.bookNorm,
            chapter = r.bible?.chapter,
            verse = r.bible?.verse,
            pubKind = r.publication?.kind?.name,
            pubKey = r.publication?.pubKey,
            pubLabel = r.publication?.label,
            editionKey = r.publication?.editionKey
        )
    }

    private fun toDomain(e: S34ReferenceEntity): S34Reference {        val bible = if (e.book != null && e.chapter != null && e.verse != null) {
            RefDetector.BibleRef(
                bookNorm = e.bookNorm ?: "",
                label = e.book,
                chapter = e.chapter,
                verse = e.verse
            )
        } else null
        val pub = if (e.pubKey != null) {
            RefDetector.DetectedRef(
                raw = e.rawText,
                kind = runCatching { RefDetector.Kind.valueOf(e.pubKind ?: "BOOK") }
                    .getOrDefault(RefDetector.Kind.BOOK),
                pubKey = e.pubKey,
                editionKey = e.editionKey ?: "",
                label = e.pubLabel ?: e.pubKey
            )
        } else null
        return S34Reference(
            type = S34RefType.valueOf(e.type),
            rawText = e.rawText,
            normalizedReference = e.normalizedReference,
            order = e.order,
            sourceLine = e.sourceLine,
            bible = bible,
            publication = pub
        )
    }
}

/**
 * Codec mínimo de lista de strings (headerLines). JSON array com escape de
 * `\` e `"`; sem dependências. Puro/testável.
 */
fun encodeStringList(items: List<String>): String {
    val sb = StringBuilder("[")
    items.forEachIndexed { i, s ->
        if (i > 0) sb.append(",")
        sb.append("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        sb.append("\"")
    }
    return sb.append("]").toString()
}

fun decodeStringList(json: String): List<String> {
    val t = json.trim()
    if (!t.startsWith("[") || !t.endsWith("]")) return emptyList()
    val out = mutableListOf<String>()
    var i = 1
    val end = t.length - 1
    while (i < end) {
        while (i < end && (t[i] == ',' || t[i].isWhitespace())) i++
        if (i >= end) break
        if (t[i] != '"') return emptyList()
        i++
        val sb = StringBuilder()
        var closed = false
        while (i < end) {
            val c = t[i]
            if (c == '\\' && i + 1 < end) {
                when (t[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(t[i + 1])
                }
                i += 2
            } else if (c == '"') {
                closed = true
                i++
                break
            } else {
                sb.append(c)
                i++
            }
        }
        if (!closed) return emptyList()
        out += sb.toString()
    }
    return out
}
