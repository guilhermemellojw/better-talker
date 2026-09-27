package com.bettertalker.app.data.edit

/**
 * Fase 18 — BLOCO B. Edição assistida F5 como Kotlin puro.
 *
 * Porte conceitual de `editProposal.ts` + `proposalParser.ts` + `editHistory.ts`
 * do web. Mesmas regras: JSON em cerca, modelo nunca escolhe alvo, veto de
 * delete via LLM, max 10 ops, max 20k chars, baseHashes FNV-1a, stale,
 * atomicidade, undo/redo (cap 50).
 *
 * O "bloco" nativo é genérico ([EditBlock]) para não acoplar ao Room aqui:
 * a fiação (nota/seleção) vive na ViewModel. Sem IO, sem Android.
 */

const val MAX_PROPOSAL_OPS = 10
const val MAX_OP_CONTENT_CHARS = 20000
const val MAX_EDIT_HISTORY = 50

enum class EditProposalMode { SUGGEST, REWRITE, IMPROVE, INSERT, DELETE }

sealed interface EditOperation {
    val targetId: String
    data class Insert(override val targetId: String, val position: InsertPosition, val contentHtml: String) : EditOperation
    data class Replace(override val targetId: String, val contentHtml: String) : EditOperation
    data class Delete(override val targetId: String) : EditOperation
}

enum class InsertPosition { BEFORE, AFTER }

data class CopilotEditProposal(
    val id: String,
    val mode: EditProposalMode,
    val explanation: String? = null,
    val operations: List<EditOperation>,
    val baseHashes: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

enum class ProposalApplyStatus { APPLIED, STALE_PROPOSAL, INVALID }

enum class ValidationError {
    UNKNOWN_TARGET, INVALID_POSITION, EMPTY_CONTENT, LAST_BLOCK, STALE_PROPOSAL
}

sealed interface ValidationResult {
    data object Ok : ValidationResult
    data class Fail(val error: ValidationError) : ValidationResult
}

/** Bloco genérico: id estável + conteúdo. Sem Room aqui. */
data class EditBlock(val id: String, val contentHtml: String)

/** Hash determinístico FNV-1a, byte-idêntico ao web (UTF-16 por unidade). */
fun hashText(s: String): String {
    var h = 0x811c9dc5.toInt()
    for (c in s) {
        h = h xor c.code
        h *= 0x01000193
    }
    return h.toUInt().toString(16).padStart(8, '0')
}

/** Texto puro sem DOM. Mesma ordem do web (tags antes de entidades);
 * só a decodificação é mais completa por causa do editor nativo. */
fun stripHtmlToText(html: String): String {
    return unescapeHtmlEntities(html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|h[1-6]|li|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " "))
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()
}

/**
 * Decodifica entidades HTML para o caractere real. Diferença legítima de
 * plataforma (§51 F16): o editor nativo (richeditor) persiste acentos como
 * entidades (`&ccedil;`), enquanto o web trabalha com UTF-8 cru — sem isso,
 * nenhuma âncora de texto com acento bateria no pt-BR. Puro/testável.
 */
