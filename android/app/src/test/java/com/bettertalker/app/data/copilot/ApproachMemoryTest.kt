package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.0 — legado de agreedApproach não é confirmação automática. */
class ApproachMemoryTest {

    private fun stored(existing: String?, addition: String?): String {
        val result = mergeConfirmedApproachText(existing, addition)
        assertTrue("esperava Stored, veio $result", result is ConfirmedApproachWrite.Stored)
        return (result as ConfirmedApproachWrite.Stored).text
    }

    @Test
    fun legadoSemMarcadorNaoEhConfirmado() {
        val memory = parseApproachMemory("situação → princípio → aplicação")
        assertTrue(memory.confirmed.isEmpty())
        assertEquals(listOf("situação → princípio → aplicação"), memory.legacy)
    }

    @Test
    fun marcadorCompletoDistingueAceiteExplicito() {
        val memory = parseApproachMemory(
            "texto antigo sem origem\n- $CONFIRMED_APPROACH_PREFIX usar a segunda abordagem $CONFIRMED_APPROACH_SUFFIX"
        )
        assertEquals(listOf("usar a segunda abordagem"), memory.confirmed)
        assertEquals(listOf("texto antigo sem origem"), memory.legacy)
    }

    @Test
    fun prefixoSemFechamentoNaoEhPromovidoAConfirmado() {
        val raw = "texto antigo\n- $CONFIRMED_APPROACH_PREFIX fragmento sem fechamento"
        val memory = parseApproachMemory(raw)
        assertTrue(memory.confirmed.isEmpty())
        assertTrue(memory.legacy.any { it.contains("fragmento sem fechamento") })
    }

    @Test
    fun fragmentoTruncadoNoTetoNuncaViraConfirmado() {
        val raw = "x".repeat(480) + "\n- $CONFIRMED_APPROACH_PREFIX fragmento cortado"
        val memory = parseApproachMemory(raw)
        assertTrue(memory.confirmed.isEmpty())
        assertTrue(memory.legacy.any { it.contains("fragmento cortado") })
    }

    @Test
    fun renderNaoChamaLegadoDeAcordado() {
        val block = renderApproachMemory("texto antigo sem origem")!!
        assertTrue(block.contains("REGISTRO LEGADO"))
        assertTrue(block.contains("NÃO o trate como decisão confirmada do usuário"))
        assertFalse(block.contains("ABORDAGEM ACORDADA"))
    }

    @Test
    fun renderMostraConfirmadoSemEsconderLegado() {
        val block = renderApproachMemory(
            "legado A\n- $CONFIRMED_APPROACH_PREFIX usar pergunta $CONFIRMED_APPROACH_SUFFIX"
        )!!
        assertTrue(block.contains("ABORDAGEM CONFIRMADA PELO USUÁRIO"))
        assertTrue(block.contains("- usar pergunta"))
        assertTrue(block.contains("REGISTRO LEGADO"))
        assertTrue(block.contains("- legado A"))
    }

    @Test
    fun aceiteExplicitoAcrescentaMarcadorSemApagarLegado() {
        val merged = stored("legado antigo", "usar pergunta")
        assertTrue(merged.startsWith("legado antigo"))
        assertTrue(merged.contains("$CONFIRMED_APPROACH_PREFIX usar pergunta $CONFIRMED_APPROACH_SUFFIX"))
        assertEquals(listOf("legado antigo"), parseApproachMemory(merged).legacy)
        assertEquals(listOf("usar pergunta"), parseApproachMemory(merged).confirmed)
    }

    @Test
    fun aceiteDuplicadoNaoMuda() {
        val first = stored(null, "usar pergunta")
        assertTrue(mergeConfirmedApproachText(first, "usar pergunta") is ConfirmedApproachWrite.AlreadyStored)
        assertTrue(mergeConfirmedApproachText(first, "  ") is ConfirmedApproachWrite.Empty)
    }

    @Test
    fun legadoDuplicadoEhOcultadoNaRenderizacao() {
        val raw = "usar pergunta\n- $CONFIRMED_APPROACH_PREFIX usar pergunta $CONFIRMED_APPROACH_SUFFIX"
        val memory = parseApproachMemory(raw)
        assertEquals(listOf("usar pergunta"), memory.confirmed)
        assertTrue(memory.legacy.isEmpty())
    }

    @Test
    fun campos480490500NaoGravamConfirmacaoParcial() {
        listOf(480, 490, 500).forEach { size ->
            val raw = "x".repeat(size)
            val result = mergeConfirmedApproachText(raw, "usar uma abordagem completa")
            assertTrue("size=$size deveria ser NoSpace: $result", result is ConfirmedApproachWrite.NoSpace)
            val memory = parseApproachMemory(raw)
            assertEquals("size=$size", listOf(raw), memory.legacy)
            assertTrue("size=$size", memory.confirmed.isEmpty())
        }
    }

    @Test
    fun confirmacaoMultilinhaGravaTodasAsLinhasCompletas() {
        val merged = stored("legado", "linha A\nlinha B")
        assertTrue(merged.length <= MAX_APPROACH_CHARS)
        val memory = parseApproachMemory(merged)
        assertEquals(listOf("linha A", "linha B"), memory.confirmed)
        assertTrue(memory.legacy.contains("legado"))
        assertTrue(merged.contains("$CONFIRMED_APPROACH_PREFIX linha A $CONFIRMED_APPROACH_SUFFIX"))
        assertTrue(merged.contains("$CONFIRMED_APPROACH_PREFIX linha B $CONFIRMED_APPROACH_SUFFIX"))
    }

    @Test
    fun confirmacaoQueCabeExatamenteNoTetoNaoEhTruncada() {
        val addition = "a"
        val marked = "- $CONFIRMED_APPROACH_PREFIX $addition $CONFIRMED_APPROACH_SUFFIX"
        val existing = "x".repeat(MAX_APPROACH_CHARS - 1 - marked.length)
        val merged = stored(existing, addition)
        assertEquals(MAX_APPROACH_CHARS, merged.length)
        assertTrue(merged.endsWith(marked))
        assertEquals(listOf(addition), parseApproachMemory(merged).confirmed)
    }

    @Test
    fun confirmacaoMultilinhaSemEspacoNaoGravaNenhumaLinha() {
        val raw = "x".repeat(480)
        val result = mergeConfirmedApproachText(raw, "linha A\nlinha B")
        assertTrue(result is ConfirmedApproachWrite.NoSpace)
        val memory = parseApproachMemory(raw)
        assertTrue(memory.confirmed.isEmpty())
        assertEquals(listOf(raw), memory.legacy)
    }
}
