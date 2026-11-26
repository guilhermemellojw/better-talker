package com.bettertalker.app.ui.copilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bettertalker.app.data.copilot.CardPayload
import com.bettertalker.app.data.copilot.CardTargetKind
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CP-02 P0 gate — reaproveitamento do patch de D (`CP02-P9-RUNTIME-TEST.patch`).
 *
 * Execução REAL no runtime Android (API 37), com inicialização forçada de
 * `PedagogicalIntegrationKt`. A compilação de androidTest NÃO substitui esta
 * execução, que pertence a @D.
 */
@RunWith(AndroidJUnit4::class)
class PedagogicalRuntimeInitTest {

    private fun context() = PedagogicalContinuityBridge.Context(
        noteId = "n1",
        sectionId = "sec-1",
        focus = Focus.SECTION,
        lastCard = CardPayload(
            targetKind = CardTargetKind.SECTION,
            targetId = "sec-1",
            targetLabel = "Romanos 8",
            baseHash = "h",
        ),
        lastCardNoteId = "n1",
        lastCardSectionId = "sec-1",
    )

    @Test
    fun p0a_inicializacaoRealERelatorioDeFalha() {
        // Reusa a técnica do patch de D: Class.forName(..., true, classLoader)
        // força <clinit> e converte ExceptionInInitializerError em falha legível.
        try {
            val clazz = Class.forName(
                "com.bettertalker.app.ui.copilot.PedagogicalIntegrationKt",
                true,
                javaClass.classLoader,
            )
            val simplify = clazz.getDeclaredMethod("simplifyPedagogicalText", String::class.java)
                .apply { isAccessible = true }
            val input = "É importante ressaltar que, basicamente, o texto cita " +
                "“Trecho recuperado” e João 17:17."
            val output = simplify.invoke(null, input) as String
            assertFalse("F7: filler deveria sair: $output", output.contains("É importante ressaltar"))
            assertTrue("P9: basicamente deve permanecer: $output", output.contains("basicamente"))
            assertTrue("F8: rótulo deve permanecer: $output", output.contains("“Trecho recuperado”"))
            assertTrue("Referência deve permanecer: $output", output.contains("João 17:17"))

            val plan = clazz.getDeclaredMethod(
                "planPedagogicalAction",
                PedagogicalContinuityBridge.Dispatch::class.java,
                Map::class.java,
            ).apply { isAccessible = true }
            val action = plan.invoke(
                null,
                PedagogicalContinuityBridge.dispatch("Explique de outra maneira.", context()),
                mapOf("sec-1" to "Romanos 8"),
            )
            assertTrue("seam pedagógico deveria reformular: $action", action is PedagogicalAction.Reformulate)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            fail(
                "P0: inicialização/execução pedagógica falhou no Android: " +
                    "${cause::class.java.name}: ${cause.message}",
            )
        }
    }

    @Test
    fun p0b_expressoesAvaliadasNoRuntimeAndroid() {
        val marcadores = simplifyPedagogicalText(
            "Em outras palavras, com o objetivo de explicar, o texto fala do amor.",
        )
        assertTrue(marcadores.contains("para explicar"))
        assertFalse(marcadores.contains("Em outras palavras"))

        val comReferencia = simplifyPedagogicalText("Ou seja, João 3:16 fala do amor.")
        assertTrue(comReferencia.contains("João 3:16"))
        assertFalse(comReferencia.contains("Ou seja"))
    }

    @Test
    fun p0c_simplificacaoAcentuadaF7NoAndroid() {
        val out = simplifyPedagogicalText("É importante ressaltar que João 3:16 fala do amor.")
        assertFalse("F7 no Android: $out", out.contains("É importante ressaltar"))
        assertTrue(out.contains("João 3:16"))
    }

    @Test
    fun p0d_basicamenteEQualificadoresPreservadosF9() {
        listOf(
            "O trabalho está basicamente concluído.",
            "Ele concorda basicamente com a proposta.",
            "Basicamente, devemos demonstrar amor.",
            "Em geral, essa orientação se aplica.",
            "De certa forma, isso é verdade.",
            "Literalmente, ele disse isso.",
        ).forEach { raw -> assertEquals("P9 no Android: $raw", raw, simplifyPedagogicalText(raw)) }
    }

    @Test
    fun p0e_citacoesReferenciasEProvenienciaF8() {
        val rotulo = "Trecho recuperado: “amor paciente” — João 3:16"
        val out = simplifyPedagogicalText("Bom, $rotulo.")
        assertTrue(out.contains(rotulo))
        assertFalse(out.contains('\u0000'))
        assertEquals(out, simplifyPedagogicalText(out))
    }

    @Test
    fun p0f_seamPedagogicoSemCrash() {
        val titulos = mapOf("sec-1" to "Romanos 8")
        val reformula = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Explique de outra maneira.", context()), titulos,
        )
        assertTrue(reformula is PedagogicalAction.Reformulate)
        assertEquals("Romanos 8", (reformula as PedagogicalAction.Reformulate).topic)

        val desenvolve = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Desenvolva essa ideia.", context()), titulos,
        )
        assertTrue(desenvolve is PedagogicalAction.Develop)

        val feedback = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Gostei dessa sugestão.", context()), emptyMap(),
        )
        assertEquals(PedagogicalAction.PositiveFeedback, feedback)
    }
}
