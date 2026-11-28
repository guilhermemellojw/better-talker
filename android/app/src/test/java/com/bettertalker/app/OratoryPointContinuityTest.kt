package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatRouter
import com.bettertalker.app.data.copilot.ChatRouter.Route
import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.copilot.OratoryGeneration.Action
import com.bettertalker.app.data.copilot.OratoryGeneration.Mode
import com.bettertalker.app.data.copilot.OratoryGeneration.Result
import com.bettertalker.app.data.copilot.OratorySession
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-PEDAGOGY/P1 — regressões AI-03 (transição 2→3 relacionando o ponto
 * 1) e AI-04 (ponto 1 relacionado ao ponto 3).
 *
 * O modelo precisa receber o CONTEÚDO REAL dos pontos citados; IDs e
 * títulos sozinhos não permitem cumprir a relação.
 */
class OratoryPointContinuityTest {

    private val doc = S34Parser.parseS34(S34Fixture.TEXT)
    private val view1 = S34StructuralRetrieval.scopeToSection(doc, "sec-1")!!
    private val view2 = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!

    private fun oratory(route: Route): Route.Oratory {
        assertTrue("esperado ORATORY, veio ${ChatRouter.describe(route)}", route is Route.Oratory)
        return route as Route.Oratory
    }

    private fun ready(route: Route.Oratory): OratoryGeneration.Spec {
        val view = route.sectionId?.let { S34StructuralRetrieval.scopeToSection(doc, it) }
        val result = OratoryGeneration.spec(
            mode = route.mode,
            document = doc,
            view = view,
            action = route.action,
            relatedSectionId = route.relatedSectionId,
        )
        assertTrue("esperado Ready, veio $result", result is Result.Ready)
        return (result as Result.Ready).spec
    }

    @Test
    fun ai03_transicaoPreservaConteudoRealDoPontoAnterior() {
        val route = oratory(
            ChatRouter.route(
                "Crie uma transição do ponto 2 para o ponto 3, relacionando com o ponto 1.",
                doc,
                "sec-2",
                null,
            )
        )
        assertEquals(Mode.TRANSITION, route.mode)
        assertEquals("sec-2", route.sectionId)
        assertEquals("sec-1", route.relatedSectionId)

        val spec = ready(route)
        assertEquals("sec-1", spec.previous?.sectionId)
        assertTrue(spec.previous!!.content.contains("João 17:17"))

        val prompt = OratoryGeneration.buildCardPrompt(spec, "relacione com o ponto 1")
        assertTrue(prompt.contains("Ponto ANTERIOR (1)"))
        assertTrue(prompt.contains("João 17:17"))
        // O ponto seguinte continua presente: a ponte é 2→3.
        assertTrue(prompt.contains("Ponto SEGUINTE (3)"))
        assertTrue(prompt.contains("Hebreus 10:23"))
    }

    @Test
    fun ai04_relacaoEntrePontosEntraNaRotaOratoriaComConteudoReal() {
        val route = oratory(
            ChatRouter.route(
                "Volte ao ponto 1 e relacione com o ponto 3.",
                doc,
                "sec-2",
                null,
            )
        )
        assertEquals(Mode.DEVELOPMENT, route.mode)
        assertEquals("sec-1", route.sectionId)
        assertEquals("sec-3", route.relatedSectionId)

        val spec = ready(route)
        assertEquals("sec-3", spec.related?.sectionId)
        assertTrue(spec.related!!.content.contains("Hebreus 10:23"))

        val prompt = OratoryGeneration.buildCardPrompt(spec, "relacione com o ponto 3")
        assertTrue(prompt.contains("Ponto RELACIONADO (3)"))
        assertTrue(prompt.contains("Hebreus 10:23"))
        // O ponto 1 continua sendo o foco do desenvolvimento.
        assertTrue(prompt.contains("Ponto em foco (1)"))
        assertTrue(prompt.contains("João 17:17"))
        assertFalse(prompt.contains("Ponto SEGUINTE"))
    }

    @Test
    fun ai04_iteracaoMantemOPontoRelacionado() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-1", "sec-3")
        val decision = OratorySession.decide("Melhore.", doc, "sec-1", last)
        assertTrue(decision is OratorySession.Decision.Generate)
        assertEquals("sec-3", (decision as OratorySession.Decision.Generate).relatedSectionId)
    }

    @Test
    fun semPedidoDeRelacaoOPontoAnteriorNaoViraRecapitulacao() {
        val route = oratory(
            ChatRouter.route("Crie uma transição do ponto 2 para o ponto 3.", doc, "sec-2", null)
        )
        assertEquals(null, route.relatedSectionId)
        val spec = ready(route)
        assertEquals(null, spec.previous)
        val prompt = OratoryGeneration.buildCardPrompt(spec, "crie uma transição")
        assertFalse(prompt.contains("[S34] Ponto ANTERIOR"))
    }
}
