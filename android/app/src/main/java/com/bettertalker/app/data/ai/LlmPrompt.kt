package com.bettertalker.app.data.ai

/**
 * Alimentação exclusiva (RAG estrito): a IA só recebe o que o pipeline
 * recuperou do banco local. Nada de memória do modelo.
 */
data class RagPassage(val index: Int, val text: String, val source: String)

/** Orientação estrutural (be/th): só a referência, nunca o texto. */
data class RagOrientation(val lessonRef: String, val kindLabel: String)

data class RagContext(
    val sectionTitle: String,
    val minutes: Int? = null,
    val passages: List<RagPassage>,
    val orientation: RagOrientation? = null,
    /** últimas trocas, já resumidas em 1 linha cada */
    val history: List<String> = emptyList(),
    /** "introdução" | "ilustração" | "conclusão" | "pergunta inicial" */
    val taskKind: String = "introdução"
)

/**
 * Monta o prompt RAG com a cláusula de bloqueio rígida no topo.
 * Puro/testável. O texto be/th NUNCA entra aqui (só a referência).
 */
fun buildRagPrompt(ctx: RagContext): String {
    val sb = StringBuilder()
    sb.append(
        "AVISO RÍGIDO: use SOMENTE os trechos numerados em REFERÊNCIAS INDEXADAS. " +
            "É PROIBIDO buscar na sua memória, completar versículos de cabeça ou inventar " +
            "citações, números de capítulo e lição. Se a informação não está abaixo, " +
            "diga que não foi encontrada. Responda em português.\n\n"
    )
    sb.append("REGRAS:\n")
    sb.append("1. Matéria: apenas os trechos numerados, citando [n] a cada uso.\n")
    sb.append("2. Estrutura: siga a orientação indicada; publicações-guia não são matéria.\n")
    sb.append("3. Curto e direto; sem floreio inventado.\n\n")
    sb.append("SEÇÃO: \"${ctx.sectionTitle}\"")
    ctx.minutes?.let { sb.append(" (${it} min)") }
    sb.append("\nTAREFA: desenvolver a ${ctx.taskKind} desta seção.\n\n")
    sb.append("REFERÊNCIAS INDEXADAS:\n")
    if (ctx.passages.isEmpty()) {
        sb.append("(nenhuma — diga que a matéria não foi encontrada)\n")
    } else {
        for (p in ctx.passages) {
            sb.append("[${p.index}] [${p.source}] ${p.text}\n")
        }
    }
    ctx.orientation?.let {
        sb.append("\nORIENTAÇÃO ESTRUTURAL (guia, não matéria): ${it.lessonRef} para ${it.kindLabel}.\n")
    }
    if (ctx.history.isNotEmpty()) {
        sb.append("\nINTERAÇÃO ANTERIOR:\n")
        ctx.history.takeLast(4).forEach { sb.append("- $it\n") }
    }
    return sb.toString().trim()
}
