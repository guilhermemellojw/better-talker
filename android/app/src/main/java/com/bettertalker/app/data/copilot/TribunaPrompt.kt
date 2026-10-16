package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SpeechSection

/**
 * #20-fix-a: prompt da Tribuna — MAPA DE MEMÓRIA por seção (não é resumo).
 * A IA gera; o usuário edita. Puro/testável.
 */
internal fun buildTribunaPrompt(sections: List<SpeechSection>): String {
    val sb = StringBuilder()
    sb.append("Você vai criar um MAPA DE MEMÓRIA para um orador (não um resumo).\n\n")
    sb.append("Para CADA seção do discurso abaixo, retorne um objeto JSON:\n")
    sb.append("{\n")
    sb.append("  \"sectionId\": \"id\",\n")
    sb.append("  \"mainIdea\": \"1 frase curta (máx 20 palavras) com a ideia central\",\n")
    sb.append("  \"bibleRef\": \"ref mais importante da seção (ou null)\",\n")
    sb.append("  \"anchorPhrase\": \"frase-chave para memorizar (máx 15 palavras)\",\n")
    sb.append("  \"transition\": \"como liga para a próxima seção (máx 15 palavras)\"\n")
    sb.append("}\n\n")
    sb.append("Regras:\n")
    sb.append("- NÃO invente refs nem fatos\n")
    sb.append("- anchorPhrase deve vir do TEXTO da seção (não inventar)\n")
    sb.append("- transition é uma ponte, não uma nova ideia\n")
    sb.append("- Se a seção está vazia, retorne null para ela\n")
    sb.append("- Retorne APENAS JSON válido (array)\n\n")
    sb.append("--- SEÇÕES ---\n")
    sections.forEach { s ->
        val plain = com.bettertalker.app.data.edit.stripHtmlToText(s.contentHtml).trim()
        sb.append("[${s.id}] ${s.title} (${s.minutes} min)\n")
        if (plain.isNotEmpty()) sb.append("Texto: ${plain.take(2000)}\n")
        if (s.bibleRefs.isNotEmpty()) sb.append("Refs: ${s.bibleRefs.joinToString(", ")}\n")
        sb.append("\n")
    }
    return sb.toString()
}
