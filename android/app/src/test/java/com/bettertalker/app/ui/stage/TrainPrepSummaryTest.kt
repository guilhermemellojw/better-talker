package com.bettertalker.app.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainPrepSummaryTest {

    @Test
    fun resumoInteiroAnaliseComCronometro() {
        assertEquals(
            "inteiro · análise · cronômetro",
            trainPrepSummary("WHOLE|A|1"),
        )
    }

    @Test
    fun resumoTrechoSoLeituraSemCronometro() {
        assertEquals(
            "trecho · só leitura · sem cronômetro",
            trainPrepSummary("SECTION|sec-1|R|0"),
        )
    }

    @Test
    fun resumoSubtopico() {
        assertEquals(
            "subtópico · análise · cronômetro",
            trainPrepSummary("SUBPOINT|sec-1|sp-1|A|1"),
        )
    }

    @Test
    fun valorAusenteOuInvalidoEhNull() {
        assertNull(trainPrepSummary(null))
        assertNull(trainPrepSummary(""))
        assertNull(trainPrepSummary("lixo"))
    }
}
