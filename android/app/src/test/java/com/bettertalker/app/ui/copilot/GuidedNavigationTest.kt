package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modo guiado seção-a-seção: navegação pura (decisão de destino, gate de
 * aprovação e rótulos). O VM só executa a decisão — tudo que decide vive
 * aqui, testável sem Android/Room.
 */
class GuidedNavigationTest {

    private val three = listOf("s1", "s2", "s3")

    // ---------- 1. gate: não avança sem aprovação ----------

    @Test
    fun next_semAprovacao_bloqueiaEPedeAprovar() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = "s1",
            direction = GuideNavDirection.NEXT, force = false, approvedIds = emptySet(),
        )
        assertEquals(GuideNavTarget.BlockedNeedsApproval, t)
        assertTrue(
            "mensagem deve orientar a aprovar",
            GUIDE_NEEDS_APPROVAL_MESSAGE.lowercase().contains("aprov"),
        )
    }

    // ---------- 2. aprovada avança ----------

    @Test
    fun next_comAprovacao_avancaParaAProxima() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = "s1",
            direction = GuideNavDirection.NEXT, force = false, approvedIds = setOf("s1"),
        )
        assertEquals(GuideNavTarget.MoveTo("s2"), t)
    }

    // ---------- 3. forçar ignora o gate ----------

    @Test
    fun next_forcado_avancaSemAprovacao() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = "s1",
            direction = GuideNavDirection.NEXT, force = true, approvedIds = emptySet(),
        )
        assertEquals(GuideNavTarget.MoveTo("s2"), t)
    }

    // ---------- 4. última não tem próxima ----------

    @Test
    fun next_naUltima_naoHaProxima() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = "s3",
            direction = GuideNavDirection.NEXT, force = true, approvedIds = setOf("s3"),
        )
        assertEquals(GuideNavTarget.NoNext, t)
        assertEquals("Não há próxima seção.", GUIDE_NO_NEXT_MESSAGE)
    }

    // ---------- 5. primeira não tem anterior ----------

    @Test
    fun prev_naPrimeira_naoHaAnterior() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = "s1",
            direction = GuideNavDirection.PREV, force = false, approvedIds = emptySet(),
        )
        assertEquals(GuideNavTarget.NoPrev, t)
        assertEquals("Não há seção anterior.", GUIDE_NO_PREV_MESSAGE)
    }

    // ---------- 6. anterior sempre permite ----------

    @Test
    fun prev_semprePermite_mesmoSemAprovacao() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = "s3",
            direction = GuideNavDirection.PREV, force = false, approvedIds = emptySet(),
        )
        assertEquals(GuideNavTarget.MoveTo("s2"), t)
    }

    // ---------- extra: sem foco e sem seções ----------

    @Test
    fun next_semFoco_comecaPelaPrimeira() {
        val t = resolveNavTarget(
            orderedIds = three, currentId = null,
            direction = GuideNavDirection.NEXT, force = false, approvedIds = emptySet(),
        )
        assertEquals(GuideNavTarget.MoveTo("s1"), t)
    }

    @Test
    fun semSecoes_orientaAbrirTopico() {
        val t = resolveNavTarget(
            orderedIds = emptyList(), currentId = null,
            direction = GuideNavDirection.NEXT, force = false, approvedIds = emptySet(),
        )
        assertEquals(GuideNavTarget.NoSections, t)
    }

    // ---------- 7. indicador ----------

    @Test
    fun indicador_focoNaTercaDeCinco() {
        val label = guideStripLabel(index = 2, total = 5, title = "Como viver para sempre", minutes = 5)
        assertEquals("Tópico 3 de 5 — Como viver para sempre (5 min)", label)
    }

    @Test
    fun anuncio_deTrocaDeEscopo() {
        assertEquals(
            "— Tópico 2 de 5: Como viver para sempre (8 min) —",
            guideAnnounceLabel(index = 1, total = 5, title = "Como viver para sempre", minutes = 8),
        )
    }

    // ---------- 8. comandos de texto ----------

    @Test
    fun comandos_deNavegacao() {
        assertTrue(isNextCommand("próximo"))
        assertTrue(isNextCommand("pode avançar"))
        assertTrue(isNextCommand("o seguinte"))
        assertFalse(isNextCommand("voltar"))
        // A classificação é textual (contém); o guard de escopo — só
        // interceptar com tópico em foco — vive no `resolveSectionNav` do VM.
        assertTrue(isNextCommand("qual o próximo passo do esboço?"))
    }

    @Test
    fun comandos_forcarEPular() {
        assertTrue(isForceNextCommand("forçar próximo"))
        assertTrue(isForceNextCommand("pular"))
        assertFalse(isForceNextCommand("próximo"))
    }

    @Test
    fun comandos_voltar() {
        assertTrue(isPrevCommand("anterior"))
        assertTrue(isPrevCommand("voltar"))
        assertFalse(isPrevCommand("próximo"))
    }

    @Test
    fun comandos_aprovar_eDesaprovar() {
        // "aprovar"/"aprovado" aprovam; "desaprovar" NUNCA.
        assertTrue(isApproveCommand("aprovar seção"))
        assertTrue(isApproveCommand("ok, aprovado"))
        assertFalse(isApproveCommand("desaprovar seção"))
        assertFalse(isApproveCommand("próximo"))
    }
}
