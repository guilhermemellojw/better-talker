package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.planning.Dossier
import kotlinx.coroutines.CancellationException

/**
 * MENTOR-INTEGRATION-P14 — controlador do trecho de preparação do turno.
 *
 * Código efetivamente chamado por `send()` para histórico, dossiê, captura
 * de alvo e persistência da pergunta. Dependências injetadas tornam
 * observáveis falhas, cancelamento e efeitos colaterais — sem duplicar a
 * implementação produtiva nem espalhar limpeza por cada operação.
 */
object MentorTurnLifecycle {

    data class Operations(
        val loadHistory: suspend () -> List<ChatTurn>,
        val loadDossier: suspend () -> Dossier?,
        val captureTarget: suspend (Dossier?) -> CardTarget?,
        val persistQuestion: suspend () -> Unit,
    )

    data class Prepared(
        val history: List<ChatTurn>,
        /** Falha de histórico é explicitamente entregue à decisão de continuidade. */
        val historyFailure: Exception?,
        val dossier: Dossier?,
        val target: CardTarget?,
    )

    sealed interface Result {
        data class Ready(val prepared: Prepared) : Result
        data class Failed(val stage: Stage, val cause: Exception) : Result
    }

    enum class Stage { HISTORY, DOSSIER, TARGET, QUESTION }

    /**
     * Ordem canônica. CancellationException nunca é convertida em falha comum:
     * a finalização do turno pertence ao try/catch/finally do chamador.
     */
    suspend fun prepare(ops: Operations): Result {
        var stage = Stage.HISTORY
        return try {
            var historyFailure: Exception? = null
            val history = try {
                ops.loadHistory()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                historyFailure = e
                emptyList()
            }
            stage = Stage.DOSSIER
            val dossier = try {
                ops.loadDossier()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null // dossiê indisponível degrada sem inventar contexto
            }
            stage = Stage.TARGET
            val target = ops.captureTarget(dossier)
            stage = Stage.QUESTION
            ops.persistQuestion()
            Result.Ready(Prepared(history, historyFailure, dossier, target))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(stage, e)
        }
    }
}
