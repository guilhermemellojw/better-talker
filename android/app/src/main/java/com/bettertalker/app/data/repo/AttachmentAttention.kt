package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import java.io.File
import java.util.Locale

/** Normaliza o nome do arquivo para comparar placeholders e imports. */
internal fun normalizedAttachmentName(raw: String?): String =
    raw.orEmpty().trim().lowercase(Locale.ROOT)

internal fun isAttentionStatus(status: String): Boolean =
    status.equals("missing", ignoreCase = true) || status.equals("failed", ignoreCase = true)

/**
 * P0: remove da lista "Precisa de atenção" os placeholders já resolvidos.
 *
 * Um `missing`/`failed` deixa de ser listado quando existe outro anexo
 * `ready` + `indexed` que representa a mesma publicação, por:
 * 1. mesmo sha256;
 * 2. mesma chave de publicação; ou
 * 3. mesmo nome de arquivo normalizado.
 *
 * Nada é apagado do banco: o placeholder continua disponível para o sync
 * e volta a aparecer se o anexo pronto for removido.
 */
internal fun attentionAttachments(items: List<AttachmentEntity>): List<AttachmentEntity> {
    val ready = items.filter { it.indexed && it.status.equals("ready", ignoreCase = true) }
    return items.filter { item ->
        isAttentionStatus(item.status) &&
            ready.none { candidate -> representsSamePublication(candidate, item) }
    }
}

private fun representsSamePublication(
    ready: AttachmentEntity,
    placeholder: AttachmentEntity,
): Boolean {
    val readyHash = ready.sha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    val placeholderHash = placeholder.sha256?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
    if (readyHash != null && readyHash == placeholderHash) return true

    val readyKey = strongPublicationKey(ready)
    if (readyKey != null &&
        readyKey == strongPublicationKey(placeholder) &&
        ready.kind.equals(placeholder.kind, ignoreCase = true)
    ) {
        return true
    }

    val readyName = normalizedAttachmentName(ready.fileName)
    return readyName.isNotEmpty() && readyName == normalizedAttachmentName(placeholder.fileName)
}

/**
 * Chaves de revista sem edição (`w`, `wp`, `g`, `gn`) são amplas demais para
 * provar que dois arquivos são a mesma publicação. Edições como `w17.10`
 * continuam válidas; para as bases, a decisão fica com sha256/nome.
 */
private fun strongPublicationKey(attachment: AttachmentEntity): String? =
    attachment.publicationKeyOrNull()?.takeIf { it !in BROAD_MAGAZINE_KEYS }

private val BROAD_MAGAZINE_KEYS = setOf("w", "wp", "g", "gn")

/**
 * P1: escolhe um placeholder `missing`/`failed` sem binário para preencher
 * em vez de criar outro anexo no import.
 *
 * Casa pelo nome normalizado ou, para o mesmo tipo, pela chave de publicação.
 * Linhas com arquivo válido ficam de fora: elas são reparadas pelo fluxo
 * explícito (ou pelo dedupe por sha256), não sobrescritas silenciosamente.
 */
internal fun chooseImportPlaceholder(
    items: List<AttachmentEntity>,
    displayName: String,
    kind: String,
): AttachmentEntity? {
    val name = normalizedAttachmentName(displayName)
    val publication = filePublicationKeyOrNull(displayName)
    return items
        .asSequence()
        .filter { isAttentionStatus(it.status) }
        .filter { it.appPath.isBlank() || !File(it.appPath).isFile }
        .sortedWith(
            compareByDescending<AttachmentEntity> { it.status.equals("missing", ignoreCase = true) }
                .thenByDescending { it.addedAt },
        )
        .firstOrNull { candidate ->
            val sameName = name.isNotEmpty() &&
                normalizedAttachmentName(candidate.fileName) == name
            val samePublication = publication != null &&
                candidate.kind.equals(kind, ignoreCase = true) &&
                candidate.publicationKeyOrNull() == publication
            sameName || samePublication
        }
}
