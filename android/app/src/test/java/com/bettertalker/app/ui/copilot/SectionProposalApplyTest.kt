package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.edit.CopilotEditProposal
import com.bettertalker.app.data.edit.EditOperation
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.InsertPosition
import com.bettertalker.app.data.edit.hashText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T2 (Bug #14) — proposta com foco em SEÇÃO: o bloco é título + conteúdo e o
 * apply devolve o novo conteúdo (sem a linha do título). Stale via FNV-1a do
 * bloco (mudança real → hash diferente). Puro/testável.
 */
class SectionProposalApplyTest {

    private val title = "FOMOS CRIADOS PARA VIVER PARA SEMPRE"

    private fun insert(after: String, html: String) = CopilotEditProposal(
        id = "p1", mode = EditProposalMode.INSERT,
        operations = listOf(EditOperation.Insert("focus", InsertPosition.AFTER, html))
    )

    private fun replace(html: String) = CopilotEditProposal(
        id = "p2", mode = EditProposalMode.IMPROVE,
        operations = listOf(EditOperation.Replace("focus", html))
    )

    @Test
    fun insertDepoisDoTituloViraConteudoDaSecao() {
        val out = applyProposalToSectionBlock(title, "", title, insert(title, "<p>novo</p>"))
        assertEquals("<p>novo</p>", out)
    }

    @Test
    fun insertPreservaConteudoExistente() {
        val out = applyProposalToSectionBlock(
            title, "<p>antigo</p>", title, insert(title, "<p>novo</p>")
        )
        assertEquals("<p>novo</p>\n\n<p>antigo</p>", out)
    }

    @Test
    fun replaceNoFocoViraConteudoSemTitulo() {
        val out = applyProposalToSectionBlock(
            title, "<p>antigo</p>", title, replace("<p>melhor</p>")
        )
        assertEquals("<p>melhor</p>\n\n<p>antigo</p>", out)
    }

    @Test
    fun focoAusenteEhStale() {
        assertNull(
            applyProposalToSectionBlock(
                title, "<p>x</p>", "OUTRO TÍTULO", insert(title, "<p>y</p>")
            )
        )
    }

    @Test
    fun hashDoBlocoDetectaMudancaReal() {
        val h1 = hashText(sectionProposalBlock(title, "<p>a</p>"))
        val h2 = hashText(sectionProposalBlock(title, "<p>b</p>"))
        assertNotEquals(h1, h2)
        assertEquals(h1, hashText(sectionProposalBlock(title, "<p>a</p>")))
    }
}
