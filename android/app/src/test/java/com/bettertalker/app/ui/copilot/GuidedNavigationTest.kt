package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modo guiado seção-a-seção: navegação pura (decisão de destino e rótulos).
 * #25-fix-c: SEM gate de aprovação — nextSection avança sempre (approvedAt
 * virou legado). O VM só executa a decisão — tudo que decide vive aqui,
 * testável sem Android/Room.
 */
class GuidedNavigationTest {

    private val three = listOf("s1", "s2", "s3")

    // ---------- 1. NEXT avança (sem gate) ----------

    @Test
    fun next_avancaParaAProxima() {
        val t = resolveNavTarget(three, "s1", GuideNavDirection.NEXT)
        assertEquals(GuideNavTarget.MoveTo("s2"), t)
    }

    // ---------- 2. última não tem próxima ----------

    @Test
    fun next_naUltima_naoHaProxima() {
        val t = resolveNavTarget(three, "s3", GuideNavDirection.NEXT)
        assertEquals(GuideNavTarget.NoNext, t)
        assertEquals("Não há próxima seção.", GUIDE_NO_NEXT_MESSAGE)
    }

    // ---------- 3. primeira não tem anterior ----------

    @Test
    fun prev_naPrimeira_naoHaAnterior() {
        val t = resolveNavTarget(three, "s1", GuideNavDirection.PREV)
        assertEquals(GuideNavTarget.NoPrev, t)
        assertEquals("Não há seção anterior.", GUIDE_NO_PREV_MESSAGE)
    }

    // ---------- 4. PREV volta ----------

    @Test
    fun prev_voltaParaAAnterior() {
        val t = resolveNavTarget(three, "s3", GuideNavDirection.PREV)
        assertEquals(GuideNavTarget.MoveTo("s2"), t)
    }

    // ---------- extra: sem foco e sem seções ----------

    @Test
    fun next_semFoco_comecaPelaPrimeira() {
        val t = resolveNavTarget(three, null, GuideNavDirection.NEXT)
        assertEquals(GuideNavTarget.MoveTo("s1"), t)
    }

    @Test
    fun semSecoes_orientaAbrirTopico() {
        val t = resolveNavTarget(emptyList(), null, GuideNavDirection.NEXT)
        assertEquals(GuideNavTarget.NoSections, t)
        assertEquals("Abra um tópico no editor para navegar entre seções.", GUIDE_NO_FOCUS_MESSAGE)
    }

    // ---------- 5. anúncio de troca de escopo ----------

    @Test
    fun anuncio_deTrocaDeEscopo() {
        assertEquals(
            "— Tópico 2 de 5: Como viver para sempre (8 min) —",
            guideAnnounceLabel(index = 1, total = 5, title = "Como viver para sempre", minutes = 8),
        )
    }

    // ---------- 6. comandos de texto ----------

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
