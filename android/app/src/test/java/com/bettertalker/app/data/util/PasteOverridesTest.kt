package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1b-1a — overrides da prévia sem re-processar o texto bruto. */
class PasteOverridesTest {

    private val part5 =
        "5. Cultivando o interesse (4 min) DE CASA EM CASA. " +
            "Fale sobre uma das verdades do apêndice A (lmd lição 7 ponto 4)."

    private val treasuresBody = """
        Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)

        Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)

        Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
    """.trimIndent()

    private val talkText =
        "Ter esperança é o segredo para o contentamento\n\n(10 min)\n\n$treasuresBody"

    @Test
    fun titulo_semMinutos_substituiBaseEMantemDuracao() {
        val plan = planPaste(part5)
        val out = applyPasteOverrides(plan, PasteOverrides(noteTitle = "Novo título"))

        assertEquals("Novo título (4 min)", out.noteTitle)
        assertEquals(4, out.sections.single { it.role == SectionRole.BODY }.minutes)
    }

    @Test
    fun tituloComMinutosNoOverride_naoDuplicaSufixo() {
        val plan = planPaste(part5)
        val out = applyPasteOverrides(plan, PasteOverrides(noteTitle = "Novo (4 min)", minutes = 6))

        assertEquals("Novo (6 min)", out.noteTitle)
    }

    @Test
    fun tituloVazio_mantemBaseOriginal() {
        val plan = planPaste(part5)
        val out = applyPasteOverrides(plan, PasteOverrides(noteTitle = "   ", minutes = 6))

        assertEquals("Cultivando o interesse (6 min)", out.noteTitle)
    }

    @Test
    fun duracao_vmDemo_bodyRecebeTotal() {
        val plan = planPaste(part5)
        val out = applyPasteOverrides(plan, PasteOverrides(minutes = 6))

        assertEquals("Cultivando o interesse (6 min)", out.noteTitle)
        assertEquals(6, out.sections.single { it.role == SectionRole.BODY }.minutes)
        val kind = out.kind as PasteKind.VmDemo
        assertEquals(6, kind.part.minutes)
    }

    @Test
    fun duracao_vmTalk_redistribuiIntroCorposConclusao() {
        val plan = planPaste(talkText)
        assertTrue(plan.kind is PasteKind.VmTalk)
        val out = applyPasteOverrides(plan, PasteOverrides(minutes = 12))

        assertEquals("Ter esperança é o segredo para o contentamento (12 min)", out.noteTitle)
        assertEquals(12, out.sections.sumOf { it.minutes })
        assertEquals(1, out.sections.first { it.role == SectionRole.INTRO }.minutes)
        assertEquals(1, out.sections.last { it.role == SectionRole.CONCLUSION }.minutes)
        assertEquals(
            10,
            out.sections.filter { it.role == SectionRole.BODY }.sumOf { it.minutes },
        )
        val kind = out.kind as PasteKind.VmTalk
        assertEquals(12, kind.part.minutes)
    }

    @Test
    fun overrides_naoMudamTipoDeParte() {
        val demo = applyPasteOverrides(
            planPaste(part5),
            PasteOverrides(noteTitle = "X", minutes = 6),
        )
        assertTrue(demo.kind is PasteKind.VmDemo)

        val talk = applyPasteOverrides(
            planPaste(talkText),
            PasteOverrides(noteTitle = "Y", minutes = 12),
        )
        assertTrue(talk.kind is PasteKind.VmTalk)
    }

    @Test
    fun refBiblicaExcluida_saiDoPlanoEDasSecoes_masFicaNoTexto() {
        val plan = planPaste(talkText)
        val ref = plan.bibleRefs.first()
        val out = applyPasteOverrides(
            plan,
            PasteOverrides(excludedBibleRefs = setOf(ref)),
        )

        assertFalse(ref in out.bibleRefs)
        assertTrue(out.sections.none { ref in it.bibleRefs })
        assertEquals("o texto colado não é alterado", plan.plainText, out.plainText)
    }

    @Test
    fun refPublicacaoExcluida_saiDoPlanoEDasSecoes() {
        val plan = planPaste(part5)
        val ref = plan.publicationRefs.first()
        val key = publicationRefKey(ref)
        val out = applyPasteOverrides(
            plan,
            PasteOverrides(excludedPublicationRefs = setOf(key)),
        )

        assertTrue(out.publicationRefs.isEmpty())
        assertTrue(out.sections.none { it.publicationRefs.isNotEmpty() })
        assertTrue(out.plainText.contains("lmd"))
    }

