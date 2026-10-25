package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1b-3 — formatos reais da apostila SEM número de parte.
 *
 * O texto real copiado pelo usuário não traz "5."; o parser precisa entrar
 * no caminho V&M pela whitelist de títulos, não pelo número.
 */
class VmRealFormatsTest {

    private fun plan(text: String): PastePlan = planPaste(text)

    private fun bodyTitles(plan: PastePlan): List<String> =
        plan.toPastedNote().sections
            .filter { it.role == SectionRole.BODY }
            .map { it.title }

    @Test
    fun iniciandoConversasSemNumero_detectaDemoComCenarioERef() {
        val p = plan(
            "Iniciando conversas (3 min) TESTEMUNHO INFORMAL. Comece a conversar " +
                "com alguém que parece estar feliz. (lmd lição 2 ponto 3)"
        )
        assertTrue(p.kind is PasteKind.VmDemo)
        assertEquals(DiscourseType.MINISTRY_PART, p.discourseType)
        assertEquals("Iniciando conversas (3 min)", p.noteTitle)
        assertEquals("TESTEMUNHO INFORMAL", p.scenario)
        assertEquals("lmd lição 2 ponto 3", p.publicationReference)
        val body = p.sections.single { it.role == SectionRole.BODY }
        assertEquals(3, body.minutes)
        assertTrue(body.title.contains("TESTEMUNHO INFORMAL"))
        assertTrue(body.title.contains("lmd lição 2 ponto 3"))
        assertTrue(body.contentHtml.isEmpty())
    }

    @Test
    fun discursoSemNumero_detectaTalkComTemaERefDaParte() {
        val p = plan(
            "Discurso (4 min) lmd apêndice A ponto 20 — Tema: Jesus não é o Deus " +
                "Todo-Poderoso. (th lição 7)"
        )
        assertTrue(p.kind is PasteKind.VmTalk)
        assertEquals(DiscourseType.MINISTRY_TALK, p.discourseType)
        assertEquals("Jesus não é o Deus Todo-Poderoso (4 min)", p.noteTitle)
        assertEquals("lmd apêndice A ponto 20", p.publicationReference)
        assertEquals(4, p.sections.sumOf { it.minutes })
        assertEquals(1, p.sections.first { it.role == SectionRole.INTRO }.minutes)
        assertEquals(1, p.sections.last { it.role == SectionRole.CONCLUSION }.minutes)
        val body = bodyTitles(p).single()
        assertTrue(body.contains("Jesus não é o Deus Todo-Poderoso"))
        assertTrue(body.contains("lmd apêndice A ponto 20"))
        assertTrue(
            "a ref de técnica (th) continua como fonte",
            p.publicationRefs.any { it.symbol == "th" },
        )
    }

    @Test
    fun cultivandoSemNumero_detectaDemoComCenarioERef() {
        val p = plan(
            "Cultivando o interesse (4 min) DE CASA EM CASA. Fale sobre a " +
                "importância de iniciar conversas amigáveis. (lmd lição 7 ponto 4)"
        )
        assertTrue(p.kind is PasteKind.VmDemo)
        assertEquals(DiscourseType.MINISTRY_PART, p.discourseType)
        assertEquals("DE CASA EM CASA", p.scenario)
        assertEquals("lmd lição 7 ponto 4", p.publicationReference)
    }

    @Test
    fun oQueVoceDiriaSemNumero_eRejeitada() {
        val p = plan(
            "O que você diria? (5 min) CONSIDERAÇÃO. Como você responderia a " +
                "alguém que diz: \"Não tenho tempo para estudar a Bíblia\"? (lmd lição 9)"
        )
        assertTrue(p.kind is PasteKind.VmRejected)
        assertTrue(p.sections.isEmpty())
        assertTrue(p.notice.orEmpty().contains("não é suportada"))
    }

    @Test
    fun fazendoDiscipulosSemNumero_detectaDemoComEstudoBiblico() {
        val p = plan(
            "Fazendo discípulos (5 min) ESTUDO BÍBLICO. Demonstre como ajudar a " +
                "pessoa a progredir rumo ao batismo. (lmd lição 12 ponto 2)"
        )
        assertTrue(p.kind is PasteKind.VmDemo)
        assertEquals("ESTUDO BÍBLICO", p.scenario)
        assertEquals("lmd lição 12 ponto 2", p.publicationReference)
    }

    @Test
    fun explicandoCrencasSemNumero_mantemDemoPorEnquanto() {
        // Ambiguidade talk/demo fica para a 1b-2; o default histórico é DEMO.
        val p = plan(
            "Explicando suas crenças (4 min) Tema: Por que acreditamos que o " +
                "Reino de Deus é um governo real?"
        )
        assertTrue(p.kind is PasteKind.VmDemo)
        assertEquals(DiscourseType.MINISTRY_PART, p.discourseType)
    }

    @Test
    fun joiasLeituraEstudoSemNumero_viramVmRejected() {
        val cases = listOf(
            "Joias espirituais (10 min) Perguntas: 1. O que aprendemos com a fé " +
                "de Noé? 2. Como aplicar?",
            "Leitura da Bíblia (4 min) Leia Gênesis 1:1-5.",
            "Estudo Bíblico de Congregação (30 min) Apostila: ...",
        )
        for (text in cases) {
            val p = plan(text)
            assertTrue("esperado VmRejected: $text", p.kind is PasteKind.VmRejected)
            assertTrue(
                "mensagem específica: ${p.notice}",
                p.notice.orEmpty().contains("nenhuma das partes"),
            )
        }
    }

    @Test
    fun textoLivreComMinutosERef_continuaFreeText() {
        val p = plan("Minha nota (4 min) lmd lição 2 ponto 3")
        assertTrue(p.kind is PasteKind.FreeText)
    }

    @Test
    fun notaTituladaDiscursoComTema_naoSequestra() {
        val p = plan("Discurso: Seja corajoso (5 min) Corpo do discurso.")
        assertTrue(p.kind is PasteKind.FreeText)
    }

    @Test
    fun referenciaLmdApendice_eReconhecida() {
        assertEquals(
            "lmd apêndice A ponto 20",
            vmReferenceOf("lmd apêndice A ponto 20"),
        )
        assertEquals("ESTUDO BÍBLICO", vmScenarioOf("ESTUDO BÍBLICO. Demonstre."))
    }
}
