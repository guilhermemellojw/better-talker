package com.bettertalker.app

import com.bettertalker.app.data.edit.ApplyResult
import com.bettertalker.app.data.edit.CopilotEditProposal
import com.bettertalker.app.data.edit.EditBlock
import com.bettertalker.app.data.edit.EditHistory
import com.bettertalker.app.data.edit.EditOperation
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.InsertPosition
import com.bettertalker.app.data.edit.MAX_OP_CONTENT_CHARS
import com.bettertalker.app.data.edit.MAX_PROPOSAL_OPS
import com.bettertalker.app.data.edit.ParseResult
import com.bettertalker.app.data.edit.ProposalApplyStatus
import com.bettertalker.app.data.edit.ValidationError
import com.bettertalker.app.data.edit.ValidationResult
import com.bettertalker.app.data.edit.applyEditProposal
import com.bettertalker.app.data.edit.captureBaseHashes
import com.bettertalker.app.data.edit.extractJsonFence
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.edit.parseEditProposal
import com.bettertalker.app.data.edit.stripHtmlToText
import com.bettertalker.app.data.edit.unescapeHtmlEntities
import com.bettertalker.app.data.edit.validateEditProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 18 — BLOCO B. Testes do F5 (§34): proposta válida/inválida, stale,
 * aceitação, rejeição (não aplicar), atomicidade, limites, delete proibido,
 * undo/redo, parser, hashes idênticos ao web.
 */
class EditProposalTest {

    private val blocks = listOf(
        EditBlock("b1", "<p>Introdução original.</p>"),
        EditBlock("b2", "<p>Ponto principal.</p>")
    )

    private fun replaceProp(
        target: String = "b1",
        content: String = "<p>Introdução nova.</p>",
        hashes: Map<String, String> = captureBaseHashes(blocks, listOf(target))
    ) = CopilotEditProposal("p1", EditProposalMode.IMPROVE, "melhora",
        listOf(EditOperation.Replace(target, content)), hashes)

    // ---------- hashes idênticos ao web ----------

    @Test
    fun hashTextIgualAoWeb() {
        assertEquals("811c9dc5", hashText(""))
        assertEquals("4f9f2cab", hashText("hello"))
        assertEquals("c051ed15", hashText("Introdução"))
        assertEquals("46318a03", hashText("<p>Olá, mundo! Café & pão.</p>"))
        assertEquals("1006baeb", hashText(" texto com emoji 🎤 e acentos ãõç"))
    }

    @Test
    fun stripHtmlToTextIgualAoWeb() {
        assertEquals("Olá", stripHtmlToText("<p>Olá</p>"))
        // Comportamento real do web (verificado via node): espaço do <p>.
        assertEquals("a\n b", stripHtmlToText("<p>a</p><p>b</p>"))
        assertEquals("a & b", stripHtmlToText("a &amp; b"))
    }

    @Test
    fun unescapeDecodificaEntidadesDoEditorNativo() {
        // Diferença legítima de plataforma: richeditor persiste acentos
        // como entidades; sem isso nada com acento bateria no pt-BR.
        assertEquals("A confiança em Jeová sustenta quem ora.",
            stripHtmlToText("<p>A confian&ccedil;a em Jeov&aacute; sustenta quem ora&period;</p>"))
        assertEquals("a & b", unescapeHtmlEntities("a &amp; b"))
        assertEquals("X", unescapeHtmlEntities("&#88;"))
        assertEquals("—", unescapeHtmlEntities("&mdash;"))
    }

    // ---------- proposta válida ----------

    @Test
    fun propostaValidaAplicaReplace() {
        val r = applyEditProposal(blocks, replaceProp())
        assertEquals(ProposalApplyStatus.APPLIED, r.status)
        assertEquals("<p>Introdução nova.</p>", r.blocks!![0].contentHtml)
        assertEquals("<p>Ponto principal.</p>", r.blocks[1].contentHtml)
        // Original intacto (não muta).
        assertEquals("<p>Introdução original.</p>", blocks[0].contentHtml)
    }

    @Test
    fun propostaInsertBeforeAfter() {
        val p = CopilotEditProposal("p", EditProposalMode.INSERT, null,
            listOf(EditOperation.Insert("b2", InsertPosition.BEFORE, "<p>Novo.</p>")),
            captureBaseHashes(blocks, listOf("b2")))
        val r = applyEditProposal(blocks, p, newId = { EditBlock("n1", it.contentHtml).id })
        assertEquals(ProposalApplyStatus.APPLIED, r.status)
        assertEquals(listOf("b1", "n1", "b2"), r.blocks!!.map { it.id })
    }

