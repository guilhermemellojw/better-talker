package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.RefDetector

/**
 * 6.0.5 — pergunta sobre uma fonte específica.
 *
 * O chat não pode responder sobre o ponto/S-34 quando o usuário pede uma
 * publicação/Bíblia específica. Este bloco entra ANTES da pergunta no user
 * prompt e a interpretação (`jr 29` = livro, p. 29; `Je 29` = Bíblia) evita
 * a confusão entre os dois espaços de referência.
 *
 * Puro/testável: sem LLM, rede, banco ou Compose.
 */
const val SOURCE_QUESTION_RULES = """
MODO PERGUNTA SOBRE FONTE (prioridade máxima):
- A pergunta cita uma referência específica. Responda a partir do bloco "PERGUNTA SOBRE FONTE".
- Não responda sobre o ponto atual, o S-34, o dossiê ou a nota como se fossem a fonte pedida. Eles não estão disponíveis neste modo.
- Respeite a interpretação fornecida: "jr 29" é o livro Jeremias — Uma Mensagem de Deus para Nós, página 29; "Je 29" é o capítulo 29 de Jeremias na Bíblia.
- Se o texto da fonte não estiver no bloco, diga honestamente que não encontrou no acervo e ofereça Abrir Biblioteca. Nunca invente.
- Cite a referência e use o texto literal; paráfrases devem ser marcadas como suas.
"""

/** Evidência literal de uma pergunta sobre fonte. */
data class SourceQuestionEvidence(
    val reference: String,
    val text: String,
    val label: String? = null,
)

/** Teto total do bloco de fonte no prompt; o reader continua com tudo. */
const val MAX_SOURCE_QUESTION_CHARS = 6000

/**
 * Bloco "PERGUNTA SOBRE FONTE". Puro/testável.
 * A interpretação vai ANTES do texto para o modelo não confundir os dois
 * espaços de referência.
 */
fun buildSourceQuestionBlock(
    question: String,
    interpretations: List<String>,
    evidence: List<SourceQuestionEvidence>,
    maxChars: Int = MAX_SOURCE_QUESTION_CHARS,
): String {
    val sb = StringBuilder()
    sb.append("## PERGUNTA SOBRE FONTE (prioridade máxima)\n")
    sb.append("Pergunta: ").append(question.trim()).append("\n")
    val interp = interpretations.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (interp.isNotEmpty()) {
        sb.append("Interpretação da referência:\n")
        interp.forEach { sb.append("- ").append(it).append("\n") }
    }
    sb.append("\nTexto da fonte (use este texto; não responda sobre o ponto atual):\n")
    var used = 0
    var truncated = false
    val lines = mutableListOf<String>()
    for (e in evidence) {
        val text = e.text.trim()
        if (text.isBlank()) continue
        val prefix = "- ${e.reference}: "
        val available = maxChars - used - prefix.length - 2
        if (available <= 80) {
            truncated = true
            break
        }
        val slice = if (text.length > available) {
            truncated = true
            text.take(available).trimEnd() + "…"
        } else {
            text
        }
        val line = prefix + "\"" + slice + "\""
        lines += line
        used += line.length + 1
    }
    if (lines.isEmpty()) {
        sb.append("(Não encontrei o texto desta fonte no acervo local.)\n")
        sb.append("Diga isso com honestidade, não invente e ofereça Abrir Biblioteca.\n")
    } else {
        lines.forEach { sb.append(it).append("\n") }
    }
    if (truncated) {
        sb.append("Atenção: o texto acima foi truncado no prompt. O leitor mostra o texto completo; não afirme o que não viu.\n")
    }
    sb.append("\nRegras: responda a partir do texto da fonte; não use o ponto/S-34 como substituto. Cite a referência. Se a fonte não estiver no bloco, diga que não encontrou.\n")
    return sb.toString().trim()
}

/**
 * 6.0.5 — a mensagem pede o TEXTO de uma fonte já detectada?
 *
 * Diferencia consulta de fonte de geração/refinamento: "O que diz w17.10?"
 * entra no modo fonte; "Use João 3:16 no ponto 2" e "desenvolva o ponto 2"
 * continuam no fluxo normal (S-34/dossiê). Puro/testável.
 */
fun isSourceQuestionMessage(text: String, hasDetectedRef: Boolean): Boolean {
    if (!hasDetectedRef) return false
    if (ChatRouter.isLongTextGenerationRequest(text)) return false
    if (OratoryGeneration.detectMode(text) != null) return false
    if (OratorySession.isRefinement(text)) return false
    // Instrução de usar a ref num ponto é geração, não consulta da fonte.
    if (SOURCE_USE_INSTRUCTION.containsMatchIn(text)) return false
    return true
}

/** Verbos de “usar a ref no texto” — pedido de geração, não de leitura. */
private val SOURCE_USE_INSTRUCTION = Regex(
    """(?i)\b(use|usar|usando|utilize|utilizar|aplique|aplicar|inclua|incluir|""" +
        """insira|inserir|coloque|colocar)\b"""
)

/**
 * P1 (6.0.5) — no modo fonte, uma resposta não pode introduzir outra
 * publicação (ex.: citou `w24.08` quando a pergunta era `w17.10`).
 * Devolve os rótulos extras, sem duplicar. Puro/testável.
 */
fun sourceQuestionDriftRefs(
    answer: String,
    allowedEditionKeys: Set<String>,
): List<String> = RefDetector.detect(answer)
    .filter { it.editionKey !in allowedEditionKeys }
    .map { it.raw }
    .distinct()
