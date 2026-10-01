package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Test

class InputLevelClassifierTest {

    private val classifier = InputLevelClassifier()

    // FULL_OUTLINE

    @Test
    fun fullOutline_syntheticFixture() {
        val input = "Título: Jeová é digno de confiança\n" +
            "TEMPO TOTAL: 30 MINUTOS\n" +
            "CONFIE EM JEOVÁ EM TEMPOS DIFÍCEIS\n" +
            "João 5:28,29 e Atos 24:15\n" +
            "APOIE SUA FÉ COM PROVAS\n" +
            "Heb 11:1 e Rom 10:17\n" +
            "MANTENHA A ESPERANÇA VIVA\n" +
            "Apo 21:3,4"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun fullOutline_tempoTotalUppercase() {
        val input = "TEMPO TOTAL: 15 MINUTOS\nFÉ E ORAÇÃO\nJoão 3:16\nAMOR DE DEUS\nRom 5:8\nESPERANÇA VIVA\nApo 21:4"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun fullOutline_tempoTotalLowercase() {
        val input = "tempo total: 15 MINUTOS\nFÉ E ORAÇÃO\nJoão 3:16\nAMOR DE DEUS\nRom 5:8\nESPERANÇA VIVA\nApo 21:4"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    // PARTIAL_OUTLINE

    @Test
    fun partialOutline_oneTitleTwoRefs() {
        val input = "A VIDA ETERNA\nJoão 5:28,29\nAtos 24:15"
        assertEquals(InputLevel.PARTIAL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun partialOutline_twoTitlesFiveRefs() {
        val input = "A ESPERANÇA DA VIDA ETERNA\nJoão 5:28,29 e Atos 24:15\nO AMOR DE DEUS\nRomanos 5:8 e Hebreus 11:1 e Apocalipse 21:4"
        assertEquals(InputLevel.PARTIAL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun partialOutline_tnmAbbreviationsProAnd1Te() {
        val input = "A VIDA ETERNA\nPro 3:5\n1Te 4:16"
        assertEquals(InputLevel.PARTIAL_OUTLINE, classifier.classify(input))
    }

    // THEME_WITH_TEXTS

    @Test
    fun themeWithTexts_resurrectionHope() {
        val input = "A esperança da ressurreição. Textos: João 5:28,29 e Atos 24:15."
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify(input))
    }

    @Test
    fun themeWithTexts_fullBookName() {
        val input = "Fé. Hebreus 11:1."
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify(input))
    }

    @Test
    fun themeWithTexts_tnmAbbreviation1Ti() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("1Ti 2:5"))
    }

    @Test
    fun themeWithTexts_tnmAbbreviation2Te() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("2Te 3:3"))
    }

    @Test
    fun themeWithTexts_fullNameProvérbiosStillMatches() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Provérbios 3:5"))
    }

    @Test
    fun themeWithTexts_legacyName1TessalonicensesStillMatches() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("1Tessalonicenses 4:16"))
    }

    // THEME_ONLY

    @Test
    fun themeOnly_plainTheme() {
        assertEquals(InputLevel.THEME_ONLY, classifier.classify("A importância do perdão"))
    }

    @Test
    fun themeOnly_empty() {
        assertEquals(InputLevel.THEME_ONLY, classifier.classify(""))
    }

    @Test
    fun themeOnly_whitespace() {
        assertEquals(InputLevel.THEME_ONLY, classifier.classify("   \n  \n  "))
    }

    @Test
    fun themeOnly_loneTitleWithoutRefs() {
        assertEquals(InputLevel.THEME_ONLY, classifier.classify("O AMOR NUNCA ACABA"))
    }

    // Bordas

    @Test
    fun border_tempoTotalTwoTitlesTwoRefs_isPartial() {
        val input = "TEMPO TOTAL: 20 MIN\nFÉ EM DEUS\nJoão 3:16\nAMOR ETERNO\nRom 5:8"
        assertEquals(InputLevel.PARTIAL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun border_tempoTotalTwoTitlesNoRefs_isThemeOnly() {
        val input = "TEMPO TOTAL: 20 MIN\nFÉ EM DEUS\nAMOR ETERNO"
        assertEquals(InputLevel.THEME_ONLY, classifier.classify(input))
    }

    @Test
    fun border_invalidRefDoesNotCount() {
        val input = "A VIDA ETERNA\nJoão 3:16\nXYZ 5:6"
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify(input))
    }

    @Test
    fun border_shortUppercaseLineIsNotTitle() {
        assertEquals(InputLevel.THEME_ONLY, classifier.classify("ATENÇÃO"))
    }

    @Test
    fun border_titleEndingWithColonIsNotTitle() {
        assertEquals(InputLevel.THEME_ONLY, classifier.classify("A VIDA ETERNA:"))
    }

    @Test
    fun titleWithQuestionMark_isCounted() {
        val input = "PAREM DE SE PREOCUPAR TANTO\nJoão 3:16\nPOR QUE NÃO PRECISAMOS?\nRom 5:8\nQUAIS OS BENEFÍCIOS?\nApo 21:4\nTEMPO TOTAL: 20 MIN"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun titleWithExclamation_isCounted() {
        val input = "VIVA COM ALEGRIA!\nJoão 3:16\nCONFIE EM DEUS!\nRom 5:8\nESPERANÇA VIVA!\nApo 21:4\nTEMPO TOTAL: 15 MIN"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun titleWithColonOrDot_isNotCounted() {
        val input = "TÍTULO COM DOIS PONTOS:\nJoão 3:16\nOUTRO COM PONTO.\nRom 5:8"
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify(input))
    }

    @Test
    fun tnmAbbreviationPr_isCounted() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Pr 3:5"))
    }

    @Test
    fun tnmAbbreviationHe_isCounted() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("He 11:1"))
    }

    @Test
    fun tnmAbbreviationTg_isCounted() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Tg 2:17"))
    }

    @Test
    fun tnmAbbreviationAp_isCounted() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Ap 21:4"))
    }

    @Test
    fun fullNameProverbios_stillMatches() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Provérbios 3:5"))
    }

    @Test
    fun fullNameHebreus_stillMatches() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Hebreus 11:1"))
    }

    // ---------- 1.1e: regressões dos formatos modernos ----------

    @Test
    fun regression_modernPlainTitles_stillWork() {
        val input = "TEMPO TOTAL: 30 MIN\nFÉ E ORAÇÃO\nAMOR DE DEUS\nESPERANÇA VIVA"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun regression_tnmAbbreviations_stillWork() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Pr 3:5"))
    }

    @Test
    fun regression_questionTitles_stillWork() {
        val input = "TEMPO TOTAL: 20 MIN\nPAREM DE SE PREOCUPAR TANTO\nPOR QUE NÃO PRECISAMOS?\nQUAIS OS BENEFÍCIOS?"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }
}
