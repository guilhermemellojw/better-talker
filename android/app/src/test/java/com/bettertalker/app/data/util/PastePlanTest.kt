package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.GenerationProfile
import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase A — cérebro único da colagem: mesmo texto pelo caminho com/sem
 * cabeçalho, variantes de minutos, múltiplas partes, refs, personas e
 * classificação da parte 6.
 */
class PastePlanTest {

    private val part5 =
        "5. Cultivando o interesse (4 min) DE CASA EM CASA. " +
            "Fale sobre uma das verdades do apêndice A (lmd lição 7 ponto 4)."

    private val header = "FAÇA SEU MELHOR NO MINISTÉRIO"

    @Test
    fun cabecalhoVsSolto_mesmoTituloCorpoCenarioEReferencia() {
        val loose = detectVmMaterial(part5).single()
        val withHeader = detectVmMaterial("$header\n$part5").single()

        assertEquals(loose.title, withHeader.title)
        assertEquals("Cultivando o interesse", withHeader.title)
        assertEquals(loose.body, withHeader.body)
        assertEquals(loose.scenario, withHeader.scenario)
        assertEquals("DE CASA EM CASA", withHeader.scenario)
        assertEquals(loose.reference, withHeader.reference)
        assertEquals("lmd lição 7 ponto 4", withHeader.reference)
        assertEquals(4, withHeader.minutes)
        assertEquals(5, withHeader.partNumber)
    }

    @Test
    fun cabecalhoVsSolto_mesmaEstruturaDeCardsEPersona() {
        val loose = planPaste(part5)
        val withHeader = planPaste("$header\n$part5")

        assertEquals(loose.discourseType, withHeader.discourseType)
        assertEquals(loose.noteTitle, withHeader.noteTitle)
        assertEquals(loose.generationProfile, withHeader.generationProfile)
        assertEquals(GenerationProfile.DEMO_SCRIPTWRITER, withHeader.generationProfile)
        assertEquals(loose.sections.size, withHeader.sections.size)
        assertEquals(loose.sections.map { it.title }, withHeader.sections.map { it.title })
        assertEquals(loose.sections.map { it.minutes }, withHeader.sections.map { it.minutes })
        assertEquals(loose.bibleRefs, withHeader.bibleRefs)
        assertEquals(loose.publicationRefs, withHeader.publicationRefs)
        // A ref de publicação (lmd) é extraída e persistida no card BODY.
        assertFalse(withHeader.publicationRefs.isEmpty())
        assertEquals("lmd", withHeader.publicationRefs.first().symbol)
        assertTrue(withHeader.sections.first().contentHtml.isEmpty())
    }

    @Test
    fun variantesDeMinutos_saoReconhecidas() {
        val variants = listOf(
            "5. Cultivando o interesse (4 min.) DE CASA EM CASA",
            "5. Cultivando o interesse 4 min DE CASA EM CASA",
            "5. Cultivando o interesse\n(4 min)\nDE CASA EM CASA",
            "• 5. Cultivando o interesse (4 min) DE CASA EM CASA",
            "5.\u00A0Cultivando o interesse (4 min) DE CASA EM CASA",
        )
        variants.forEach { text ->
            val p = detectVmMaterial(text).single()
            assertEquals(text, 4, p.minutes)
            assertEquals(text, "Cultivando o interesse", p.title)
            assertEquals(text, VmNature.DEMO, p.nature)
        }
    }

    @Test
    fun duasPartesSoltas_retornaAmbasENaoDescartaEmSilencio() {
        val text = """
            4. Iniciando conversas (3 min) DE CASA EM CASA
            Fale sobre uma notícia local.

            5. Cultivando o interesse (4 min) TESTEMUNHO INFORMAL
            Retome a conversa anterior.
        """.trimIndent()

        val parts = detectVmMaterial(text)
        assertEquals(2, parts.size)
        assertEquals(listOf(4, 5), parts.map { it.partNumber })
        assertEquals(listOf(VmNature.DEMO, VmNature.DEMO), parts.map { it.nature })

        val plan = planPaste(text)
        assertEquals(GenerationProfile.DEMO_SCRIPTWRITER, plan.generationProfile)
        assertEquals(1, plan.ignoredParts.size)
        assertEquals(5, plan.ignoredParts.single().partNumber)
        assertTrue(plan.notice.orEmpty().contains("Ignorei"))
        assertTrue(plan.notice.orEmpty().contains("parte 5"))
    }

