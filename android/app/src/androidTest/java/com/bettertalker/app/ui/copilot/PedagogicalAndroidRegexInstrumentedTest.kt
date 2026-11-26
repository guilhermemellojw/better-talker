package com.bettertalker.app.ui.copilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bettertalker.app.data.copilot.CardPayload
import com.bettertalker.app.data.copilot.CardTargetKind
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HOTFIX P0 — execução REAL no runtime Android (API 37).
 *

 * O crash reportado é `PatternSyntaxException` em
 * `PedagogicalIntegrationKt.<clinit>`: flags embutidos Java (`U`/`u`) não
 * são aceitos pelo motor ICU do Android. Este teste NÃO é substituído pela
 * compilação de androidTest: ele precisa ser EXECUTADO no dispositivo por D.
 */
@RunWith(AndroidJUnit4::class)
class PedagogicalAndroidRegexInstrumentedTest {

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
    fun p0a_inicializacaoDaClasseSemPatternSyntaxException() {
        // A primeira chamada força <clinit>; qualquer flag inválido lança aqui.
        assertEquals("Texto simples.", simplifyPedagogicalText("Texto simples."))
        assertEquals(
            PedagogicalAction.Delegate,
            planPedagogicalAction(PedagogicalContinuityBridge.dispatch("Oi.", context()), emptyMap()),
        )
    }

    @Test
    fun p0b_expressoesAvaliadasNoRuntimeAndroid() {
        val marcadores = simplifyPedagogicalText(
            "Em outras palavras, com o objetivo de explicar, o texto fala do amor.",
        )
        assertTrue(marcadores.contains("para explicar"))
        assertFalse(marcadores.contains("Em outras palavras"))

        val comReferencia = simplifyPedagogicalText(
            "Ou seja, João 3:16 fala do amor.",
        )
        assertTrue(comReferencia.contains("João 3:16"))
        assertFalse(comReferencia.contains("Ou seja"))
    }

    @Test
    fun p0c_simplificacaoAcentuadaF7NoAndroid() {
        val out = simplifyPedagogicalText("É importante ressaltar que João 3:16 fala do amor.")
        assertFalse("F7 no Android: $out", out.contains("É importante ressaltar"))
        assertTrue(out.contains("João 3:16"))
        assertEquals("É importante ressaltar", "É importante ressaltar") // sanidade do literal
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
        val reformula = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Explique de outra maneira.", context()),
            mapOf("sec-1" to "Romanos 8"),
        )
        assertTrue(reformula is PedagogicalAction.Reformulate)
        assertEquals("Romanos 8", (reformula as PedagogicalAction.Reformulate).topic)

        val desenvolve = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Desenvolva essa ideia.", context()),
            mapOf("sec-1" to "Romanos 8"),
        )
        assertTrue(desenvolve is PedagogicalAction.Develop)

        val feedback = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Gostei dessa sugestão.", context()),
            emptyMap(),
        )
        assertEquals(PedagogicalAction.PositiveFeedback, feedback)
    }
}
