package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SectionValidationError
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * F3.1 — apresentação amigável do editor. Puro/testável: nenhum enum interno
 * muda; só a linguagem mostrada ao usuário e a agregação de fontes.
 */

/** Nome amigável do papel (para cabeçalhos/menus). */
fun friendlyRoleName(role: SectionRole): String = when (role) {
    SectionRole.INTRO -> "Introdução"
    SectionRole.BODY -> "Tópico de desenvolvimento"
    SectionRole.CONCLUSION -> "Conclusão"
}

/** Rótulo curto do papel para o menu de "Adicionar parte". */
fun friendlyRoleMenuLabel(role: SectionRole): String = when (role) {
    SectionRole.INTRO -> "Introdução"
    SectionRole.BODY -> "Tópico de desenvolvimento"
    SectionRole.CONCLUSION -> "Conclusão"
}

/**
 * Fontes do tópico: referências da seção + de TODOS os sub-pontos, deduplicadas
 * e na ordem de aparição. Não cria infraestrutura nova — só agrega o que já
 * existe (`bibleRefs`/`publicationRefs`). Nenhuma referência é descartada.
 */
fun topicSources(section: SpeechSection, subPoints: List<SubPoint>): List<String> {
    val refs = LinkedHashSet<String>()
    section.bibleRefs.forEach { if (it.isNotBlank()) refs += it }
    section.publicationRefs.forEach { if (it.symbol.isNotBlank()) refs += it.symbol }
    subPoints.forEach { sp ->
        sp.bibleRefs.forEach { if (it.isNotBlank()) refs += it }
        sp.publicationRefs.forEach { if (it.symbol.isNotBlank()) refs += it.symbol }
    }
    return refs.toList()
}

/**
 * Mensagem específica do aviso estrutural quando a regra é determinável.
 * Null = sem erros (ou nenhum caso coberto). Nunca inventa diagnóstico:
 * regras não modeladas pelo validador (ex.: "falta conclusão") continuam
 * usando a mensagem genérica no caller.
 */
fun structureWarningMessage(errors: List<SectionValidationError>): String? {
    if (errors.isEmpty()) return null
    return when {
        SectionValidationError.NO_BODY in errors ->
            "Adicione pelo menos um tópico de desenvolvimento."
        SectionValidationError.MULTIPLE_INTRO in errors ->
            "Há mais de uma introdução — deixe apenas uma."
        SectionValidationError.MULTIPLE_CONCLUSION in errors ->
            "Há mais de uma conclusão — deixe apenas uma."
        SectionValidationError.INTRO_NOT_FIRST in errors ->
            "A introdução deve ser a primeira parte do discurso."
        SectionValidationError.CONCLUSION_NOT_LAST in errors ->
            "A conclusão deve ser a última parte do discurso."
        SectionValidationError.NON_POSITIVE_MINUTES in errors ->
            "Alguma parte está com 0 minuto — ajuste a duração."
        SectionValidationError.UNEXPECTED_INTRO in errors ->
            "Este tipo de discurso não usa introdução."
        SectionValidationError.UNEXPECTED_CONCLUSION in errors ->
            "Este tipo de discurso não usa conclusão."
        SectionValidationError.WRONG_BODY_COUNT in errors ->
            "Este tipo de discurso usa um único tópico."
        SectionValidationError.NO_SECTIONS in errors ->
            "Comece adicionando as partes do discurso."
        else -> null
    }
}