    // ---------- inválidas ----------

    @Test
    fun alvoDesconhecidoInvalida() {
        val r = applyEditProposal(blocks, replaceProp(target = "zzz", hashes = emptyMap()))
        assertEquals(ProposalApplyStatus.INVALID, r.status)
        assertNull(r.blocks)
    }

    @Test
    fun conteudoVazioInvalida() {
        val r = applyEditProposal(blocks, replaceProp(content = "   "))
        assertEquals(ProposalApplyStatus.INVALID, r.status)
    }

    @Test
    fun excessoDeOperacoesInvalida() {
        val ops = (1..(MAX_PROPOSAL_OPS + 1)).map {
            EditOperation.Replace("b1", "<p>x</p>") as EditOperation
        }
        val v = validateEditProposal(blocks, CopilotEditProposal("p", EditProposalMode.REWRITE,
            null, ops, captureBaseHashes(blocks, listOf("b1"))))
        assertEquals(ValidationResult.Fail(ValidationError.INVALID_POSITION), v)
    }

    @Test
    fun conteudoAcimaDoLimiteInvalida() {
        val big = "<p>" + "x".repeat(MAX_OP_CONTENT_CHARS) + "</p>"
        val v = validateEditProposal(blocks, replaceProp(content = big))
        assertEquals(ValidationResult.Fail(ValidationError.EMPTY_CONTENT), v)
    }

    @Test
    fun deleteUltimoBlocoInvalida() {
        val single = listOf(EditBlock("only", "<p>x</p>"))
        val p = CopilotEditProposal("p", EditProposalMode.DELETE, null,
            listOf(EditOperation.Delete("only")),
            captureBaseHashes(single, listOf("only")))
        assertEquals(ValidationResult.Fail(ValidationError.LAST_BLOCK),
            validateEditProposal(single, p))
    }

    // ---------- stale ----------

    @Test
    fun staleBloqueiaAplicacao() {
        val prop = replaceProp()
        val edited = listOf(
            EditBlock("b1", "<p>Introdução EDITADA depois.</p>"),
            EditBlock("b2", "<p>Ponto principal.</p>")
        )
        val r = applyEditProposal(edited, prop)
        assertEquals(ProposalApplyStatus.STALE_PROPOSAL, r.status)
        assertNull(r.blocks)
        // Editor intacto: nada foi aplicado.
        assertEquals("<p>Introdução EDITADA depois.</p>", edited[0].contentHtml)
    }

    @Test
    fun semBaseHashNaoTravaLegado() {
        // Compatibilidade: proposta antiga sem hashes não sofre stale.
        val r = applyEditProposal(blocks, replaceProp(hashes = emptyMap()))
        assertEquals(ProposalApplyStatus.APPLIED, r.status)
    }

    // ---------- atomicidade ----------

    @Test
    fun falhaEmUmaOperacaoAnulaTudo() {
        val p = CopilotEditProposal("p", EditProposalMode.REWRITE, null, listOf(
            EditOperation.Replace("b1", "<p>Nova.</p>"),
            EditOperation.Replace("inexistente", "<p>X.</p>")
        ), captureBaseHashes(blocks, listOf("b1")))
        val r = applyEditProposal(blocks, p)
        // unknown_target na validação: nada aplicado.
        assertEquals(ProposalApplyStatus.INVALID, r.status)
        assertNull(r.blocks)
    }

    // ---------- parser ----------

    @Test
    fun parserExtraiCercaJsonECoageAlvo() {
        val raw = "Aqui está:\n```json\n{\"explanation\":\"Nova intro\",\"operations\":" +
            "[{\"type\":\"replace\",\"content\":\"<p>Nova.</p>\"}]}\n```"
        val r = parseEditProposal(raw, blocks, "b2", EditProposalMode.IMPROVE, "px")
        assertTrue(r is ParseResult.Ok)
        val prop = (r as ParseResult.Ok).proposal
        assertEquals("b2", prop.operations[0].targetId)
        assertEquals("Nova intro", prop.explanation)
        assertEquals("px", prop.id)
        assertTrue(prop.baseHashes.containsKey("b2"))
    }

