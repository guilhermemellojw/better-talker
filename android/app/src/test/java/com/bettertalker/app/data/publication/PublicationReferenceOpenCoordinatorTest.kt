package com.bettertalker.app.data.publication

import com.bettertalker.app.ui.components.PublicationDisplayLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = scope,
            onResolution = { results += it },
            onFailure = { },
        )
        val l = link()
        coordinator.open(l.occurrence, l.sourceText) { occ, _ ->
            PublicationReferenceResolution.NotRecognized(occ.raw)
        }
        delay(50)
        assertEquals(1, results.size)
    }

    @Test
    fun doisCliquesRapidosMantemSomenteRecente() = runBlocking {
        val results = mutableListOf<PublicationReferenceResolution>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = scope,
            onResolution = { results += it },
            onFailure = { },
        )
        val l = link()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            delay(1000)
            PublicationReferenceResolution.NotRecognized("antigo")
        }
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            PublicationReferenceResolution.NotRecognized("recente")
        }
        delay(50)
        assertTrue(results.isEmpty() || results.last().let { it is PublicationReferenceResolution.NotRecognized && it.raw == "recente" })
    }

    @Test
    fun excecaoNaResolucao() = runBlocking {
        val failures = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = scope,
            onResolution = { },
            onFailure = { failures += it },
        )
        val l = link()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            throw IllegalStateException("boom")
        }
        delay(50)
        assertEquals(1, failures.size)
    }

    @Test
    fun cancelamentoImpedeResultadoTardio() = runBlocking {
        val results = mutableListOf<PublicationReferenceResolution>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = PublicationReferenceOpenCoordinator(
            scope = scope,
            onResolution = { results += it },
            onFailure = { },
        )
        val l = link()
        coordinator.open(l.occurrence, l.sourceText) { _, _ ->
            delay(1000)
            PublicationReferenceResolution.NotRecognized("tardio")
        }
        coordinator.cancel()
        delay(50)
        assertTrue(results.isEmpty())
    }
}