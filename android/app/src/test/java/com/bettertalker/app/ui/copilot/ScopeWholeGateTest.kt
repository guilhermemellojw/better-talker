package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.buildDeepSeekPrompts
import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #14-fix: em WHOLE (Discurso inteiro) o bloco/seleção do editor não valem —
 * sem "conclusão fantasma" no prompt (BLOCO ATUAL) nem no hint do S-34.
 */
class ScopeWholeGateTest {

    @Test
    fun whole_zeraBlocoESelecao() {
        assertEquals(null to "", effectiveFocusFields(true, "Conclusão", "trecho selecionado"))
    }

    @Test
    fun section_passaIntacto() {
        assertEquals(
            "Conclusão" to "trecho selecionado",
            effectiveFocusFields(false, "Conclusão", "trecho selecionado"),
        )
    }

    @Test
    fun whole_hintS34NaoResolveAUltimaSecao() {
        val (block, sel) = effectiveFocusFields(true, "Conclusão", "x")
        val hint = structuralHint(pushedTitle = null, activeBlockTitle = block, selection = sel)
        // Vazio = sem hint (mesmo contrato do "nada focado" legado); nunca "Conclusão".
        assertTrue("hint deveria ficar vazio, veio: $hint", hint.isNullOrBlank())
        assertNotEquals("Conclusão", hint)
    }

    @Test
    fun section_hintS34ResolveAUltimaSecao() {
        val (block, sel) = effectiveFocusFields(false, "Conclusão", "x")
        assertEquals(
            "Conclusão",
            structuralHint(pushedTitle = null, activeBlockTitle = block, selection = sel),
        )
    }

    @Test
    fun whole_promptSemBlocoAtualDaConclusao() {
        val (block, _) = effectiveFocusFields(true, "Conclusão", "")
        val prompts = buildDeepSeekPrompts(
            message = "o que temos até agora?",
            history = emptyList(),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = block,
            blockMinutes = null,
            blockText = "corpo da nota",
        )
        assertFalse(prompts.system.contains("Conclusão"))
        assertFalse(prompts.system.contains("BLOCO ATUAL"))
    }

    @Test
    fun section_promptComBlocoAtual() {
        val (block, _) = effectiveFocusFields(false, "Conclusão", "")
        val prompts = buildDeepSeekPrompts(
            message = "o que temos até agora?",
            history = emptyList(),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = block,
            blockMinutes = null,
            blockText = "corpo da nota",
        )
        assertTrue(prompts.system.contains("Conclusão"))
        assertTrue(prompts.system.contains("BLOCO ATUAL"))
    }
}
