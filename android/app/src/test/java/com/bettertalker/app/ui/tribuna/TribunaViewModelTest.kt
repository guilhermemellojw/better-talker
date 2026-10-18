package com.bettertalker.app.ui.tribuna

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #20-fix-b — a Tribuna: load, edits com autosave debounced e TTS só da
 * âncora. Sem Context/Room: fakes + debounce 0 + escopo próprio.
 */
class TribunaViewModelTest {

    private class FakeDao(initial: List<SpeechSectionEntity>) : SpeechSectionDao {
        val rows = MutableStateFlow(initial)
        override suspend fun forNote(noteId: String) =
            rows.value.filter { it.noteId == noteId }.sortedBy { it.order }
        override suspend fun get(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(section: SpeechSectionEntity) {
            rows.value = rows.value.filterNot { it.id == section.id } + section
        }
        override suspend fun upsertAll(sections: List<SpeechSectionEntity>) {
            sections.forEach { upsert(it) }
        }
        override suspend fun delete(section: SpeechSectionEntity) {
            rows.value = rows.value.filterNot { it.id == section.id }
        }
        override suspend fun deleteForNote(noteId: String) {
            rows.value = rows.value.filterNot { it.noteId == noteId }
        }
        override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
            rows.map { list -> list.filter { it.noteId == noteId }.sortedBy { it.order } }
    }

    private class FakeSpeaker : TribunaSpeaker {
        val spoken = mutableListOf<String>()
        val state = MutableStateFlow(false)
        override fun speak(text: String) {
            spoken += text
            state.value = true
        }
        override fun stop() { state.value = false }
        override fun shutdown() {}
        override val speaking: Flow<Boolean> = state
    }

    private class FakePrefs : TribunaFontPrefs {
        var set: Int? = null
        override val fontSp: Flow<Int> = flowOf(24)
        override suspend fun setFontSp(v: Int) { set = v }
    }

    private fun entity(id: String, order: Int, tribuna: String? = null) = SpeechSectionEntity(
        id = id, noteId = "n1", order = order, role = "BODY", title = "PONTO $order",
        minutes = 5, contentHtml = "<p>x</p>", bibleRefsJson = "[]",
        publicationRefsJson = "[]", methodPrinciple = null, tribunaJson = tribuna,
        createdAt = 1, updatedAt = 2,
    )

    private fun capsule(id: String, anchor: String = "Âncora $id") = TribunaCapsule(
        sectionId = id, mainIdea = "Ideia $id", anchorPhrase = anchor,
    )

    /** Roda um bloco com escopo próprio (o debounce assenta antes do fim). */
    private fun withVm(
        dao: FakeDao,
        speaker: FakeSpeaker = FakeSpeaker(),
        isDemonstration: Boolean = false,
        block: suspend (TribunaViewModel, FakeSpeaker, FakeDao) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val vm = TribunaViewModel(
                dao, "n1", speaker, FakePrefs(), debounceMs = 0, scope = scope,
                isDemonstrationProvider = { isDemonstration },
            )
            block(vm, speaker, dao)
            delay(60)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadComDuasSecoesComTribuna() = withVm(
        FakeDao(
            listOf(
                entity("s1", 0, capsule("s1").toJson()),
                entity("s2", 1, capsule("s2").toJson()),
            )
        )
    ) { vm, _, _ ->
        vm.load()
        delay(30)
        assertEquals(2, vm.cards.value.size)
        assertEquals("Âncora s1", vm.cards.value[0].capsule?.anchorPhrase)
        assertEquals("Ideia s2", vm.cards.value[1].capsule?.mainIdea)
    }

    @Test
    fun loadComSecaoSemTribuna() = withVm(FakeDao(listOf(entity("s1", 0, null)))) { vm, _, _ ->
        vm.load()
        delay(30)
        assertEquals(1, vm.cards.value.size)
        assertNull(vm.cards.value[0].capsule)
    }

    @Test
    fun updateAnchorAtualizaLocalESalva() = withVm(
        FakeDao(listOf(entity("s1", 0, capsule("s1").toJson())))
    ) { vm, _, dao ->
        vm.load()
        delay(30)
        vm.updateAnchorPhrase("s1", "Nova âncora")
        assertEquals("Nova âncora", vm.cards.value[0].capsule?.anchorPhrase)
        delay(40)
        assertTrue(
            dao.rows.value.firstOrNull { it.id == "s1" }
                ?.tribunaJson?.contains("Nova âncora") == true
        )
    }

    @Test
    fun updateMainIdeaVaziaPersisteVazio() = withVm(
        FakeDao(listOf(entity("s1", 0, capsule("s1").toJson())))
    ) { vm, _, _ ->
        vm.load()
        delay(30)
        vm.updateMainIdea("s1", "")
        assertEquals("", vm.cards.value[0].capsule?.mainIdea)
    }

    @Test
    fun speakAnchorChamaTtsComAAncora() = withVm(
        FakeDao(listOf(entity("s1", 0, capsule("s1", "Frase ouro").toJson())))
    ) { vm, speaker, _ ->
        vm.load()
        delay(30)
        vm.speakAnchor("s1")
        delay(40)
        assertEquals(listOf("Frase ouro"), speaker.spoken)
        assertEquals("s1", vm.speakingId.value)
    }

    @Test
    fun speakAnchorSemCapsulaNaoChamaTts() = withVm(
        FakeDao(listOf(entity("s1", 0, null)))
    ) { vm, speaker, _ ->
        vm.load()
        delay(30)
        vm.speakAnchor("s1")
        delay(40)
        assertTrue(speaker.spoken.isEmpty())
        assertNull(vm.speakingId.value)
    }

    // ---------- Bloco 2 (demos): a Tribuna vira roteiro ----------

    @Test
    fun demo_carregaTurnosDoDialogo() = withVm(
        FakeDao(
            listOf(
                entity("s1", 0, null).copy(
                    contentHtml = "<p><b>Publicador:</b> Ola, bom dia!</p>" +
                        "<p><b>Morador:</b> Bom dia, tudo bem?</p>",
                ),
            )
        ),
        isDemonstration = true,
    ) { vm, _, _ ->
        vm.load()
        delay(30)
        assertTrue(vm.isDemonstration.value)
        val turns = vm.cards.value[0].turns
        assertEquals(2, turns.size)
        assertEquals("Publicador", turns[0].speaker)
        assertEquals("Ola, bom dia!", turns[0].text)
        assertEquals("Morador", turns[1].speaker)
        assertEquals("Bom dia, tudo bem?", turns[1].text)
    }

    @Test
    fun discurso_naoCarregaTurnos() = withVm(
        FakeDao(listOf(entity("s1", 0, capsule("s1").toJson())))
    ) { vm, _, _ ->
        vm.load()
        delay(30)
        assertTrue(!vm.isDemonstration.value)
        assertTrue(vm.cards.value[0].turns.isEmpty())
        assertEquals("Âncora s1", vm.cards.value[0].capsule?.anchorPhrase)
    }
}
