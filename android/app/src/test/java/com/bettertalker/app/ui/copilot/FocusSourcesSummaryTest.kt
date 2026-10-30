package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.FocusCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/** C2.6 — resumo do cabeçalho de "Fontes e apoio". */
class FocusSourcesSummaryTest {

    private fun counts(
        point: Int = 0,
        content: Int = 0,
        dossier: Int = 0,
        noText: Int = 0,
        orientation: Int = 0,
        budget: Int = 0,
        turnRefs: Int = 0,
    ) = FocusCounts(
        point = point,
        pointContent = content + noText,
        content = content,
        orientation = orientation,
        orientationInventory = orientation,
        dossier = dossier,
        pack = 0,
        question = 0,
        newText = content,
        noText = noText,
        budget = budget,
        truncated = 0,
        noteRefs = point,
        turnRefs = turnRefs,
        extras = 0,
    )

    @Test
    fun resumoDoPontoComPromptDossieESemTexto() {
        assertEquals(
            "5 do ponto · 3 no prompt · 1 no dossiê · 1 sem texto",
            focusSourcesSummary(
                counts(point = 5, content = 3, dossier = 1, noText = 1, turnRefs = 9),
            ),
        )
    }

    @Test
    fun semPontoMostraTurno() {
        assertEquals(
            "4 do turno",
            focusSourcesSummary(counts(point = 0, turnRefs = 4)),
        )
    }

    @Test
    fun incluiOrientacaoEOrcamentoQuandoExistirem() {
        assertEquals(
            "2 do ponto · 1 orientação · 1 fora do orçamento",
            focusSourcesSummary(
                counts(point = 2, orientation = 1, budget = 1, turnRefs = 8),
            ),
        )
    }
}