fun unescapeHtmlEntities(s: String): String {
    var out = s
    out = Regex("&#(\\d+);").replace(out) {
        it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
    }
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) {
        it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: " "
    }
    for ((entity, char) in NAMED_ENTITIES) {
        if (out.contains(entity)) out = out.replace(entity, char)
    }
    return out
}

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
    "&apos;" to "'",
    "&aacute;" to "á", "&Aacute;" to "Á", "&agrave;" to "à", "&Agrave;" to "À",
    "&acirc;" to "â", "&Acirc;" to "Â", "&atilde;" to "ã", "&Atilde;" to "Ã",
    "&auml;" to "ä", "&Auml;" to "Ä", "&aring;" to "å", "&Aring;" to "Å",
    "&eacute;" to "é", "&Eacute;" to "É", "&egrave;" to "è", "&Egrave;" to "È",
    "&ecirc;" to "ê", "&Ecirc;" to "Ê", "&euml;" to "ë", "&Euml;" to "Ë",
    "&iacute;" to "í", "&Iacute;" to "Í", "&igrave;" to "ì", "&Igrave;" to "Ì",
    "&icirc;" to "î", "&Icirc;" to "Î", "&iuml;" to "ï", "&Iuml;" to "Ï",
    "&oacute;" to "ó", "&Oacute;" to "Ó", "&ograve;" to "ò", "&Ograve;" to "Ò",
    "&ocirc;" to "ô", "&Ocirc;" to "Ô", "&otilde;" to "õ", "&Otilde;" to "Õ",
    "&ouml;" to "ö", "&Ouml;" to "Ö",
    "&uacute;" to "ú", "&Uacute;" to "Ú", "&ugrave;" to "ù", "&Ugrave;" to "Ù",
    "&ucirc;" to "û", "&Ucirc;" to "Û", "&uuml;" to "ü", "&Uuml;" to "Ü",
    "&ccedil;" to "ç", "&Ccedil;" to "Ç", "&ntilde;" to "ñ", "&Ntilde;" to "Ñ",
    "&yacute;" to "ý", "&Yacute;" to "Ý",
    "&hellip;" to "…", "&mdash;" to "—", "&ndash;" to "–",
    "&ldquo;" to "\u201C", "&rdquo;" to "\u201D", "&lsquo;" to "\u2018", "&rsquo;" to "\u2019",
    "&laquo;" to "«", "&raquo;" to "»", "&sect;" to "§", "&para;" to "¶",
    "&middot;" to "·", "&iexcl;" to "¡", "&iquest;" to "¿",
    "&szlig;" to "ß", "&copy;" to "©", "&reg;" to "®", "&trade;" to "™",
    "&deg;" to "°", "&plusmn;" to "±", "&times;" to "×", "&divide;" to "÷",
    "&frac12;" to "½", "&frac14;" to "¼", "&oelig;" to "œ", "&OElig;" to "Œ",
    "&aelig;" to "æ", "&AElig;" to "Æ", "&period;" to ".", "&comma;" to ",",
    "&colon;" to ":", "&semi;" to ";", "&excl;" to "!", "&quest;" to "?",
    "&lpar;" to "(", "&rpar;" to ")"
)

/** Valida sem aplicar: alvos, posições, invariantes e stale. Puro/testável. */
fun validateEditProposal(blocks: List<EditBlock>, proposal: CopilotEditProposal): ValidationResult {
    if (proposal.operations.isEmpty() || proposal.operations.size > MAX_PROPOSAL_OPS) {
        return ValidationResult.Fail(ValidationError.INVALID_POSITION)
    }
    val byId = blocks.associateBy { it.id }
    val targets = proposal.operations.map { it.targetId }.toSet()
    for (id in targets) {
        if (!byId.containsKey(id)) return ValidationResult.Fail(ValidationError.UNKNOWN_TARGET)
    }
    for (op in proposal.operations) {
        when (op) {
            is EditOperation.Insert -> {
                if (op.contentHtml.isBlank() || op.contentHtml.length > MAX_OP_CONTENT_CHARS) {
                    return ValidationResult.Fail(ValidationError.EMPTY_CONTENT)
                }
            }
            is EditOperation.Replace -> {
                if (op.contentHtml.isBlank() || op.contentHtml.length > MAX_OP_CONTENT_CHARS) {
                    return ValidationResult.Fail(ValidationError.EMPTY_CONTENT)
                }
            }
            is EditOperation.Delete -> {
                // Invariante do editor: sempre ao menos 1 bloco.
                val deleted = proposal.operations
                    .filterIsInstance<EditOperation.Delete>().map { it.targetId }.toSet()
                if (blocks.none { it.id !in deleted }) {
                    return ValidationResult.Fail(ValidationError.LAST_BLOCK)
                }
            }
        }
    }
    // Stale: qualquer alvo cujo conteúdo mudou desde a geração.
    for (id in targets) {
        val current = byId[id]
        val base = proposal.baseHashes[id]
        if (base != null && current != null && hashText(current.contentHtml) != base) {
            return ValidationResult.Fail(ValidationError.STALE_PROPOSAL)
        }
    }
    return ValidationResult.Ok
}

