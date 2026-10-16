package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.edit.CopilotEditProposal
import com.bettertalker.app.data.edit.EditOperation
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.InsertPosition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #16-fix-c — o aceite não persiste marcadores internos (〈sugestão〉/
 * 〈decisão〉) e preserva o HTML (o strip genérico comeria os <p>).
 * Puro/testável.
 */
class AcceptProposalStripTest {

    private val title = "JEOVÁ É UM DEUS DE ORGANIZAÇÃO"

    private fun insert(html: String) = CopilotEditProposal(
        id = "p", mode = EditProposalMode.INSERT,
        operations = listOf(EditOperation.Insert("focus", InsertPosition.AFTER, html))
    )

    @Test
    fun marcadoresSaemNoAceiteHTMLIntacto() {
        val html = "<p>Texto normal. 〈sugestão〉Pergunta criativa?〈/sugestão〉 Fim.</p>"
        val out = applyProposalToSectionBlock(title, "", title, insert(html))
        assertEquals("<p>Texto normal. Pergunta criativa? Fim.</p>", out)
    }

    @Test
    fun textoSemMarcadoresInalterado() {
        val html = "<p>Texto normal.</p><p>Segundo parágrafo.</p>"
        val out = applyProposalToSectionBlock(title, "", title, insert(html))
        assertEquals(html, out)
    }
}
