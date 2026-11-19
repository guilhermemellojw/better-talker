package com.bettertalker.app.data.bible

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * UX-REF-1A2 — coordena toques em referências bíblicas.
 *
 * Cada solicitação recebe um token monotônico. Ao iniciar outra, a anterior é
 * cancelada e seu token deixa de ser atual. Mesmo que um resolvedor ignore o
 * cancelamento, resultados/erros antigos são descartados antes de tocar a UI.
 *
 * O escopo é o da tela (NoteChatHost); nada é global.
 */
class BibleReferenceOpenCoordinator(
    private val scope: CoroutineScope,
    private val onResolution: (BibleReferenceResolution) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    fun open(
        occurrence: BibleReferenceOccurrence,
        resolve: suspend (BibleReferenceOccurrence) -> BibleReferenceResolution,
    ) {
        val token = ++generation
        job?.cancel()
        job = scope.launch {
            try {
                val result = resolve(occurrence)
                if (token == generation) onResolution(result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == generation) {
                    onFailure("Não foi possível abrir esta referência agora.")
                }
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
}