data class ApplyResult(val status: ProposalApplyStatus, val blocks: List<EditBlock>? = null)

/**
 * Aplica atomicamente: valida tudo antes; qualquer falha => nada muda.
 * Retorna nova lista (não muta a original).
 */
fun applyEditProposal(
    blocks: List<EditBlock>,
    proposal: CopilotEditProposal,
    newId: (EditOperation.Insert) -> String = { op -> "block-copilot-${System.currentTimeMillis()}-${op.targetId.hashCode()}" }
): ApplyResult {
    when (val v = validateEditProposal(blocks, proposal)) {
        is ValidationResult.Fail -> return ApplyResult(
            if (v.error == ValidationError.STALE_PROPOSAL) ProposalApplyStatus.STALE_PROPOSAL
            else ProposalApplyStatus.INVALID)
        ValidationResult.Ok -> Unit
    }
    var out = blocks.map { it.copy() }
    for (op in proposal.operations) {
        when (op) {
            is EditOperation.Replace -> {
                out = out.map { b -> if (b.id == op.targetId) b.copy(contentHtml = op.contentHtml) else b }
            }
            is EditOperation.Delete -> {
                out = out.filter { it.id != op.targetId }
            }
            is EditOperation.Insert -> {
                val idx = out.indexOfFirst { it.id == op.targetId }
                if (idx < 0) return ApplyResult(ProposalApplyStatus.INVALID)
                val created = EditBlock(newId(op), op.contentHtml)
                val at = if (op.position == InsertPosition.BEFORE) idx else idx + 1
                out = out.subList(0, at) + created + out.subList(at, out.size)
            }
        }
    }
    return ApplyResult(ProposalApplyStatus.APPLIED, out)
}

/** Captura os hashes-base do alvo no momento da geração. Puro/testável. */
fun captureBaseHashes(blocks: List<EditBlock>, targetIds: List<String>): Map<String, String> {
    val byId = blocks.associateBy { it.id }
    return targetIds.mapNotNull { id -> byId[id]?.let { id to hashText(it.contentHtml) } }.toMap()
}

// ---------- Parser (proposalParser.ts) ----------

sealed interface ParseResult {
    data class Ok(val proposal: CopilotEditProposal) : ParseResult
    data class Invalid(val reason: String = "unknown") : ParseResult
}

/** Extrai o JSON: cerca ```json, cerca genérica ou objeto cru. Puro/testável. */
fun extractJsonFence(text: String): String? {
    Regex("```json\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)?.let {
        return it.groupValues[1].trim()
    }
    Regex("```\\s*([\\s\\S]*?)```").find(text)?.let {
        return it.groupValues[1].trim()
    }
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) return trimmed
    return null
}

/** Extrai campo string "key": "value..." com unescape mínimo. Puro/testável. */
fun jsonStringField(obj: String, key: String): String? {
    val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)
        ?: return null
    return GeminiJson.unescape(m.groupValues[1])
}

/** Divide os objetos de topo de um array [...] respeitando strings e chaves. */
fun splitTopObjects(array: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var inStr = false
    var esc = false
    var start = -1
    for (i in array.indices) {
        val c = array[i]
        if (inStr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') inStr = false
            continue
        }
        when (c) {
            '"' -> inStr = true
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    out += array.substring(start, i + 1)
                    start = -1
                }
            }
        }
    }
    return out
}

/**
 * Parseia a resposta do modelo para o alvo dado. O alvo vem do app
 * (nunca do modelo). Delete via LLM é vetado. Puro/testável.
 */
