package com.bettertalker.app.data.copilot

/**
 * 1C.2.0 — integridade do consenso.
 *
 * `agreedApproach` é um campo legado: instalações antigas gravaram texto sem
 * qualquer marcador de origem. Não é possível saber se cada linha veio de um
 * aceite explícito do usuário ou de uma sugestão do modelo.
 *
 * A estratégia conservadora é:
 * - linhas novas gravadas por aceite explícito recebem prefixo E sufixo de
 *   confirmação completos;
 * - linhas sem o par completo continuam intactas no banco, mas são
 *   apresentadas ao modelo como registro legado de confirmação desconhecida;
 * - nenhuma linha antiga é convertida, apagada ou reescrita automaticamente.
 */
internal const val CONFIRMED_APPROACH_PREFIX = "[confirmado]"
internal const val CONFIRMED_APPROACH_SUFFIX = "[/confirmado]"

/** Resultado explícito de uma tentativa de memorizar uma abordagem. */
sealed interface ConfirmedApproachWrite {
    /** Texto novo completo que deve ser persistido. */
    data class Stored(val text: String) : ConfirmedApproachWrite

    /** A confirmação já estava gravada; nada muda. */
    data object AlreadyStored : ConfirmedApproachWrite

    /** Não há espaço para a confirmação completa; nada é gravado. */
    data object NoSpace : ConfirmedApproachWrite

    /** Não havia texto para memorizar. */
    data object Empty : ConfirmedApproachWrite
}

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
        val startsConfirmed = clean.startsWith(CONFIRMED_APPROACH_PREFIX, ignoreCase = true)
        val endsConfirmed = clean.endsWith(CONFIRMED_APPROACH_SUFFIX, ignoreCase = true)
        if (startsConfirmed && endsConfirmed) {
            val text = clean
                .substring(CONFIRMED_APPROACH_PREFIX.length, clean.length - CONFIRMED_APPROACH_SUFFIX.length)
                .trim()
            if (text.isNotEmpty()) confirmed += text else legacy += clean
        } else {
            // Prefixo sem fechamento = linha incompleta/truncada. Nunca é
            // promovida a confirmação; permanece como legado íntegro.
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
 * Tenta acrescentar uma abordagem explicitamente aceita pelo usuário.
 *
 * All-or-nothing: todas as linhas da confirmação precisam caber no teto de
 * [MAX_APPROACH_CHARS]. Se não couberem, o campo existente é preservado byte a
 * byte e o retorno é [ConfirmedApproachWrite.NoSpace] — nunca uma linha
 * truncada, nunca uma confirmação parcial.
 *
 * O legado nunca é convertido automaticamente. Uma linha legada com o mesmo
 * texto continua intacta e é ocultada na renderização para não duplicar.
 */
fun mergeConfirmedApproachText(existing: String?, addition: String?): ConfirmedApproachWrite {
    val add = addition?.trim().orEmpty()
    if (add.isEmpty()) return ConfirmedApproachWrite.Empty
    val confirmed = parseApproachMemory(existing).confirmed.map { it.lowercase() }
    val fresh = add.split('\n')
        .map { it.trim().removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }
        .filterNot { line -> confirmed.any { it == line.lowercase() } }
    if (fresh.isEmpty()) return ConfirmedApproachWrite.AlreadyStored

    val marked = fresh.joinToString("\n") {
        "- $CONFIRMED_APPROACH_PREFIX $it $CONFIRMED_APPROACH_SUFFIX"
    }
    val base = existing.orEmpty()
    val separator = when {
        base.isEmpty() -> ""
        base.endsWith("\n") -> ""
        else -> "\n"
    }
    val candidate = base + separator + marked
    // Nunca usa take()/truncamento aqui: uma confirmação só existe se a
    // linha inteira, com prefixo e sufixo, couber no orçamento.
    if (candidate.length > MAX_APPROACH_CHARS) return ConfirmedApproachWrite.NoSpace
    return ConfirmedApproachWrite.Stored(candidate)
}
