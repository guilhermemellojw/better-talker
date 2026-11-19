package com.bettertalker.app.data.bible

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BibleReferenceOpenCoordinatorTest {

    private fun occurrence(raw: String) = BibleReferenceOccurrence(
        raw = raw,
        start = 0,
        endExclusive = raw.length,
        book = "Mateus",
        chapter = 24,
        verses = listOf(14),
        chapterOnly = false,
        canonicalRefs = listOf("Mt 24:14"),
    )

    private fun unavailable(label: String) =
        BibleReferenceResolution.Unavailable(label)

    @Test
    fun somenteOToqueMaisRecenteAbreMesmoComResolvedorNaoCooperativo() = runBlocking {
        val first = CompletableDeferred<BibleReferenceResolution>()
        val second = CompletableDeferred<BibleReferenceResolution>()
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val coordinator = BibleReferenceOpenCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResolution = { events += (it as BibleReferenceResolution.Unavailable).reason },
            onFailure = { failures += it },
        )

        coordinator.open(occurrence("A")) {
            withContext(NonCancellable) { first.await() }
        }
        coordinator.open(occurrence("B")) { second.await() }

        second.complete(unavailable("B"))
        first.complete(unavailable("A"))

        assertEquals(listOf("B"), events)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun erroAntigoNaoApareceDepoisDoToqueMaisRecente() = runBlocking {
        val first = CompletableDeferred<BibleReferenceResolution>()
        val second = CompletableDeferred<BibleReferenceResolution>()
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val coordinator = BibleReferenceOpenCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResolution = { events += (it as BibleReferenceResolution.Unavailable).reason },
            onFailure = { failures += it },
        )

        coordinator.open(occurrence("A")) {
            withContext(NonCancellable) { first.await() }
        }
        coordinator.open(occurrence("B")) { second.await() }

        second.complete(unavailable("B"))
        first.complete(unavailable("A-velho"))

        assertEquals(listOf("B"), events)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun falhaInesperadaAvisaSemAbrirPassagem() = runBlocking {
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val coordinator = BibleReferenceOpenCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResolution = { events += it.toString() },
            onFailure = { failures += it },
        )

        coordinator.open(occurrence("A")) {
            error("quebra inesperada")
        }

        assertTrue(events.isEmpty())
        assertEquals(
            listOf("Não foi possível abrir esta referência agora."),
            failures,
        )
    }

    @Test
    fun cancelamentoNaoAlteraInterfaceNemViraErro() = runBlocking {
        val first = CompletableDeferred<BibleReferenceResolution>()
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val coordinator = BibleReferenceOpenCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            onResolution = { events += it.toString() },
            onFailure = { failures += it },
        )

        coordinator.open(occurrence("A")) {
            withContext(NonCancellable) { first.await() }
        }
        coordinator.cancel()
        first.complete(unavailable("A"))

        assertTrue(events.isEmpty())
        assertTrue(failures.isEmpty())
    }
}
