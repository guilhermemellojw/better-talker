package com.bettertalker.app.data.publication

import com.bettertalker.app.ui.components.PublicationDisplayLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationReferenceOpenCoordinatorTest {

    private fun occurrence(text: String = "Achegue-se a Jeová"): PublicationReferenceOccurrence {
        return PublicationReferenceOccurrence(
            raw = text,
            start = 0,
            endExclusive = text.length,
            publicationKey = "lff",
            titleHint = text,
            editionKey = null,
        )
    }

    private fun link(text: String = "Achegue-se a Jeová"): PublicationDisplayLink =
        PublicationDisplayLink(
            start = 0,
            endExclusive = text.length,
            id = "0:0",
            occurrence = occurrence(text),
            sourceText = text,
        )

    @Test
    fun resolucaoBemSucedida() = runBlocking {
        val results = mutableListOf<PublicationReferenceResolution>()
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = this,
            onResolution = { results += it },
            onFailure = { },
        )
        val l = link()
        coordinator.open(l.occurrence, l.sourceText) { occ, _ ->
            PublicationReferenceResolution.NotRecognized(occ.raw)
        }
        kotlinx.coroutines.yield()
        assertEquals(1, results.size)
        val result = results.single() as PublicationReferenceResolution.NotRecognized
        assertEquals(l.occurrence.raw, result.raw)
    }

    @Test
    fun doisCliquesRapidosMantemSomenteRecente() = runBlocking {
        val results = mutableListOf<PublicationReferenceResolution>()
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = this,
            onResolution = { results += it },
            onFailure = { },
        )
        val l = link()
        val firstGate = CompletableDeferred<Unit>()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            withContext(NonCancellable) { firstGate.await() }
            PublicationReferenceResolution.NotRecognized("antigo")
        }
        kotlinx.coroutines.yield()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            PublicationReferenceResolution.NotRecognized("recente")
        }
        kotlinx.coroutines.yield()
        firstGate.complete(Unit)
        kotlinx.coroutines.yield()
        assertEquals(1, results.size)
        val result = results.single() as PublicationReferenceResolution.NotRecognized
        assertEquals("recente", result.raw)
    }

    @Test
    fun excecaoNaResolucao() = runBlocking {
        val failures = mutableListOf<String>()
        val results = mutableListOf<PublicationReferenceResolution>()
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = this,
            onResolution = { results += it },
            onFailure = { failures += it },
        )
        val l = link()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            throw IllegalStateException("boom")
        }
        kotlinx.coroutines.yield()
        assertEquals(0, results.size)
        assertEquals(1, failures.size)
        assertEquals("Não foi possível abrir esta referência agora.", failures.single())
    }

    @Test
    fun cancelamentoImpedeResultadoTardio() = runBlocking {
        val results = mutableListOf<PublicationReferenceResolution>()
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = this,
            onResolution = { results += it },
            onFailure = { },
        )
        val l = link()
        val gate = CompletableDeferred<Unit>()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            withContext(NonCancellable) { gate.await() }
            PublicationReferenceResolution.NotRecognized("tardio")
        }
        coordinator.cancel()
        gate.complete(Unit)
        assertTrue(results.isEmpty())
    }
}