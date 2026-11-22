package com.bettertalker.app.data.publication

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * UX-REF-1B.3-B1 — coordena toques em referências de publicações.
 *
 * Mesmo padrão do [com.bettertalker.app.data.bible.BibleReferenceOpenCoordinator],
 * sem misturar os contratos de Bíblia e publicações.
 */
class PublicationReferenceOpenCoordinator(
    private val scope: CoroutineScope,
    private val onResolution: (PublicationReferenceResolution) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    fun open(
        occurrence: PublicationReferenceOccurrence,
        sourceText: String,
        resolve: suspend (PublicationReferenceOccurrence, String) -> PublicationReferenceResolution,
    ) {
        val token = ++generation
        job?.cancel()
        job = scope.launch {
            try {
                val result = resolve(occurrence, sourceText)
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