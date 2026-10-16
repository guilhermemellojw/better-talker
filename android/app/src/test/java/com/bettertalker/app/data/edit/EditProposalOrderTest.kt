package com.bettertalker.app.data.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #16-fix-c — múltiplos Insert(AFTER) no mesmo alvo mantêm a ordem do
 * modelo (antes saíam invertidos: cada um caía em idx+1 do alvo fixo).
 * Puro/testável.
 */
class EditProposalOrderTest {

    private val blocks = listOf(EditBlock("focus", "<h2>T</h2>"))

    private fun after(vararg html: String) = CopilotEditProposal(
        id = "p", mode = EditProposalMode.INSERT, explanation = null,
        operations = html.map { EditOperation.Insert("focus", InsertPosition.AFTER, it) }
    )

    private fun texts(
        p: CopilotEditProposal,
        newId: ((EditOperation.Insert) -> String)? = null
    ): List<String> {
        val r = if (newId == null) applyEditProposal(blocks, p) else applyEditProposal(blocks, p, newId)
        return r.blocks!!.map { stripHtmlToText(it.contentHtml) }.filter { it.isNotBlank() }
    }

    @Test
    fun quatroInsertsAfterNoMesmoAlvoMantemOrdem() {
        // newId default (produção): ids repetidos no mesmo ms não invertem.
        val r = applyEditProposal(blocks, after("<p>A</p>", "<p>B</p>", "<p>C</p>", "<p>D</p>"))
        assertEquals(ProposalApplyStatus.APPLIED, r.status)
        assertEquals(
            listOf("T", "A", "B", "C", "D"),
            r.blocks!!.map { stripHtmlToText(it.contentHtml) }.filter { it.isNotBlank() }
        )
    }

    @Test
    fun insertsAfterComReplacePreservaComportamento() {
        val ops = listOf(
            EditOperation.Insert("focus", InsertPosition.AFTER, "<p>A</p>"),
            EditOperation.Insert("focus", InsertPosition.AFTER, "<p>B</p>"),
            EditOperation.Insert("focus", InsertPosition.AFTER, "<p>C</p>"),
            EditOperation.Replace("focus", "<p>R</p>"),
        )
        val p = CopilotEditProposal("p", EditProposalMode.INSERT, null, ops)
        assertEquals(listOf("R", "A", "B", "C"), texts(p) { "n-${it.contentHtml}" })
    }

    @Test
    fun misturaBeforeAfterMantemOrdemCorreta() {
        val ops = listOf(
            EditOperation.Insert("focus", InsertPosition.BEFORE, "<p>X</p>"),
            EditOperation.Insert("focus", InsertPosition.AFTER, "<p>A</p>"),
            EditOperation.Insert("focus", InsertPosition.AFTER, "<p>B</p>"),
        )
        val p = CopilotEditProposal("p", EditProposalMode.INSERT, null, ops)
        assertEquals(listOf("X", "T", "A", "B"), texts(p) { "n-${it.contentHtml}" })
    }
}