fun parseEditProposal(
    rawText: String,
    blocks: List<EditBlock>,
    targetId: String,
    mode: EditProposalMode,
    proposalId: String = "prop-${System.currentTimeMillis()}"
): ParseResult {
    if (blocks.none { it.id == targetId }) return ParseResult.Invalid("unknown_target")
    if (mode == EditProposalMode.DELETE) {
        // Delete não precisa de LLM: proposta local determinística.
        return ParseResult.Ok(CopilotEditProposal(
            id = proposalId, mode = mode,
            explanation = "Remover o bloco.",
            operations = listOf(EditOperation.Delete(targetId)),
            baseHashes = captureBaseHashes(blocks, listOf(targetId))
        ))
    }
    val jsonText = extractJsonFence(rawText) ?: return ParseResult.Invalid("no_fence")
    val opsArray = Regex("\"operations\"\\s*:\\s*\\[").find(jsonText)
        ?: return ParseResult.Invalid("no_operations_array")
    val arrayStart = opsArray.range.last + 1
    // encontra o ] que fecha o array (nível 0 de colchetes)
    var depth = 1
    var inStr = false
    var esc = false
    var end = -1
    for (i in arrayStart until jsonText.length) {
        val c = jsonText[i]
        if (inStr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') inStr = false
            continue
        }
        when (c) {
            '"' -> inStr = true
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
        }
    }
    if (end < 0) return ParseResult.Invalid("unbalanced_array")
    val rawOps = splitTopObjects(jsonText.substring(arrayStart, end))
    if (rawOps.isEmpty()) return ParseResult.Invalid("empty_operations")
    if (rawOps.size > MAX_PROPOSAL_OPS) return ParseResult.Invalid("too_many_operations")
    val ops = mutableListOf<EditOperation>()
    for (raw in rawOps) {
        val type = jsonStringField(raw, "type") ?: return ParseResult.Invalid("op_without_type")
        // Delete via LLM é vetado (§18 web).
        if (type == "delete") return ParseResult.Invalid("llm_delete_vetoed")
        val content = jsonStringField(raw, "contentHtml")
            ?: jsonStringField(raw, "content")
            ?: return ParseResult.Invalid("op_without_content")
        if (content.isBlank() || content.length > MAX_OP_CONTENT_CHARS) {
            return ParseResult.Invalid("op_content_invalid")
        }
        when (type) {
            "replace" -> ops += EditOperation.Replace(targetId, content)
            "insert" -> {
                val pos = if (jsonStringField(raw, "position") == "before") InsertPosition.BEFORE
                else InsertPosition.AFTER
                ops += EditOperation.Insert(targetId, pos, content)
            }
            else -> return ParseResult.Invalid("op_unknown_type:" + type.take(20))
        }
    }
    if (ops.isEmpty()) return ParseResult.Invalid("no_valid_operations")
    val explanation = jsonStringField(jsonText, "explanation")?.take(500)
    return ParseResult.Ok(CopilotEditProposal(
        id = proposalId, mode = mode, explanation = explanation,
        operations = ops,
        baseHashes = captureBaseHashes(blocks, listOf(targetId))
    ))
}

/** Unescape JSON mínimo compartilhado com o parser. */
private object GeminiJson {
    fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

// ---------- Histórico (editHistory.ts) ----------

/**
 * Undo/redo por pilha de snapshots. push só no accept; edição nova limpa
 * o redo. Sem IO.
 */
class EditHistory {
    private val undoStack = ArrayDeque<List<EditBlock>>()
    private val redoStack = ArrayDeque<List<EditBlock>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(blocks: List<EditBlock>) {
        undoStack.addLast(blocks.map { it.copy() })
        if (undoStack.size > MAX_EDIT_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(current: List<EditBlock>): List<EditBlock>? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current.map { it.copy() })
        return prev
    }

    fun redo(current: List<EditBlock>): List<EditBlock>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current.map { it.copy() })
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

/**
 * Texto resultante da proposta sobre o foco (para preview ANTES/DEPOIS e
 * verificação). Puro/testável.
 */
fun renderAfterText(proposal: CopilotEditProposal, focusText: String): String {
    val blocks = applyEditProposal(listOf(EditBlock("focus", focusText)), proposal).blocks
        ?: return focusText
    return blocks.map { stripHtmlToText(it.contentHtml) }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}
