package com.bettertalker.app.data.repo

import com.bettertalker.app.domain.planning.ReferenceStatus

/**
 * Fase 1 — regras puras das citações que entram no prompt.
 *
 * `citedSources` não pode mais ignorar uma ref em silêncio: quando a edição
 * existe mas o trecho não, o modelo precisa saber a diferença entre
 * "edição ausente" e "edição no acervo; trecho não encontrado". E PARTIAL é
 * fonte utilizável (mesmo critério do RoomReferenceResolver).
 */

/** RESOLVED e PARTIAL são fontes utilizáveis; o resto vira referência sem texto. */
internal fun isCitedSourceUsable(status: ReferenceStatus): Boolean =
    status == ReferenceStatus.RESOLVED || status == ReferenceStatus.PARTIAL

/**
 * Linha de motivo para o bloco "REFERÊNCIAS ... SEM TEXTO".
 * Nunca diz que a publicação inteira falta quando só o trecho faltou.
 */
internal fun citedUnavailableDetail(
    reference: String,
    status: ReferenceStatus,
    editionPresent: Boolean,
    bible: Boolean = false,
    tnmPresent: Boolean = editionPresent,
): String {
    val reason = when {
        bible && !tnmPresent -> "TNM ausente"
        bible && status == ReferenceStatus.MISSING_CORPUS ->
            "TNM ausente ou referência não reconhecida"
        bible -> "TNM presente; versículo não encontrado"
        !editionPresent -> "edição ausente do acervo"
        status == ReferenceStatus.UNRESOLVED ->
            "edição no acervo; trecho não encontrado"
        status == ReferenceStatus.MISSING_CORPUS ->
            "edição ausente do acervo"
        else -> "texto não encontrado no acervo"
    }
    return "$reference — $reason"
}
