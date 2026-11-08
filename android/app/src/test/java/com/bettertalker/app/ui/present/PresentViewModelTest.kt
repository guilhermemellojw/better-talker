package com.bettertalker.app.ui.present

import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.domain.speech.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.1 — Palco: restauração/persistência de Mapa, Foco e foco por seção. */
class PresentViewModelTest {

    private class FakeSource(
        private val title: String = "Nota",
        private val discourseType: String? = null,
        private val sections: List<SpeechSectionEntity> = emptyList(),
        private val images: List<NoteImageEntity> = emptyList(),
    ) : PresentSource {
        override suspend fun load(noteId: String) = PresentSourceData(
            title = title,
            discourseType = discourseType,
            sections = sections,
            images = images,
        )
    }

    private class FakePrefs : PresentPrefs {
        val font = MutableStateFlow(24)
        val map = MutableStateFlow(true)
        val focus = mutableMapOf<String, MutableStateFlow<Int>>()
        var session: String? = null

        override val fontSp: Flow<Int> = font
        override suspend fun setFontSp(v: Int) { font.value = v }
        override fun mapMode(noteId: String): Flow<Boolean> = map
        override suspend fun setMapMode(noteId: String, map: Boolean) {
            this.map.value = map
        }
        override fun focusCue(noteId: String, sectionId: String): Flow<Int> =
            focus.getOrPut(sectionId) { MutableStateFlow(0) }
        override suspend fun setFocusCue(noteId: String, sectionId: String, index: Int) {
            focus.getOrPut(sectionId) { MutableStateFlow(0) }.value = index
        }
        override suspend fun savedSession(noteId: String): String? = session
        override suspend fun setSavedSession(noteId: String, raw: String) {
            session = raw
        }
    }

    private fun capsule(id: String, cueCount: Int): TribunaCapsule = TribunaCapsule(
        sectionId = id,
        mainIdea = "Ideia $id",
        anchorPhrase = "Âncora $id",
        cueSheetVersion = 2,
        cues = (0 until cueCount).map { index ->
            TribunaCue(TribunaCueKind.REASONING, "Lembrete $index")
        },
    )

    private fun section(
        id: String,
        order: Int,
        cueCount: Int = 2,
        mapped: Boolean = true,
    ) = SpeechSectionEntity(
        id = id,
        noteId = "n1",
        order = order,
        role = "BODY",
        title = "SEÇÃO $order",
        minutes = 5,
        contentHtml = "<p>x</p>",
        bibleRefsJson = "[]",
        publicationRefsJson = "[]",
        methodPrinciple = null,
        tribunaJson = if (mapped) capsule(id, cueCount).toJson() else null,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun withVm(
        source: FakeSource,
        prefs: FakePrefs = FakePrefs(),
        block: suspend (PresentViewModel, FakePrefs) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val vm = PresentViewModel(source, prefs, "n1", scope)
            block(vm, prefs)
            delay(60)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitLoaded(vm: PresentViewModel) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!vm.loadReady.value && System.currentTimeMillis() < deadline) delay(10)
    }

