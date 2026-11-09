package com.bettertalker.app.ui.bible

import com.bettertalker.app.domain.bible.BiblePassageReader
import com.bettertalker.app.domain.bible.BiblePassageSource
import com.bettertalker.app.domain.bible.BibleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.2 — estado do leitor: abrir, status, TTS sem números e fechar. */
class BibleReaderViewModelTest {

    private class FakeSource(
        private val source: BibleSource? = BibleSource("nwt", "TNM", true),
        private val texts: Map<String, String> = mapOf(
            "Ez 1:16" to "Primeiro versículo.",
            "Ez 1:17" to "Segundo versículo.",
        ),
    ) : BiblePassageSource {
        override suspend fun preferredSource(): BibleSource? = source
        override suspend fun texts(sourceId: String, canonicalRefs: List<String>) =
            texts.filterKeys { it in canonicalRefs }
    }

    private class FakeSpeaker : BibleSpeaker {
        val spoken = mutableListOf<String>()
        val state = MutableStateFlow(false)
        override fun speak(text: String) {
            spoken += text
            state.value = true
        }
        override fun stop() { state.value = false }
        override val speaking: Flow<Boolean> = state
    }

    private fun withVm(
        source: BiblePassageSource = FakeSource(),
        block: suspend (BibleReaderViewModel, FakeSpeaker) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val speaker = FakeSpeaker()
            val vm = BibleReaderViewModel(BiblePassageReader(source), speaker, scope)
            block(vm, speaker)
            delay(30)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitPassage(vm: BibleReaderViewModel) {
        val deadline = System.currentTimeMillis() + 2_000
        while (vm.state.value.passage == null && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    @Test
    fun abreCarregaEMostraStatus() = withVm { vm, _ ->
        vm.open(listOf("Ezequiel 1:16-17"))
        assertTrue(vm.state.value.visible)
        assertTrue(vm.state.value.loading)
        awaitPassage(vm)
        val p = vm.state.value.passage!!
        assertEquals("Ezequiel 1:16-17", p.displayRef)
        assertEquals(2, p.verses.size)
        assertEquals("TNM", p.sourceLabel)
        assertNull(vm.state.value.error)
    }

    @Test
    fun ttsLeTextoCorridoSemNumeros() = withVm { vm, speaker ->
        vm.open(listOf("Ez 1:16-17"))
        awaitPassage(vm)
        vm.toggleSpeak()
        delay(20)
        assertEquals(1, speaker.spoken.size)
        assertTrue(speaker.spoken[0].contains("Primeiro versículo"))
        assertTrue(speaker.spoken[0].contains("Segundo versículo"))
        assertFalse(speaker.spoken[0].startsWith("16"))
        assertTrue(vm.state.value.speaking)
        vm.close()
        assertFalse(vm.state.value.visible)
        assertNull(vm.state.value.passage)
        assertFalse(speaker.state.value)
    }

    @Test
    fun semTnmExpoeBiblioteca() = withVm(source = FakeSource(source = null)) { vm, _ ->
        vm.open(listOf("João 3:16"))
        awaitPassage(vm)
        val p = vm.state.value.passage!!
        assertTrue(p.needsLibrary)
        assertEquals("A TNM não está no acervo.", p.warning)
    }

    @Test
    fun refVaziaNaoAbre() = withVm { vm, _ ->
        vm.open(listOf(" ", ""))
        assertFalse(vm.state.value.visible)
    }
}
