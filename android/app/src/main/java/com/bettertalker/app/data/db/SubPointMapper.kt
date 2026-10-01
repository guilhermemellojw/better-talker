package com.bettertalker.app.data.db

import com.bettertalker.app.data.repo.decodeStringList
import com.bettertalker.app.data.repo.encodeStringList
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Mapeamento entidade Room <-> domínio para sub-pontos.
 * Reusa os codecs de listas do projeto (sem duplicação).
 */
fun SubPointEntity.toDomain(): SubPoint {
    return SubPoint(
        id = id,
        sectionId = sectionId,
        order = order,
        outlineText = outlineText,
        bibleRefs = decodeStringList(bibleRefsJson),
        publicationRefs = decodePublicationRefList(publicationRefsJson),
        instruction = instruction,
        developedHtml = developedHtml,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun SubPoint.toEntity(): SubPointEntity {
    return SubPointEntity(
        id = id,
        sectionId = sectionId,
        order = order,
        outlineText = outlineText,
        bibleRefsJson = encodeStringList(bibleRefs),
        publicationRefsJson = encodePublicationRefList(publicationRefs),
        instruction = instruction,
        developedHtml = developedHtml,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
