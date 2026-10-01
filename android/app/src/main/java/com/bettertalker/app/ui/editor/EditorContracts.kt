package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contratos section-aware do editor multi-seção (3.2.5d).
 *
 * Preparados aqui; o `SectionCardEditor` passa a emitir seleção e o
 * `EditorScreen` a consumir inserções na 3.2.5e. O Copilot consome
 * `SelectionContext` na 3.2.5e.
 */

/**
 * Contexto da seleção do editor: identifica a seção/sub-ponto onde o
 * usuário está, e o texto selecionado (se houver).
 *
 * @param sectionId id da seção
 * @param subPointId id do sub-ponto (null para INTRO/CONCLUSION)
 * @param selectedText texto selecionado (vazio se cursor sem seleção)
 * @param fullContentHtml HTML completo do editor ativo (para contexto)
 */
data class SelectionContext(
    val sectionId: String,
    val subPointId: String?,
    val selectedText: String,
    val fullContentHtml: String,
)

/**
 * Inserção programática direcionada a uma seção/sub-ponto específico.
 *
 * @param sectionId id da seção destino
 * @param subPointId id do sub-ponto destino (null para INTRO/CONCLUSION)
 * @param markdown markdown a inserir
 * @param heading se presente, insere após este heading (senão no cursor/fim)
 */
data class SectionAwareInsert(
    val sectionId: String,
    val subPointId: String?,
    val markdown: String,
    val heading: String? = null,
)

/** Direção de movimentação em listas ordenadas (seções, sub-pontos). */
enum class MoveDirection { UP, DOWN }

/**
 * Mensagem de aviso de cascata ao excluir uma seção (3.2.5f.2b).
 * Vazia se a seção não tem sub-pontos (nada extra a excluir).
 */
internal fun cascadeWarningMessage(subPointCount: Int): String =
    if (subPointCount > 0) " Os $subPointCount sub-pontos serão excluídos junto."
    else ""

/**
 * Estado do aviso de estrutura do discurso (não-bloqueante).
 *
 * Extraído do EditorViewModel (hotfix P0 da 3.2.5f.2b) porque o VM tinha
 * um NPE de inicialização: o coletor dentro do `init` chamava
 * `updateStructureWarning()` sincronamente antes de `_structureWarning`
 * ser declarada.
 *
 * Agora o estado é auto-contido e testável em JVM puro.
 *
 * Internal: só o `EditorViewModel` consome.
 */
internal class StructureWarningHolder {
    private val _warning = MutableStateFlow<SectionValidationResult.Invalid?>(null)
    val warning: StateFlow<SectionValidationResult.Invalid?> = _warning.asStateFlow()

    /** Atualiza: `Invalid` vira aviso; `Valid`/null limpam. */
    fun update(result: SectionValidationResult?) {
        _warning.value = result as? SectionValidationResult.Invalid
    }

    /** Dispensa o aviso atual (pode reaparecer se revalidar). */
    fun dismiss() {
        _warning.value = null
    }
}
