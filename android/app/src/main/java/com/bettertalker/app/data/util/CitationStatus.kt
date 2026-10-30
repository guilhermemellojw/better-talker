package com.bettertalker.app.data.util

/**
 * Status único de uma fonte citada — o mesmo para a UI (checklist/sheet) e
 * para o prompt do Copilot.
 *
 * A separação é deliberada: "edição" responde se o arquivo está no acervo;
 * "texto" responde se o trecho citado foi encontrado. Antes os dois viravam
 * um só [SourceStatus], e o prompt acabava dizendo que a publicação inteira
 * não existia quando só o trecho faltava.
 */
enum class EditionStatus {
    MISSING,
    DOWNLOADING,
    INDEXING,
    INDEX_FAILED,
    UNSUPPORTED,
    READY,
}

/** Estado do TEXTO citado dentro da edição (ou da TNM). */
enum class TextStatus {
    RESOLVED,
    PARTIAL,
    NOT_FOUND,
    AMBIGUOUS,
}

/** Próxima ação do usuário para destravar a fonte. */
enum class SourceAction {
    DOWNLOAD,
    REINDEX,
    CONFIRM,
    OPEN,
    WAIT,
    NONE,
}

/**
 * Estado combinado. Puro/testável: nenhuma ação de rede/DB.
 *
 * Regra de precedência: edição transitória/falha manda; ambiguidade de
 * referência vira CONFIRM (nunca escolhe uma edição sozinha); texto ausente
 * com edição pronta vira REINDEX (o índice pode estar incompleto).
 */
data class CitationStatus(
    val edition: EditionStatus,
    val text: TextStatus,
) {
    val action: SourceAction
        get() = when {
            edition == EditionStatus.DOWNLOADING ||
                edition == EditionStatus.INDEXING -> SourceAction.WAIT
            edition == EditionStatus.MISSING && text == TextStatus.AMBIGUOUS ->
                SourceAction.CONFIRM
            edition == EditionStatus.MISSING -> SourceAction.DOWNLOAD
            edition == EditionStatus.INDEX_FAILED -> SourceAction.REINDEX
            edition == EditionStatus.UNSUPPORTED -> SourceAction.OPEN
            text == TextStatus.AMBIGUOUS -> SourceAction.CONFIRM
            text == TextStatus.NOT_FOUND -> SourceAction.REINDEX
            text == TextStatus.PARTIAL -> SourceAction.OPEN
            else -> SourceAction.NONE
        }

    /** Compatibilidade com o checklist legado (nunca use para decidir ação). */
    val legacyStatus: SourceStatus
        get() = when (edition) {
            EditionStatus.DOWNLOADING -> SourceStatus.DOWNLOADING
            EditionStatus.INDEXING -> SourceStatus.INDEXING
            EditionStatus.INDEX_FAILED -> SourceStatus.INDEX_FAILED
            EditionStatus.UNSUPPORTED -> SourceStatus.UNSUPPORTED
            EditionStatus.MISSING -> SourceStatus.EDITION_MISSING
            EditionStatus.READY -> when (text) {
                TextStatus.NOT_FOUND -> SourceStatus.TEXT_NOT_FOUND
                TextStatus.AMBIGUOUS -> SourceStatus.EDITION_MISSING
                TextStatus.RESOLVED, TextStatus.PARTIAL -> SourceStatus.RESOLVED
            }
        }

    /** Edição pronta e texto utilizável (exato ou parcial). */
    val isReady: Boolean
        get() = edition == EditionStatus.READY &&
            (text == TextStatus.RESOLVED || text == TextStatus.PARTIAL)

    val isPending: Boolean
        get() = edition == EditionStatus.DOWNLOADING || edition == EditionStatus.INDEXING

    /** A resposta ainda não pode usar esta fonte sem ação/decisão do usuário. */
    val blocksAnswer: Boolean
        get() = !isReady

    val needsAction: Boolean
        get() = action != SourceAction.NONE
}

/** Compatibilidade: [NoteSource] continua expondo o status legado. */
fun defaultTextStatus(status: SourceStatus): TextStatus = when (status) {
    SourceStatus.RESOLVED -> TextStatus.RESOLVED
    SourceStatus.TEXT_NOT_FOUND -> TextStatus.NOT_FOUND
    else -> TextStatus.NOT_FOUND
}

