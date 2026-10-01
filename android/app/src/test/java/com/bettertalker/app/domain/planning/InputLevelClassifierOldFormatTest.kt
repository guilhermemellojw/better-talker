package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Test

class InputLevelClassifierOldFormatTest {

    private val classifier = InputLevelClassifier()

    @Test
    fun oldFormat_totalTimePattern_serAbrangido_works() {
        val input = "A SER ABRANGIDO EM 45 MINUTOS\n" +
            "**POR QUE MUITOS TEMEM O FUTURO** (5 min)\n" +
            "**SEGUNDO TÍTULO DA SEÇÃO** (5 min)\n" +
            "**TERCEIRO TÍTULO DA SEÇÃO** (8 min)"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun oldFormat_totalTimePattern_duracao_works() {
        val input = "DURAÇÃO: 45 MINUTOS\n" +
            "**POR QUE MUITOS TEMEM O FUTURO** (5 min)\n" +
            "**SEGUNDO TÍTULO DA SEÇÃO** (5 min)\n" +
            "**TERCEIRO TÍTULO DA SEÇÃO** (8 min)"
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun oldFormat_markdownTitle_withMinutes_isCounted() {
        val input = "**POR QUE MUITOS TEMEM O FUTURO** (5 min)\nIs 45:18\nSof 1:14"
        assertEquals(InputLevel.PARTIAL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun oldFormat_markdownTitle_withoutMinutes_isNotCounted() {
        val input = "**ESCAPARÁ DO DESTINO DESTE MUNDO?**\nIs 45:18\nSof 1:14"
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify(input))
    }

    @Test
    fun oldFormat_markdownBold_noMinutes_isNotCounted() {
        val input = "**TÍTULO EM NEGRITO SEM MINUTOS**\nIs 45:18"
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify(input))
    }

    @Test
    fun oldFormat_h1Heading_withMinutes_isCounted() {
        val input = "# TÍTULO DA SEÇÃO (5 min)\nIs 45:18\nSof 1:14"
        assertEquals(InputLevel.PARTIAL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun oldFormat_isaíasAbbrevIs_isRecognized() {
        assertEquals(InputLevel.THEME_WITH_TEXTS, classifier.classify("Is 45:18"))
    }

    @Test
    fun oldFormat_fullS34Number84_isFullOutline() {
        val input = "**ESCAPARÁ DO DESTINO DESTE MUNDO?**\n" +
            "A SER ABRANGIDO EM 45 MINUTOS\n" +
            "**POR QUE MUITOS TEMEM O FUTURO** (5 min)\n" +
            "Is 45:18; Sof 1:14-18 — as pessoas temem o futuro.\n" +
            "**O QUE O FUTURO RESERVA PARA NÓS?** (10 min)\n" +
            "Apo 21:4 — a promessa de um novo mundo.\n" +
            "**COMO ENCARAR O FUTURO COM CONFIANÇA** (5 min)\n" +
            "Sof 2:3 — busque a Jeová."
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }

    @Test
    fun oldFormat_modernS34WithMarkdown_isFullOutline() {
        // Bug latente: S-34-T moderno real usa **TÍTULO** (N min) — antes da 1.1e
        // esses títulos não eram contados e o nível caía.
        val input = "N.º 35 É possível viver para sempre?\n" +
            "TEMPO TOTAL: 30 MINUTOS\n" +
            "**FOMOS CRIADOS PARA VIVER PARA SEMPRE** (5 min)\n" +
            "Precisamos estar vivos para ter esperança.\n" +
            "**COMO A VIDA FOI PERDIDA** (4 min)\n" +
            "Adão e Eva desobedeceram.\n" +
            "**COMO RECUPERAR A VIDA** (7 min)\n" +
            "Jeová promete vida eterna."
        assertEquals(InputLevel.FULL_OUTLINE, classifier.classify(input))
    }
}
