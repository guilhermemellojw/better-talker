package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatRouter
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.ui.editor.DraftTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FALTA-2 — proposta de ESTRUTURA da seção via OutlineProposer: alvo,
 * request (escopo por seção), escolha do plano, aplicação (merge/dedupe/
 * concatenação), gate de stale por hash e reconhecimento do comando.
 * Tudo puro — o VM só executa a decisão.
 */
class StructureProposalTest {

    private fun section(
        title: String = "Fé que age",
        minutes: Int = 5,
        contentHtml: String = "<p>texto atual da seção</p>",
        bibleRefs: List<String> = emptyList(),
        pubRefs: List<PublicationRef> = emptyList(),
        approach: String? = null,
    ) = SpeechSection(
        id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = title, minutes = minutes, contentHtml = contentHtml,
        bibleRefs = bibleRefs, publicationRefs = pubRefs,
        methodPrinciple = null, approvedAt = null,
        createdAt = 1L, updatedAt = 1L,
    ).copy(agreedApproach = approach)

    private fun plan(
        title: String = "Fé que age",
        minutes: Int = 7,
        mainIdea: String = "Mostrar que o desejo de viver é legítimo.",
        bibleRefs: List<String> = listOf("Ec 3:11"),
        pubRefs: List<PublicationRef> = emptyList(),
    ) = SectionPlan(
        title = title, minutes = minutes, mainIdea = mainIdea,
        bibleRefs = bibleRefs, publicationRefs = pubRefs, methodPrinciple = null,
    )

    private fun proposal(vararg plans: SectionPlan) = OutlineProposal(
        id = "p1", angle = OutlineAngle.PRACTICAL, audience = Audience.GENERAL,
        title = "Discurso", summary = "Resumo da proposta.", totalMinutes = 5,
        sections = plans.toList(),
    )

    // ---------- 2. alvo precisa ser seção ----------

    @Test
    fun alvo_secaoOk_subpontoEOrientaFocar() {
        assertNull(structureTargetError(DraftTarget.Section("s1")))
        assertEquals(
            STRUCTURE_NEEDS_SECTION_MESSAGE,
            structureTargetError(DraftTarget.SubPoint("s1", "sp1")),
        )
        assertEquals(STRUCTURE_NEEDS_SECTION_MESSAGE, structureTargetError(null))
        assertTrue(STRUCTURE_NEEDS_SECTION_MESSAGE.contains("foque numa seção"))
    }

    // ---------- 3. request: escopo por seção ----------

    @Test
    fun request_mapeiaSecaoParaGeracao() {
        val r = buildStructureRequest(
            section(minutes = 4, bibleRefs = listOf("Ec 3:11"), pubRefs = listOf(PublicationRef("lff"))),
            methodPrinciples = listOf("Seja específico"),
            id = "req-1",
        )

        assertEquals("req-1", r.id)
        assertEquals("Fé que age", r.theme)
        assertEquals(4, r.totalMinutes)
        assertEquals(Audience.GENERAL, r.audience)
        assertEquals(OutlineAngle.PRACTICAL, r.angle)
        assertEquals(listOf("Ec 3:11"), r.bibleRefs)
        assertEquals(listOf(PublicationRef("lff")), r.publicationRefs)
        assertEquals(listOf("Seja específico"), r.methodPrinciples)
    }

    // ---------- escolha do plano da seção focada ----------

    @Test
    fun pick_casaTitulo_caiPrimeiroEBarreVazia() {
        val p = proposal(
            plan(title = "Tópico 2 — Obras"),
            plan(title = "Fé que age"),
        )
        assertEquals("Fé que age", pickSectionPlan(p, "Fé que age")?.title)
        assertEquals("Tópico 2 — Obras", pickSectionPlan(p, "Título inexistente")?.title)
        assertNull(pickSectionPlan(proposal(), "Fé"))
    }

    // ---------- 3/6/7. aplicação: campos, concatenação e dedupe ----------