/** Compatibilidade: status legado → estado de edição. */
fun editionStatusOf(status: SourceStatus): EditionStatus = when (status) {
    SourceStatus.EDITION_MISSING -> EditionStatus.MISSING
    SourceStatus.DOWNLOADING -> EditionStatus.DOWNLOADING
    SourceStatus.INDEXING -> EditionStatus.INDEXING
    SourceStatus.INDEX_FAILED -> EditionStatus.INDEX_FAILED
    SourceStatus.UNSUPPORTED -> EditionStatus.UNSUPPORTED
    SourceStatus.RESOLVED, SourceStatus.TEXT_NOT_FOUND -> EditionStatus.READY
}

fun editionStatusLabel(status: EditionStatus): String = when (status) {
    EditionStatus.MISSING -> "edição ausente"
    EditionStatus.DOWNLOADING -> "edição baixando"
    EditionStatus.INDEXING -> "edição indexando"
    EditionStatus.INDEX_FAILED -> "indexação falhou"
    EditionStatus.UNSUPPORTED -> "formato não suportado"
    EditionStatus.READY -> "edição no acervo"
}

fun textStatusLabel(status: TextStatus): String = when (status) {
    TextStatus.RESOLVED -> "trecho resolvido"
    TextStatus.PARTIAL -> "trecho parcial"
    TextStatus.NOT_FOUND -> "trecho não encontrado"
    TextStatus.AMBIGUOUS -> "referência ambígua"
}

fun actionLabel(action: SourceAction): String = when (action) {
    SourceAction.DOWNLOAD -> "baixar edição"
    SourceAction.REINDEX -> "reindexar"
    SourceAction.CONFIRM -> "confirmar edição"
    SourceAction.OPEN -> "abrir edição"
    SourceAction.WAIT -> "aguardar"
    SourceAction.NONE -> "nenhuma"
}

/**
 * Motivo humano, distinto para Bíblia e publicação. É o texto que entra no
 * prompt e no checklist — nunca "não existe".
 */
fun citationReason(source: NoteSource): String {
    val c = source.citation
    val bible = source.kind == SourceKind.BIBLE
    val pdf = source.fileName?.endsWith(".pdf", ignoreCase = true) == true
    return when {
        c.text == TextStatus.AMBIGUOUS ->
            source.hint?.takeIf { it.isNotBlank() }
                ?: "referência ambígua; confirme a edição (mês/ano ou número)"
        c.edition == EditionStatus.DOWNLOADING ->
            if (bible) "TNM baixando" else "edição baixando — acompanhe na Biblioteca"
        c.edition == EditionStatus.INDEXING ->
            if (bible) "TNM indexando" else "edição indexando — o Copilot usa quando terminar"
        c.edition == EditionStatus.INDEX_FAILED ->
            if (bible) "TNM importada; indexação falhou"
            else "edição importada; indexação falhou"
        c.edition == EditionStatus.UNSUPPORTED ->
            if (bible) "TNM em formato não suportado" else "edição em formato não suportado"
        c.edition == EditionStatus.MISSING ->
            if (bible) "TNM ausente" else "edição ausente do acervo"
        c.text == TextStatus.NOT_FOUND ->
            if (bible) "TNM presente; versículo não encontrado"
            else "edição no acervo; trecho não encontrado"
        c.text == TextStatus.PARTIAL ->
            when {
                pdf -> "PDF no acervo; trecho localizado pelo contexto (confira)"
                !source.hint.isNullOrBlank() -> source.hint
                else -> "edição no acervo; trecho parcial (confira a unidade)"
            }
        pdf -> "PDF no acervo (página/§ não indexados)"
        else ->
            if (bible) "TNM resolvida" else "edição no acervo; trecho resolvido"
    }
}

/** Linha compacta motivo + ação usada no inventário do prompt. */
fun citationDetailLine(source: NoteSource): String {
    val action = source.citation.action
    val suffix = if (action == SourceAction.NONE) "" else " — ação: ${actionLabel(action)}"
    return citationReason(source) + suffix
}

/** Rótulo curto para a linha do checklist. */
fun citationStatusLabel(source: NoteSource): String = when (source.citation.action) {
    SourceAction.NONE -> "No acervo"
    SourceAction.DOWNLOAD -> "Edição não importada"
    SourceAction.REINDEX ->
        if (source.citation.text == TextStatus.NOT_FOUND) "Texto não encontrado"
        else "Indexação falhou"
    SourceAction.CONFIRM -> "Confirmar edição"
    SourceAction.OPEN ->
        if (source.citation.edition == EditionStatus.UNSUPPORTED) "Formato não suportado"
        else "Abrir edição"
    SourceAction.WAIT ->
        if (source.citation.edition == EditionStatus.INDEXING) "Indexando…" else "Baixando…"
}

/** Chave estável para o gate (independe do texto do usuário). */
fun NoteSource.gateKey(): String = "$kind|$ref|${editionKey.orEmpty()}"
