package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SpeechSection

/**
 * v4.2: metadado de imagem que pode entrar no prompt.
 *
 * Só TEXTO (legenda/tipo): o app é DeepSeek texto-only; os bytes da imagem
 * nunca saem do aparelho nem entram no HTML/RAG.
 */
data class TribunaImageHint(
    val id: String,
    val caption: String,
    val kind: String,
)

/**
 * v4.2: prompt da Tribuna 2.0 — folha de cues de UMA seção.
 *
 * Uma chamada por seção mantém o trabalho pequeno (600–900 tokens de saída),
 * permite salvar cada seção e regenerar só a que ficou incompleta.
 * Puro/testável.
 */
internal fun buildTribunaSectionPrompt(
    section: SpeechSection,
    images: List<TribunaImageHint> = emptyList(),
    nextSectionTitle: String? = null,
    subPoints: List<String> = emptyList(),
): String {
    val plain = com.bettertalker.app.data.edit.stripHtmlToText(section.contentHtml).trim()
    val sb = StringBuilder()
    sb.append("Você vai criar o MAPA DE MEMÓRIA de UMA seção de um discurso (não um resumo).\n")
    sb.append("O orador usa as cues para falar de coração, sem ler o texto.\n\n")
    sb.append("Retorne APENAS UM objeto JSON válido, sem markdown, neste formato:\n")
    sb.append("{\n")
    sb.append("  \"sectionId\": \"").append(section.id).append("\",\n")
    sb.append("  \"mainIdea\": \"1 frase curta (máx 20 palavras) com a ideia central\",\n")
    sb.append("  \"bibleRef\": \"ref mais importante da seção (ou null)\",\n")
    sb.append("  \"anchorPhrase\": \"frase-chave para memorizar (máx 15 palavras)\",\n")
    sb.append("  \"transition\": \"como liga para a próxima seção (ou null)\",\n")
    sb.append("  \"timeMinutes\": ").append(section.minutes.coerceAtLeast(1)).append(",\n")
    sb.append("  \"keyTerms\": [\"3 a 6 termos-chave\"],\n")
    sb.append("  \"cues\": [\n")
    sb.append("    {\"kind\": \"HOOK\", \"text\": \"...\", \"refs\": []},\n")
    sb.append("    {\"kind\": \"REASONING\", \"text\": \"...\", \"refs\": []},\n")
    sb.append("    {\"kind\": \"SCRIPTURE\", \"text\": \"...\", \"refs\": [\"Sal 103:20\"]}\n")
    sb.append("  ]\n")
    sb.append("}\n\n")
    sb.append("Tipos de cue e quando usar:\n")
    sb.append("- HOOK: abertura que prende a atenção (1 por seção, na primeira).\n")
    sb.append("- REASONING: o argumento/linha de raciocínio, em 1–2 cues curtos.\n")
    sb.append("- SCRIPTURE: texto bíblico já citado na seção; refs obrigatórias.\n")
    sb.append("- ILLUSTRATION: exemplo/ilustração já presente ou implícita no texto.\n")
    sb.append("- APPLICATION: o que o ouvinte deve fazer.\n")
    sb.append("- IMAGE: só quando houver imagem anexada; use o imageId e a legenda reais.\n")
    sb.append("- TRANSITION: ponte para a próxima seção.\n")
    sb.append("- INVITATION, RECAP, APPEAL: só em conclusão (convite, recapitulação, apelo).\n\n")
    sb.append("Regras:\n")
    sb.append("- NÃO invente refs, fatos, números, citações nem legendas.\n")
    sb.append("- anchorPhrase deve vir do TEXTO da seção (não inventar outra ideia).\n")
    sb.append("- mainIdea: 1 frase; keyTerms: 3 a 6 palavras/frases curtas do texto.\n")
    sb.append("- timeMinutes deve ser a duração informada da seção.\n")
    sb.append("- Use 4 a 8 cues, na ordem em que o orador deve falar.\n")
    sb.append("- Se não houver imagem anexada, NÃO emita cue IMAGE.\n")
    sb.append("- Retorne APENAS JSON válido (um objeto, não array).\n\n")
    sb.append("--- SEÇÃO ---\n")
    sb.append("id: ").append(section.id).append("\n")
    sb.append("título: ").append(section.title).append("\n")
    sb.append("papel: ").append(section.role.name).append("\n")
    sb.append("duração: ").append(section.minutes).append(" min\n")
    section.objective?.takeIf { it.isNotBlank() }?.let {
        sb.append("objetivo: ").append(it.take(500)).append("\n")
    }
    section.agreedApproach?.takeIf { it.isNotBlank() }?.let {
        sb.append("abordagem acordada: ").append(it.take(500)).append("\n")
    }
    if (plain.isNotEmpty()) sb.append("Texto: ").append(plain.take(4000)).append("\n")
    val usefulSubPoints = subPoints.map { it.trim() }.filter { it.isNotEmpty() }
    if (usefulSubPoints.isNotEmpty()) {
        sb.append("Subpontos:\n")
        usefulSubPoints.forEachIndexed { index, line ->
            sb.append(index + 1).append(". ").append(line.take(400)).append("\n")
        }
    }
    if (section.bibleRefs.isNotEmpty()) {
        sb.append("Refs bíblicas: ").append(section.bibleRefs.joinToString(", ")).append("\n")
    }
    if (section.publicationRefs.isNotEmpty()) {
        val refs = section.publicationRefs.joinToString(", ") { ref ->
            buildString {
                append(ref.symbol)
                ref.page?.let { append(" p.").append(it) }
                ref.paragraph?.let { append(" §").append(it) }
            }
        }
        sb.append("Publicações: ").append(refs).append("\n")
    }
    sb.append("\n")
    if (images.isNotEmpty()) {
        sb.append("--- IMAGENS ANEXADAS (só legenda; os bytes não são enviados) ---\n")
        images.forEach { image ->
            sb.append("[").append(image.id).append("] ")
                .append(image.caption.ifBlank { "(sem legenda)" })
                .append(" (").append(image.kind).append(")\n")
        }
        sb.append("\n")
    }
    nextSectionTitle?.takeIf { it.isNotBlank() }?.let {
        sb.append("--- PRÓXIMA SEÇÃO ---\n").append(it).append("\n\n")
    }
    return sb.toString()
}