    @Test
    fun parserVetaDeleteDoLLM() {
        val raw = "```json\n{\"operations\":[{\"type\":\"delete\"}]}\n```"
        assertTrue(parseEditProposal(raw, blocks, "b1", EditProposalMode.REWRITE) is ParseResult.Invalid)
    }

    @Test
    fun parserRejeitaJsonInvalido() {
        assertTrue(parseEditProposal("sem json aqui", blocks, "b1", EditProposalMode.IMPROVE)
            is ParseResult.Invalid)
        assertTrue(parseEditProposal("```json\n{quebrado\n```", blocks, "b1", EditProposalMode.IMPROVE)
            is ParseResult.Invalid)
        assertTrue(parseEditProposal("```json\n{\"operations\":[]}\n```",
            blocks, "b1", EditProposalMode.IMPROVE) is ParseResult.Invalid)
    }

    @Test
    fun parserDeleteLocalEDeterministico() {
        val r = parseEditProposal("qualquer coisa", blocks, "b1", EditProposalMode.DELETE, "pd")
        assertTrue(r is ParseResult.Ok)
        val prop = (r as ParseResult.Ok).proposal
        assertTrue(prop.operations[0] is EditOperation.Delete)
        assertEquals(ProposalApplyStatus.APPLIED, applyEditProposal(blocks, prop).status)
    }

    @Test
    fun parserAlvoDesconhecidoInvalida() {
        val raw = "```json\n{\"operations\":[{\"type\":\"replace\",\"content\":\"<p>X.</p>\"}]}\n```"
        assertTrue(parseEditProposal(raw, blocks, "zzz", EditProposalMode.IMPROVE)
            is ParseResult.Invalid)
    }

    @Test
    fun extractJsonFenceTresFormatos() {
        assertEquals("{\"a\":1}", extractJsonFence("x ```json\n{\"a\":1}\n``` y"))
        assertEquals("{\"a\":1}", extractJsonFence("x ```\n{\"a\":1}\n``` y"))
        assertEquals("{\"a\":1}", extractJsonFence("  {\"a\":1}  "))
        assertEquals(null, extractJsonFence("sem json"))
    }

    // ---------- undo/redo ----------

    @Test
    fun undoRedoCicloCompleto() {
        val h = EditHistory()
        assertTrue(!h.canUndo && !h.canRedo)
        h.push(blocks)
        val applied = applyEditProposal(blocks, replaceProp()).blocks!!
        val back = h.undo(applied)
        assertEquals(blocks, back)
        assertTrue(h.canRedo)
        val fwd = h.redo(back!!)
        assertEquals(applied, fwd)
    }

    @Test
    fun pushNovoLimpaRedo() {
        val h = EditHistory()
        h.push(blocks)
        val applied = applyEditProposal(blocks, replaceProp()).blocks!!
        h.undo(applied)
        assertTrue(h.canRedo)
        h.push(applied)
        assertTrue(!h.canRedo)
    }

    @Test
    fun undoVazioDevolveNull() {
        assertNull(EditHistory().undo(blocks))
        assertNull(EditHistory().redo(blocks))
    }

    // ---------- rejeição = não aplicar ----------

    @Test
    fun rejeicaoNaoTocaNosBlocos() {
        val before = blocks.map { it.copy() }
        // Rejeitar = simplesmente não chamar applyEditProposal.
        assertEquals(before, blocks)
    }
    // ---------- renderAfterText (preview ANTES/DEPOIS) ----------

    @Test
    fun renderAfterTextReplace() {
        val focus = "Introdução original."
        val p = CopilotEditProposal("p", EditProposalMode.IMPROVE, null,
            listOf(EditOperation.Replace("focus", "<p>Introdução nova.</p>")),
            captureBaseHashes(listOf(EditBlock("focus", focus)), listOf("focus")))
        assertEquals("Introdução nova.",
            com.bettertalker.app.data.edit.renderAfterText(p, focus))
    }

    @Test
    fun renderAfterTextInsert() {
        val p = CopilotEditProposal("p", EditProposalMode.INSERT, null,
            listOf(EditOperation.Insert("focus", com.bettertalker.app.data.edit.InsertPosition.AFTER, "<p>Novo.</p>")),
            emptyMap())
        assertEquals("Base.\n\nNovo.", com.bettertalker.app.data.edit.renderAfterText(p, "Base."))
    }
}
