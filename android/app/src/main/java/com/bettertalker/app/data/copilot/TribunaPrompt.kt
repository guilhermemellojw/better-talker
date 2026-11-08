package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.TribunaDetail
import com.bettertalker.app.domain.speech.rules

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
 * v4.2/v5.1: prompt da Tribuna 2.0 — folha de cues de UMA seção.
 *
 * Uma chamada por seção mantém o trabalho pequeno (orçamento varia com o
 * nível de detalhe), permite salvar cada seção e regenerar só a incompleta.
 * Puro/testável.
 */
internal fun buildTribunaSectionPrompt(
    section: SpeechSection,
    images: List<TribunaImageHint> = emptyList(),
    nextSectionTitle: String? = null,
    subPoints: List<String> = emptyList(),
    detail: TribunaDetail = TribunaDetail.STANDARD,
): String {
    val plain = com.bettertalker.app.data.edit.stripHtmlToText(section.contentHtml).trim()
    val rules = detail.rules()
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
    sb.append("  \"keyTerms\": [\"termos-chave do texto\"],\n")
    sb.append("  \"cues\": [\n")
    sb.append("    {\"kind\": \"HOOK\", \"text\": \"...\", \"refs\": []},\n")
    sb.append("    {\"kind\": \"REASONING\", \"text\": \"...\", \"refs\": []},\n")
    sb.append("    {\"kind\": \"SCRIPTURE\", \"text\": \"...\", \"refs\": [\"Sal 103:20\"]}\n")
    sb.append("  ]\n")
    sb.append("}\n\n")
    sb.append("NÍVEL DE DETALHE: ").append(detail.name).append("\n")
    sb.append("- Use entre ").append(rules.minCues).append(" e ")
        .append(rules.maxCues).append(" lembretes.\n")
    sb.append("- Cada texto de lembrete deve ter no máximo ")
        .append(rules.maxWords).append(" palavras.\n")
    sb.append("- Use entre ").append(rules.minKeyTerms).append(" e ")
        .append(rules.maxKeyTerms).append(" keyTerms.\n")
    sb.append("- Detalhe maior significa mais granularidade sobre o texto existente; ")
        .append("nunca preencha com conteúdo inventado.\n\n")
    sb.append("Tipos de lembrete e quando usar:\n")
    sb.append("- HOOK: abertura que prende a atenção (1 por seção, na primeira).\n")
    sb.append("- CONTEXT: situação, personagem ou pano de fundo já presente no texto.\n")
    sb.append("- QUESTION: pergunta retórica ou de transição presente na seção.\n")
    sb.append("- REASONING: o argumento/linha de raciocínio, em 1–2 lembretes curtos.\n")
    sb.append("- SCRIPTURE: referência/prova bíblica já citada; refs obrigatórias.\n")
    sb.append("- READING: trecho bíblico para ler em voz alta; só com ref bíblica.\n")
    sb.append("- ILLUSTRATION: exemplo/ilustração já presente no texto.\n")
    sb.append("- QUOTE: citação de publicação ou pessoa; só com ref existente.\n")
    sb.append("- APPLICATION: o que o ouvinte deve fazer.\n")
    sb.append("- IMAGE: só quando houver imagem anexada; use imageId e legenda reais.\n")
    sb.append("- DELIVERY: pausa, ênfase, gesto ou tom; direção de cena, não conteúdo falado.\n")
    sb.append("- TRANSITION: ponte para a próxima seção.\n")
    sb.append("- INVITATION, RECAP, APPEAL: só em conclusão (convite, recapitulação, apelo).\n\n")
    sb.append("Regras:\n")
    sb.append("- NÃO invente refs, fatos, números, citações, ilustrações nem pausas.\n")
    sb.append("- ENTREGA/DELIVERY só quando o texto já indicar uma deixa explícita; ")
        .append("caso contrário, deixe para o usuário adicionar manualmente.\n")
    sb.append("- CITAÇÃO/QUOTE só com publicação/ref existente.\n")
    sb.append("- LEITURA/READING só com ref bíblica.\n")
    sb.append("- anchorPhrase deve vir do TEXTO da seção (não inventar outra ideia).\n")
    sb.append("- mainIdea: 1 frase; timeMinutes deve ser a duração informada.\n")
    sb.append("- Se não houver imagem anexada, NÃO emita lembrete IMAGE.\n")
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