    @Test
    fun apply_escreveObjetivoMinutosRefsEAbordagem() {
        val updated = applyStructurePlan(
            section(minutes = 5, bibleRefs = listOf("Gên 1:26")),
            plan(minutes = 7, mainIdea = "Ideia central nova.", bibleRefs = listOf("Ec 3:11"),
                pubRefs = listOf(PublicationRef("lff", page = 5))),
            summary = "Abordagem proposta.",
            now = 777L,
        )

        assertEquals("Ideia central nova.", updated.objective)
        assertEquals(7, updated.minutes)
        assertEquals(listOf("Gên 1:26", "Ec 3:11"), updated.bibleRefs)
        assertEquals(listOf(PublicationRef("lff", page = 5)), updated.publicationRefs)
        assertEquals("Abordagem proposta.", updated.agreedApproach)
        assertEquals(777L, updated.updatedAt)
        // Aprovação é passo separado (#3a) — não é tocada.
        assertNull(updated.approvedAt)
    }

    @Test
    fun apply_concatenaAbordagemExistenteComDuasQuebras() {
        val updated = applyStructurePlan(
            section(approach = "Abordagem antiga"),
            plan(), summary = "Abordagem nova",
        )
        assertEquals("Abordagem antiga\n\nAbordagem nova", updated.agreedApproach)
    }

    @Test
    fun apply_dedupeRefsJaPresentes() {
        val updated = applyStructurePlan(
            section(bibleRefs = listOf("Ec 3:11")),
            plan(bibleRefs = listOf("Ec 3:11", "Gên 1:26")),
            summary = "s",
        )
        assertEquals(listOf("Ec 3:11", "Gên 1:26"), updated.bibleRefs)
    }

    // ---------- 4. stale por hash ----------

    @Test
    fun decide_hashDiferente_bloqueia() {
        val current = section(contentHtml = "<p>mudou depois</p>")
        val d = decideStructureAccept(
            current, sectionBaseHash = "hash-antigo", plan = plan(), summary = "s",
        )
        assertEquals(StructureAcceptDecision.Stale, d)
    }

    @Test
    fun decide_hashIgual_aplica() {
        val current = section()
        val base = hashText(sectionProposalBlock(current.title, current.contentHtml))
        val d = decideStructureAccept(current, base, plan(minutes = 6), summary = "s", now = 9L)

        assertTrue(d is StructureAcceptDecision.Apply)
        val updated = (d as StructureAcceptDecision.Apply).updated
        assertEquals(6, updated.minutes)
        assertEquals(9L, updated.updatedAt)
    }

    // ---------- comando do roteador ----------

    @Test
    fun comando_reconheceFormasEExcluiArmadilhas() {
        assertTrue(ChatRouter.isProposeStructureCommand("propõe estrutura para esta seção"))
        assertTrue(ChatRouter.isProposeStructureCommand("propõe o plano"))
        assertTrue(ChatRouter.isProposeStructureCommand("planeja a seção"))
        assertTrue(ChatRouter.isProposeStructureCommand("define o objetivo desta seção"))
        assertTrue(ChatRouter.isProposeStructureCommand("defina o objetivo"))
        assertTrue(ChatRouter.isProposeStructureCommand("que textos usar nesta seção"))
        assertTrue(ChatRouter.isProposeStructureCommand("que textos usar", focused = true))
        // Armadilhas: sem verbo, sem substantivo, ou texto singular sem escopo.
        assertFalse(ChatRouter.isProposeStructureCommand("qual a estrutura do discurso"))
        assertFalse(ChatRouter.isProposeStructureCommand("sugere uma ilustração"))
        assertFalse(ChatRouter.isProposeStructureCommand("como usar este texto", focused = true))
        assertFalse(ChatRouter.isProposeStructureCommand("use esta seção como introdução"))
        assertFalse(ChatRouter.isProposeStructureCommand("explique a estrutura do discurso"))
    }
}
