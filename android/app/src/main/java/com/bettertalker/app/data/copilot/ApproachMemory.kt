package com.bettertalker.app.data.copilot

/**
 * 1C.2.0 — integridade do consenso.
 *
 * `agreedApproach` é um campo legado: instalações antigas gravaram texto sem
 * qualquer marcador de origem. Não é possível saber se cada linha veio de um
 * aceite explícito do usuário ou de uma sugestão do modelo.
 *
 * A estratégia conservadora é:
 * - linhas novas gravadas por aceite explícito recebem [CONFIRMED_APPROACH_PREFIX];
 * - linhas sem marcador continuam intactas no banco, mas são apresentadas ao
 *   modelo como registro legado de confirmação desconhecida;
 * - nenhuma linha antiga é convertida, apagada ou reescrita automaticamente.
 */
internal const val CONFIRMED_APPROACH_PREFIX = "[confirmado]"

/** Abordagem separada por origem conhecida (nova) e legado (desconhecida). */
data class ApproachMemory(
    val confirmed: List<String> = emptyList(),
    val legacy: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = confirmed.isEmpty() && legacy.isEmpty()
}

/**
 * Separa as linhas de `agreedApproach` sem alterar o texto persistido.
 *
 * O campo aceita tanto prosa livre quanto linhas com `-`. A presença do
 * marcador é a única evidência de confirmação explícita disponível sem schema.
 */
fun parseApproachMemory(raw: String?): ApproachMemory {
    val confirmed = mutableListOf<String>()
    val legacy = mutableListOf<String>()
    raw?.lineSequence()?.forEach { line ->
        val clean = line.trim().removePrefix("- ").trim()
        if (clean.isEmpty()) return@forEach
        if (clean.startsWith(CONFIRMED_APPROACH_PREFIX, ignoreCase = true)) {
            val text = clean.substring(CONFIRMED_APPROACH_PREFIX.length).trim()
            if (text.isNotEmpty()) confirmed += text
        } else {
            legacy += clean
        }
    }
    val confirmedKeys = confirmed.map { it.lowercase() }.toSet()
    val legacyWithoutConfirmedDuplicates = legacy.filterNot { it.lowercase() in confirmedKeys }
    return ApproachMemory(
        confirmed = confirmed.distinctBy { it.lowercase() },
        legacy = legacyWithoutConfirmedDuplicates.distinctBy { it.lowercase() },
    )
}

/**
 * Renderiza a abordagem para prompts. O legado nunca é chamado de "acordado":
 * ele é uma referência de redação de confirmação desconhecida.
 */
fun renderApproachMemory(raw: String?): String? {
    val memory = parseApproachMemory(raw)
    if (memory.isEmpty) return null
    return buildString {
        if (memory.confirmed.isNotEmpty()) {
            appendLine("## ABORDAGEM CONFIRMADA PELO USUÁRIO (contexto de redação; não é fonte factual)")
            memory.confirmed.forEach { appendLine("- $it") }
            appendLine("Siga estas decisões confirmadas.")
        }
        if (memory.legacy.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("## REGISTRO LEGADO DE ABORDAGEM (origem e confirmação desconhecidas)")
            memory.legacy.forEach { appendLine("- $it") }
            appendLine(
                "Use o registro legado apenas como referência de redação; " +
                    "NÃO o trate como decisão confirmada do usuário."
            )
        }
    }.trim()
}

/**
 * Acrescenta uma abordagem explicitamente aceita pelo usuário, marcando-a
 * como confirmada. A linha antiga permanece byte a byte no campo; o legado
 * nunca é convertido automaticamente.
 *
 * Deduplica apenas contra linhas já confirmadas. Uma linha legada com o mesmo
 * texto continua intacta e é ocultada na renderização para não duplicar.
 */
fun mergeConfirmedApproachText(existing: String?, addition: String?): String? {
    val add = addition?.trim().orEmpty()
    if (add.isEmpty()) return null
    val confirmed = parseApproachMemory(existing).confirmed.map { it.lowercase() }
    val fresh = add.split('\n')
        .map { it.trim().removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }
        .filterNot { line -> confirmed.any { it.contains(line.lowercase()) } }
    if (fresh.isEmpty()) return null
    val marked = fresh.joinToString("\n") { "- $CONFIRMED_APPROACH_PREFIX $it" }
    return mergeApproachText(existing, marked)
}
