package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.edit.CopilotEditProposal
import com.bettertalker.app.data.edit.EditOperation
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.InsertPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #26-fix — aceite na seção: botão padrão ADICIONA o texto proposto (append
 * adaptativo); "Substituir a seção" mantém o comportamento clássico; o
 * delta das ops nunca duplica o texto antigo; o notice de fidelidade
 * permanece depois de aplicar.
 */
class ProposalAppendTest {

    private fun proposal(vararg ops: EditOperation) = CopilotEditProposal(
        id = "p1", mode = EditProposalMode.IMPROVE, operations = ops.toList(),
    )

    // ---------- as 3 decisões do botão (proposalApplyContent) ----------

    @Test
    fun secaoVazia_usaOProposto() {
        assertEquals(
            "novo",
            proposalApplyContent(
                oldContentHtml = "", proposedContentHtml = "novo",
                fullContentHtml = "novo", forceReplace = false,
            ),
        )
    }

    @Test
    fun secaoComConteudo_adicionaAoFinal() {
        assertEquals(
            "antigo\n\nnovo",
            proposalApplyContent("antigo", "novo", "novo", forceReplace = false),
        )
    }

    @Test
    fun substituirADescartaOAntigo() {
        assertEquals(
            "novo",
            proposalApplyContent("antigo", "novo", "novo", forceReplace = true),
        )
    }

    @Test
    fun propostaVazia_deleteOnly_aplicaOResultadoCompleto() {
        // Delete-only não tem "trecho novo" — as ops mandam (comportamento clássico).
        assertEquals(
            "vazio",
            proposalApplyContent("antigo", "", fullContentHtml = "vazio", forceReplace = false),
        )
    }

    // ---------- o delta das ops (proposedAdditionContent) ----------

    @Test
    fun deltaDeReplace_eOConteudoSubstitutoSemAcrescentarOAntigo() {
        val p = proposal(EditOperation.Replace("focus", "<p>novo</p>"))
        assertEquals("<p>novo</p>", proposedAdditionContent(p))
    }

    @Test
    fun deltaDeInsert_eApenasOTrechoInserido() {
        val p = proposal(
            EditOperation.Insert("focus", InsertPosition.AFTER, "<p>extra</p>")
        )
        assertEquals("<p>extra</p>", proposedAdditionContent(p))
    }

    @Test
    fun deltaDeDelete_vazio() {
        val p = proposal(EditOperation.Delete("focus"))
        assertTrue(proposedAdditionContent(p).isEmpty())
    }

    // ---------- aceite preserva o notice de fidelidade ----------

    @Test
    fun aceitePreservaONoticeDeFidelidade() {
        val ui = CopilotViewModel.ProposalUi(
            proposal = proposal(EditOperation.Replace("focus", "x")),
            focusText = "focus",
            notice = "removi uma frase que não tinha apoio nas fontes.",
        )
        val applied = appliedProposalUi(ui)
        assertTrue(applied.applied)
        assertEquals("removi uma frase que não tinha apoio nas fontes.", applied.notice)
    }
}