    private suspend fun await(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) delay(10)
    }

    @Test
    fun mapaPadraoEhMapa() = withVm(
        FakeSource(sections = listOf(section("s1", 0, cueCount = 3)))
    ) { vm, prefs ->
        assertTrue(prefs.map.value)
        vm.load()
        awaitLoaded(vm)
        assertTrue(vm.mapMode.value)
    }

    @Test
    fun mapaRestauradoDoDataStore() = withVm(
        FakeSource(sections = listOf(section("s1", 0, cueCount = 3)))
    ) { vm, prefs ->
        prefs.map.value = false
        await { !vm.mapMode.value }
        vm.load()
        awaitLoaded(vm)
        assertFalse(vm.mapMode.value)
    }

    @Test
    fun setMapModePersisteNoSeam() = withVm(
        FakeSource(sections = listOf(section("s1", 0, cueCount = 3)))
    ) { vm, prefs ->
        vm.load()
        awaitLoaded(vm)
        vm.setMapMode(false)
        await { !vm.mapMode.value && !prefs.map.value }
        assertFalse(vm.mapMode.value)
        assertFalse(prefs.map.value)
        vm.setMapMode(true)
        await { prefs.map.value }
    }

    @Test
    fun focoDaPrimeiraSecaoEhRestauradoNoLoad() = withVm(
        FakeSource(
            sections = listOf(
                section("s1", 0, cueCount = 3),
                section("s2", 1, cueCount = 2),
            )
        )
    ) { vm, prefs ->
        prefs.focus["s1"] = MutableStateFlow(1)
        vm.load()
        awaitLoaded(vm)
        await { vm.focusCueIndex.value == 1 }
        assertEquals(1, vm.focusCueIndex.value)
    }

    @Test
    fun focoSalvoAcimaDoLimiteEhClampadoNoLoad() = withVm(
        FakeSource(sections = listOf(section("s1", 0, cueCount = 2)))
    ) { vm, prefs ->
        prefs.focus["s1"] = MutableStateFlow(99)
        vm.load()
        awaitLoaded(vm)
        await { vm.focusCueIndex.value == 1 }
        assertEquals(1, vm.focusCueIndex.value)
    }

    @Test
    fun setFocusCueClampaEPersiste() = withVm(
        FakeSource(sections = listOf(section("s1", 0, cueCount = 3)))
    ) { vm, prefs ->
        vm.load()
        awaitLoaded(vm)
        vm.setFocusCue(99)
        await { vm.focusCueIndex.value == 2 && prefs.focus.getValue("s1").value == 2 }
        assertEquals(2, vm.focusCueIndex.value)
        vm.setFocusCue(-5)
        await { vm.focusCueIndex.value == 0 && prefs.focus.getValue("s1").value == 0 }
        assertEquals(0, vm.focusCueIndex.value)
    }

    @Test
    fun setFocusCueSemSecaoCarregadaNaoQuebra() = withVm(FakeSource()) { vm, prefs ->
        vm.setFocusCue(2)
        delay(30)
        assertEquals(0, vm.focusCueIndex.value)
        assertTrue(prefs.focus.isEmpty())
    }

    @Test
    fun nextRestauraFocoDaSecaoSeguinte() = withVm(
        FakeSource(
            sections = listOf(
                section("s1", 0, cueCount = 3),
                section("s2", 1, cueCount = 3),
            )
        )
    ) { vm, prefs ->
        prefs.focus["s1"] = MutableStateFlow(1)
        prefs.focus["s2"] = MutableStateFlow(2)
        vm.load()
        awaitLoaded(vm)
        vm.startSession()
        await { vm.currentIndex.value == 0 && vm.focusCueIndex.value == 1 }

        vm.next()
        await { vm.currentIndex.value == 1 && vm.focusCueIndex.value == 2 }

        vm.previous()
        await { vm.currentIndex.value == 0 && vm.focusCueIndex.value == 1 }
    }

    @Test
    fun focoDaSecaoSemLembretesVoltaAZero() = withVm(
        FakeSource(
            sections = listOf(
                section("s1", 0, cueCount = 3),
                section("s2", 1, cueCount = 0),
            )
        )
    ) { vm, prefs ->
        prefs.focus["s1"] = MutableStateFlow(2)
        prefs.focus["s2"] = MutableStateFlow(5)
        vm.load()
        awaitLoaded(vm)
        vm.startSession()
        await { vm.focusCueIndex.value == 2 }

        vm.next()
        await { vm.currentIndex.value == 1 && vm.focusCueIndex.value == 0 }
    }

    @Test
    fun navegacaoRapidaNaoAplicaFocoDeSecaoAnterior() = withVm(
        FakeSource(
            sections = listOf(
                section("s1", 0, cueCount = 3),
                section("s2", 1, cueCount = 3),
                section("s3", 2, cueCount = 3),
            )
        )
    ) { vm, prefs ->
        prefs.focus["s1"] = MutableStateFlow(1)
        prefs.focus["s2"] = MutableStateFlow(0)
        prefs.focus["s3"] = MutableStateFlow(2)
        vm.load()
        awaitLoaded(vm)
        vm.startSession()
        await { vm.currentIndex.value == 0 }

        vm.next()
        vm.next()
        await { vm.currentIndex.value == 2 && vm.focusCueIndex.value == 2 }
        assertEquals(2, vm.currentIndex.value)
        assertEquals(2, vm.focusCueIndex.value)
    }

    @Test
    fun focoEhPersistidoPorSecaoNaoPorNota() = withVm(
        FakeSource(
            sections = listOf(
                section("s1", 0, cueCount = 3),
                section("s2", 1, cueCount = 3),
            )
        )
    ) { vm, prefs ->
        vm.load()
        awaitLoaded(vm)
        vm.setFocusCue(2)
        await { prefs.focus.getValue("s1").value == 2 }
        assertTrue(
            prefs.focus["s2"]?.value?.let { it == 0 } ?: true,
        )
    }
}
