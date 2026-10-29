package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.TurnUiState

/**
 * M2a — regra única de erro do chat:
 *
 * - com turno ativo, o erro é renderizado DENTRO do turno (inclusive
 *   DONE/CANCELLED/ERROR); o row avulso fica escondido;
 * - sem turno ativo, fluxos legados/erro antes de criar o turno usam o
 *   `ChatErrorRow` normal;
 * - nunca os dois.
 *
 * Puro/testável. A integração no `ChatScreen` entra na implementação real
 * do M2a; o protótipo fake apenas demonstra o mesmo contrato.
 */
fun shouldShowStandaloneError(
    activeTurn: TurnUiState?,
    runState: ChatRunState,
): Boolean = activeTurn == null &&
    (runState is ChatRunState.Error || runState is ChatRunState.Cancelled)
