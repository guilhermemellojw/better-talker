package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.GenerationProfile
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
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
    fun consideracao_criaUmCardUnicoComPersonaDeMentor() {
        val text = """
            6. O que você diria? (6 min) Consideração.
            Pergunte à assistência como cada um responderia.
        """.trimIndent()

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmConsideration)
        assertEquals(DiscourseType.AVULSO, plan.discourseType)
        assertEquals(GenerationProfile.SPEECH_MENTOR, plan.generationProfile)
        assertEquals("O que você diria? (6 min)", plan.noteTitle)
        assertEquals(1, plan.sections.size)
        val card = plan.sections.single()
        assertEquals("O que você diria?", card.title)
        assertEquals(6, card.minutes)
        assertTrue(card.contentHtml.contains("assistência"))
        assertTrue(plan.notice.orEmpty().contains("Consideração"))
    }

    @Test
    fun explicarCrencasDiscurso_viraTalkComTresCards() {
        val text = "5. Explicar as suas crenças (5 min) Discurso. " +
            "Use uma abordagem simples e respeitosa."

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmTalk)
        assertEquals(DiscourseType.MINISTRY_TALK, plan.discourseType)
        assertEquals(GenerationProfile.SPEECH_MENTOR, plan.generationProfile)
        assertEquals(3, plan.sections.size)
    }

    @Test
    fun perseverancaDiscurso_viraTalkComTresCards() {
        val text = "4. Perseverança – O que Paulo fez (7 min) Discurso com participação. " +
            "Convide a assistência a comentar."

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmTalk)
        assertEquals(3, plan.sections.size)
    }

    @Test
    fun explicarCrencasDemonstracao_viraDemoComUmCard() {
        val text = "6. Explicar as suas crenças (5 min) Demonstração. " +
            "Prepare uma resposta respeitosa sobre a esperança."

        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmDemo)
        assertEquals(DiscourseType.MINISTRY_PART, plan.discourseType)
        assertEquals(GenerationProfile.DEMO_SCRIPTWRITER, plan.generationProfile)
        assertEquals(1, plan.sections.size)
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

    // ---------- Diagnóstico/regressão: 3 formatos reais da parte 1 de Tesouros ----------

    private val treasuresBody = """
        Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)

        Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)

        Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
    """.trimIndent()

    @Test
    fun format1_semNumeroSemCabecalho_retornaVmTalk() {
        val text = "Ter esperança é o segredo para o contentamento\n\n(10 min)\n\n$treasuresBody"
        val plan = planPaste(text)
        assertEquals(PasteKind.VmTalk::class, plan.kind::class)
    }

    @Test
    fun format2_comNumeroMinutosLinhaSeguinte_retornaVmTalk() {
        val text = "1. Ter esperança é o segredo para o contentamento\n\n(10 min)\n\n$treasuresBody"
        val plan = planPaste(text)
        assertEquals(PasteKind.VmTalk::class, plan.kind::class)
    }

    @Test
    fun format3_comCabecalho_retornaVmTalk() {
        val text = "TESOUROS DA PALAVRA DE DEUS\n" +
            "1. Ter esperança é o segredo para o contentamento\n\n(10 min)\n\n$treasuresBody"
        val plan = planPaste(text)
        assertEquals(PasteKind.VmTalk::class, plan.kind::class)
    }

    @Test
    fun format4_singleLineMultiParagrafo_retornaVmTalk() {
        val body = "Parece que Baruque não estava satisfeito com sua situação na vida. " +
            "(Jer. 45:3; w25.07 22 §§ 8-9) Sem contentamento, podemos perder nossa " +
            "alegria e tomar decisões ruins. (w25.07 20 § 2) Jeová ajudou Baruque a " +
            "mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)"
        val text = "Ter esperança é o segredo para o contentamento\n\n(10 min)\n\n$body"
        val plan = planPaste(text)
        assertEquals(PasteKind.VmTalk::class, plan.kind::class)
        val part = (plan.kind as PasteKind.VmTalk).part
        assertEquals(VmSection.TREASURES, part.section)
        assertEquals(1, part.partNumber)
        assertEquals(10, part.minutes)
    }

    @Test
    fun format5_singleLineTudoInline_retornaVmTalk() {
        val text = "Ter esperança é o segredo para o contentamento (10 min) " +
            "Parece que Baruque não estava satisfeito com sua situação na vida. " +
            "(Jer. 45:3; w25.07 22 §§ 8-9) Sem contentamento, podemos perder nossa " +
            "alegria e tomar decisões ruins. (w25.07 20 § 2) Jeová ajudou Baruque a " +
            "mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)"
        val plan = planPaste(text)
        assertEquals(PasteKind.VmTalk::class, plan.kind::class)
        val part = (plan.kind as PasteKind.VmTalk).part
        assertEquals(VmSection.TREASURES, part.section)
        assertEquals(1, part.partNumber)
        assertEquals(10, part.minutes)
        assertEquals("Ter esperança é o segredo para o contentamento", part.title)
    }

    @Test
    fun format5_cadaFraseViraSubtopicoDaLinhaDeRaciocinio() {
        val text = "Ter esperança é o segredo para o contentamento (10 min) " +
            "Parece que Baruque não estava satisfeito com sua situação " +
            "(Jer. 45:3; w25.07 22 §§ 8-9) Sem contentamento, podemos perder a " +
            "alegria (w25.07 20 § 2) Jeová ajudou Baruque a mudar o ponto de " +
            "vista (Jer. 45:5; w25.07 24 §§ 13-14)"
        val plan = planPaste(text)
        val body = plan.sections.first { it.role == SectionRole.BODY }
        assertEquals(3, body.subPoints.size)
        assertTrue(body.subPoints[0].text.startsWith("Parece que Baruque"))
        assertTrue(body.subPoints[1].text.startsWith("Sem contentamento"))
        assertTrue(body.subPoints[2].text.startsWith("Jeová ajudou"))
        assertTrue(body.subPoints[0].bibleRefs.any { it.contains("45:3") })
        assertTrue(body.subPoints[0].publicationRefs.isNotEmpty())
    }

    @Test
    fun splitVmSubtopics_naoQuebraRefsNemAbbreviacoes() {
        val body = "Parece que Baruque não estava satisfeito " +
            "(Jer. 45:3; w25.07 22 §§ 8-9) Sem contentamento, podemos perder a " +
            "alegria (w25.07 20 § 2) Jeová ajudou Baruque a mudar o ponto de " +
            "vista (Jer. 45:5; w25.07 24 §§ 13-14)"
        val subs = splitVmSubtopics(body)
        assertEquals(3, subs.size)
        assertTrue(subs[0].text.contains("Jer. 45:3"))
        assertTrue(subs[0].text.endsWith(")"))
        assertTrue(subs[1].text.startsWith("Sem contentamento"))
        assertTrue(subs[2].text.startsWith("Jeová ajudou"))
    }

    private val singleNewlineBody =
        "Parece que Baruque não estava satisfeito com sua situação " +
            "(Jeremias 45:3; lmd licao 7 ponto 4)\n" +
            "Sem contentamento, podemos perder a alegria (lmd licao 7 ponto 4)\n" +
            "Jeova ajudou Baruque a mudar o ponto de vista " +
            "(Jeremias 45:5; lmd licao 7 ponto 4)"

    @Test
    fun format1_semLinhasEmBranco_retornaVmTalk() {
        val text = "Ter esperança é o segredo para o contentamento\n" +
            "(10 min)\n$singleNewlineBody"
        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmTalk)
        assertEquals(1, (plan.kind as PasteKind.VmTalk).part.partNumber)
        assertEquals(10, (plan.kind as PasteKind.VmTalk).part.minutes)
    }

    @Test
    fun format2_semLinhasEmBranco_retornaVmTalk() {
        val text = "1. Ter esperança é o segredo para o contentamento\n" +
            "(10 min)\n$singleNewlineBody"
        assertTrue(planPaste(text).kind is PasteKind.VmTalk)
    }

    @Test
    fun format3_semLinhasEmBranco_retornaVmTalk() {
        val text = "TESOUROS DA PALAVRA DE DEUS\n" +
            "1. Ter esperança é o segredo para o contentamento\n" +
            "(10 min)\n$singleNewlineBody"
        assertTrue(planPaste(text).kind is PasteKind.VmTalk)
    }

    @Test
    fun normalizadorJuntaMinutosQuandoNaoHaLinhaEmBranco() {
        val normalized = PasteTextNormalizer.normalize(
            "Ter esperança é o segredo para o contentamento\n(10 min)\nCorpo."
        )
        assertTrue(normalized.contains("contentamento (10 min)"))
    }

    @Test
    fun format5_titulosVariados_naoUsamKeyword() {
        val body = "O texto mostra o poder de Deus. (Sl 19:1) " +
            "A publicação explica o tema. (w24.01 5 § 2)"
        val titles = listOf(
            "A ordem nos céus revela a glória de Deus",
            "Como podemos imitar a fé de Abraão",
            "Perseverança – O que Paulo fez",
            "A criação revela o poder de Deus",
        )
        titles.forEach { title ->
            val text = "$title (10 min) $body"
            val plan = planPaste(text)
            assertTrue(text, plan.kind is PasteKind.VmTalk)
            val part = (plan.kind as PasteKind.VmTalk).part
            assertEquals(text, VmSection.TREASURES, part.section)
            assertEquals(text, 1, part.partNumber)
        }
    }

    @Test
    fun textoLivreComMinutosSemParenteses_retornaFreeText() {
        val plan = planPaste(
            "Vou falar por 10 min sobre João 3:16 e Salmos 23:1, então..."
        )
        assertTrue(plan.kind is PasteKind.FreeText)
    }

    @Test
    fun singleLineComRefsSoBiblicas_naoSequestraTexto() {
        val text = "Reflexão sobre a esperança (10 min) " +
            "O texto cita João 3:16. Também cita Salmos 23:1."
        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.FreeText)
    }

    @Test
    fun format1_titulosVariados_naoUsamKeyword() {
        val cases = listOf(
            "A ordem nos céus revela a glória de Deus" to 10,
            "Como podemos imitar a fé de Abraão" to 10,
            "Perseverança – O que Paulo fez" to 7,
        )
        cases.forEach { (title, minutes) ->
            val text = "$title\n\n($minutes min)\n\n$treasuresBody"
            val plan = planPaste(text)
            assertTrue(text, plan.kind is PasteKind.VmTalk)
            val part = (plan.kind as PasteKind.VmTalk).part
            assertEquals(text, VmSection.TREASURES, part.section)
            assertEquals(text, 1, part.partNumber)
            assertEquals(text, minutes, part.minutes)
        }
    }

    @Test
    fun parte4IniciandoConversas_retornaVmDemo() {
        val text = "4. Iniciando conversas (3 min) TESTEMUNHO INFORMAL\n" +
            "Publicador: Olá, tudo bem?\nMorador: Tudo bem.\n" +
            "Publicador: Gostaria de conversar sobre a Bíblia."
        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.VmDemo)
        val part = (plan.kind as PasteKind.VmDemo).part
        assertEquals(VmSection.MINISTRY, part.section)
        assertEquals(4, part.partNumber)
        assertEquals(VmNature.DEMO, part.nature)
    }

    @Test
    fun parte6DiscursoSemCabecalho_retornaVmTalk() {
        val plan = planPaste(
            "6. Discurso (5 min) Tema: seja corajoso\n\nCorpo do discurso."
        )
        assertTrue(plan.kind is PasteKind.VmTalk)
        assertEquals(DiscourseType.MINISTRY_TALK, plan.discourseType)
    }

    @Test
    fun parte6ConsideracaoSemCabecalho_retornaVmConsideration() {
        val plan = planPaste(
            "6. O que você diria? (6 min) Consideração\n\nPergunte à assistência."
        )
        assertTrue(plan.kind is PasteKind.VmConsideration)
    }

    @Test
    fun textoLivreComRefEsporadica_retornaFreeText() {
        val plan = planPaste("Olá, tudo bem? Vou falar sobre João 3:16 hoje.")
        assertTrue(plan.kind is PasteKind.FreeText)
    }

    @Test
    fun parteSemRefsSuficientes_naoSequestraTexto() {
        val text = "Ter esperança é o segredo para o contentamento\n\n(10 min)\n\n" +
            "Parece que Baruque não estava satisfeito. (Jer. 45:3)"
        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.FreeText)
    }

    @Test
    fun parteComMinutosForaDaFaixa_naoSequestraTexto() {
        val text = "Ter esperança é o segredo para o contentamento\n\n(20 min)\n\n$treasuresBody"
        val plan = planPaste(text)
        assertTrue(plan.kind is PasteKind.FreeText)
    }
}