    @Test
    fun toPastedNote_respeitaTituloDuracaoERefs() {
        val plan = planPaste(part5)
        val ref = plan.publicationRefs.first()
        val out = applyPasteOverrides(
            plan,
            PasteOverrides(
                noteTitle = "Novo",
                minutes = 6,
                excludedPublicationRefs = setOf(publicationRefKey(ref)),
            ),
        )
        val note = out.toPastedNote()

        assertEquals("Novo (6 min)", note.title)
        assertEquals(6, note.sections.single { it.role == SectionRole.BODY }.minutes)
        assertTrue(note.sections.single().publicationRefs.isEmpty())
    }

    @Test
    fun chaveERotuloDaRefDePublicacao() {
        val ref = PublicationRef(symbol = "lmd", paragraph = 4, chapter = "lição 7")
        assertEquals("lmd|§4|lição 7", publicationRefKey(ref))
        assertEquals("lmd lição 7 §4", publicationRefLabel(ref))
    }

    // ---------- 1b-1b: cenário/referência no título do BODY ----------

    @Test
    fun cenarioEditado_vaParaTituloDoBody() {
        val plan = planPaste(part5)
        assertEquals("DE CASA EM CASA", plan.scenario)
        val out = applyPasteOverrides(
            plan,
            PasteOverrides(scenario = "TESTEMUNHO INFORMAL"),
        )
        val note = out.toPastedNote()
        val body = note.sections.single { it.role == SectionRole.BODY }

        assertEquals("TESTEMUNHO INFORMAL", out.scenario)
        assertEquals(
            "TESTEMUNHO INFORMAL",
            (out.kind as PasteKind.VmDemo).part.scenario,
        )
        assertTrue(body.title.contains("TESTEMUNHO INFORMAL"))
        assertFalse(body.title.contains("DE CASA EM CASA"))
        assertEquals("TESTEMUNHO INFORMAL", vmScenarioOf(body.title))
        assertEquals("TESTEMUNHO INFORMAL", note.scenario)
    }

    @Test
    fun referenciaEditada_vaParaTituloDoBody() {
        val out = applyPasteOverrides(
            planPaste(part5),
            PasteOverrides(publicationReference = "lmd lição 9 ponto 2"),
        )
        val body = out.toPastedNote().sections.single { it.role == SectionRole.BODY }

        assertTrue(body.title.contains("lmd lição 9 ponto 2"))
        assertFalse(body.title.contains("lmd lição 7 ponto 4"))
        assertEquals("lmd lição 9 ponto 2", vmReferenceOf(body.title))
    }

    @Test
    fun limparCenario_removeDoTituloDoBody() {
        val out = applyPasteOverrides(
            planPaste(part5),
            PasteOverrides(scenario = ""),
        )
        val body = out.toPastedNote().sections.single { it.role == SectionRole.BODY }

        assertFalse(body.title.contains("DE CASA EM CASA"))
        assertTrue(body.title.contains("Fale sobre"))
    }

    @Test
    fun toPastedNote_semOverrides_preservaCenarioEReferencia() {
        val note = planPaste(part5).toPastedNote()
        val body = note.sections.single { it.role == SectionRole.BODY }

        assertTrue(body.title.contains("DE CASA EM CASA"))
        assertTrue(body.title.contains("lmd lição 7 ponto 4"))
        assertEquals("DE CASA EM CASA", vmScenarioOf(body.title))
        assertEquals("lmd lição 7 ponto 4", vmReferenceOf(body.title))
    }

    @Test
    fun toPastedNote_naoDuplicaMetadata() {
        val out = applyPasteOverrides(
            planPaste(part5),
            PasteOverrides(scenario = "TESTEMUNHO INFORMAL"),
        )
        val title = out.toPastedNote().sections
            .single { it.role == SectionRole.BODY }
            .title

        assertEquals(
            1,
            Regex("TESTEMUNHO INFORMAL", RegexOption.IGNORE_CASE)
                .findAll(title)
                .count(),
        )
    }

    @Test
    fun talk_recebeMetadataEmTodosOsBody() {
        val base = planPaste(talkText)
        assertTrue(base.kind is PasteKind.VmTalk)
        val plan = base.copy(
            scenario = "TESTEMUNHO INFORMAL",
            publicationReference = "lmd lição 7 ponto 4",
        )
        val bodies = plan.toPastedNote().sections.filter { it.role == SectionRole.BODY }

        assertTrue(bodies.isNotEmpty())
        assertTrue(bodies.all { it.title.contains("TESTEMUNHO INFORMAL") })
        assertTrue(bodies.all { it.title.contains("lmd lição 7 ponto 4") })
    }
}
