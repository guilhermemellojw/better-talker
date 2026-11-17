package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.0 — legado de agreedApproach não é confirmação automática. */
class ApproachMemoryTest {

    @Test
    fun legadoSemMarcadorNaoEhConfirmado() {
        val memory = parseApproachMemory("situação → princípio → aplicação")
        assertTrue(memory.confirmed.isEmpty())
        assertEquals(listOf("situação → princípio → aplicação"), memory.legacy)
    }

    @Test
    fun marcadorDistingueAceiteExplicito() {
        val memory = parseApproachMemory(
            "texto antigo sem origem\n- [confirmado] usar a segunda abordagem"
        )
        assertEquals(listOf("usar a segunda abordagem"), memory.confirmed)
        assertEquals(listOf("texto antigo sem origem"), memory.legacy)
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
            "legado A\n[confirmado] usar pergunta"
        )!!
        assertTrue(block.contains("ABORDAGEM CONFIRMADA PELO USUÁRIO"))
        assertTrue(block.contains("- usar pergunta"))
        assertTrue(block.contains("REGISTRO LEGADO"))
        assertTrue(block.contains("- legado A"))
    }

    @Test
    fun aceiteExplicitoAcrescentaMarcadorSemApagarLegado() {
        val merged = mergeConfirmedApproachText("legado antigo", "usar pergunta")!!
        assertTrue(merged.startsWith("legado antigo"))
        assertTrue(merged.contains("$CONFIRMED_APPROACH_PREFIX usar pergunta"))
        assertEquals(listOf("legado antigo"), parseApproachMemory(merged).legacy)
        assertEquals(listOf("usar pergunta"), parseApproachMemory(merged).confirmed)
    }

    @Test
    fun aceiteDuplicadoNaoMuda() {
        val first = mergeConfirmedApproachText(null, "usar pergunta")!!
        assertNull(mergeConfirmedApproachText(first, "usar pergunta"))
        assertNull(mergeConfirmedApproachText(first, "  "))
    }

    @Test
    fun legadoDuplicadoEhOcultadoNaRenderizacao() {
        val raw = "usar pergunta\n$CONFIRMED_APPROACH_PREFIX usar pergunta"
        val memory = parseApproachMemory(raw)
        assertEquals(listOf("usar pergunta"), memory.confirmed)
        assertTrue(memory.legacy.isEmpty())
    }
}
