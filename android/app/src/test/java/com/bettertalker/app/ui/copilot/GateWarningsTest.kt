package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.OratoryFidelityCheck
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 — avisos não bloqueantes dos verificadores no chat remoto. */
class GateWarningsTest {

    private val corpus = listOf(
        "Gên 1:26 Deus criou os humanos para viver uma vida perfeita, eterna, na Terra."
    )

    @Test
    fun versiculoSemFonteGeraAviso() {
        val notice = citationRevisionNotice("Como diz Gên 99:99, devemos confiar.", corpus)
        assertNotNull(notice)
        assertTrue(notice!!.startsWith("⚠️ Revise:"))
        assertTrue(notice.contains("gên 99:99"))
    }

    @Test
    fun citacaoNumeradaForaDoCorpusGeraAviso() {
        val notice = citationRevisionNotice("Veja [9] sobre isso.", corpus)
        assertNotNull(notice)
        assertTrue(notice!!.contains("[9]"))
    }

    @Test
    fun textoLimpoNaoGeraAviso() {
        assertNull(citationRevisionNotice("Deus criou os humanos para viver para sempre.", corpus))
        assertNull(citationRevisionNotice("Como diz Gên 1:26, devemos confiar.", corpus))
    }

    @Test
    fun fidelidadeInventadaGeraAvisoComMotivo() {
        val notice = fidelityRevisionNotice(
            OratoryFidelityCheck.Report(
                inventedReferences = listOf("Hebreus 10:23"),
                leakedReferences = emptyList(),
                unsupportedNumbers = listOf("7"),
            )
        )
        assertNotNull(notice)
        assertTrue(notice!!.startsWith("⚠️ Revise:"))
        assertTrue(notice.contains("Hebreus 10:23"))
        assertTrue(notice.contains("7"))
    }

    @Test
    fun fidelidadeOkNaoGeraAviso() {
        assertNull(
            fidelityRevisionNotice(OratoryFidelityCheck.Report(emptyList(), emptyList(), emptyList()))
        )
    }
}