    @Test
    fun apostilaSolta_partes5e7_retornaAmbasEPriorizaTalk() {
        val text = """
            5. Cultivando o interesse (4 min) DE CASA EM CASA
            Fale sobre a esperança.

            7. Faça discípulos (15 min)
            Corpo do discurso.
        """.trimIndent()

        val parts = detectVmMaterial(text)
        assertEquals(listOf(5, 7), parts.map { it.partNumber })
        assertEquals(listOf(VmNature.DEMO, VmNature.TALK), parts.map { it.nature })

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmTalk)
        assertEquals(7, (plan.kind as PasteKind.VmTalk).part.partNumber)
        assertEquals(5, plan.ignoredParts.single().partNumber)
        assertTrue(plan.notice.orEmpty().contains("parte 5"))
    }

    @Test
    fun parte6_discurso_viraMinistryTalkComPersonaDeMentor() {
        val text = """
            FAÇA SEU MELHOR NO MINISTÉRIO

            6. Discurso: Seja corajoso (5 min)

            Corpo do discurso.
        """.trimIndent()

        val plan = planPaste(text)
        assertEquals(DiscourseType.MINISTRY_TALK, plan.discourseType)
        assertEquals(GenerationProfile.SPEECH_MENTOR, plan.generationProfile)
        assertEquals(3, plan.sections.size)
        assertEquals(
            listOf("Introdução", "Discurso: Seja corajoso", "Conclusão"),
            plan.sections.map { it.title },
        )
    }

    @Test
    fun demo_continuaMinistryPartComPersonaDeRoteirista() {
        val text = """
            FAÇA SEU MELHOR NO MINISTÉRIO
            5. Cultivando o interesse (4 min)
            Publicador: Olá, tudo bem?
            Morador: Tudo bem.
            Publicador: Gostaria de conversar sobre a Bíblia.
        """.trimIndent()

        val plan = planPaste(text)
        assertEquals(DiscourseType.MINISTRY_PART, plan.discourseType)
        assertEquals(GenerationProfile.DEMO_SCRIPTWRITER, plan.generationProfile)
        assertEquals(1, plan.sections.size)
        assertTrue(plan.sections.single().contentHtml.contains("<b>Publicador:</b>"))
    }

    @Test
    fun refsDoPlano_iguaisAsDoExtratorDoEditor() {
        val text = """
            TESOUROS DA PALAVRA DE DEUS
            1. A esperança que fortalece (10 min)
            Considere Jer. 45:3 e a lição 7 ponto 4 (lmd lição 7 ponto 4).
        """.trimIndent()

        val plan = planPaste(text)
        val talk = (plan.kind as PasteKind.VmTalk).part
        val (expectedBible, expectedPubs) = extractRefsFromBody(talk.body)

        assertFalse(expectedBible.isEmpty())
        assertFalse(expectedPubs.isEmpty())
        assertEquals(listOf("Je 45:3"), expectedBible)
        assertEquals(expectedBible, plan.bibleRefs)
        assertEquals(expectedPubs, plan.publicationRefs)
        val body = plan.sections.first { it.title == talk.title }
        assertEquals(expectedBible, body.bibleRefs)
        assertEquals(expectedPubs, body.publicationRefs)
        // A nota agregada (usada pelo FAB) enxerga as mesmas refs.
        assertEquals(expectedBible, buildPastedNote(text).bibleRefs)
    }

    @Test
    fun joiasSemTalkNemDemo_rejeitaSemCriarCards() {
        val text = """
            TESOUROS DA PALAVRA DE DEUS
            2. Joias espirituais (8 min)
            Tesouros escondidos.
        """.trimIndent()

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmRejected)
        assertTrue(plan.sections.isEmpty())
        assertTrue(plan.notice.orEmpty().contains("nenhuma das partes"))
    }

    @Test
    fun esbocoS34_usaPlannerNaEstruturaEPersonaDeMentorNoPlano() {
        val text = """
            1. Introdução (3 min)
            Fale sobre a importância do tema.
            2. Tópico um (8 min)
            Desenvolva o primeiro ponto.
            3. Tópico dois (8 min)
            Desenvolva o segundo ponto.
            4. Conclusão (3 min)
            Encerre com uma aplicação prática.
        """.trimIndent()

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.S34)
        assertNotNull((plan.kind as PasteKind.S34).outline)
        assertEquals(DiscourseType.S34_DISCOURSE, plan.discourseType)
        assertEquals(GenerationProfile.SPEECH_MENTOR, plan.generationProfile)
        assertTrue(plan.sections.isEmpty())
    }

    @Test
    fun textoLivre_usaChatEUmaSecaoCorpo() {
        val plan = planPaste("Uma nota qualquer\ncom duas linhas.")
        assertTrue(plan.kind is PasteKind.FreeText)
        assertEquals(GenerationProfile.CHAT, plan.generationProfile)
        assertEquals(1, plan.sections.size)
        assertEquals("Corpo", plan.sections.single().title)
        assertFalse(plan.sections.single().bibleRefs.isNotEmpty())
    }
}
