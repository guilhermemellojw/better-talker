package com.bettertalker.app.ui.tribuna

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.domain.speech.TribunaDetail
import com.bettertalker.app.domain.speech.TribunaMapStatus
import com.bettertalker.app.domain.speech.toJson
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
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
        override suspend fun updateObjective(id: String, objective: String?, updatedAt: Long) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(objective = objective, updatedAt = updatedAt) else it
            }
        }
        override suspend fun updateAgreedApproach(id: String, agreedApproach: String?, updatedAt: Long) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(agreedApproach = agreedApproach, updatedAt = updatedAt) else it
            }
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

    private class FakeDetailPrefs : TribunaDetailPrefs {
        val note = MutableStateFlow(TribunaDetail.STANDARD)
        val sections = mutableMapOf<String, MutableStateFlow<TribunaDetail?>>()
        override fun noteDetail(noteId: String): Flow<TribunaDetail> = note
        override fun sectionDetail(noteId: String, sectionId: String): Flow<TribunaDetail?> =
            sections.getOrPut(sectionId) { MutableStateFlow(null) }
        override suspend fun setNoteDetail(noteId: String, detail: TribunaDetail) {
            note.value = detail
        }
        override suspend fun setSectionDetail(
            noteId: String,
            sectionId: String,
            detail: TribunaDetail,
        ) {
            sections.getOrPut(sectionId) { MutableStateFlow(null) }.value = detail
        }
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
        detailPrefs: FakeDetailPrefs? = null,
        block: suspend (TribunaViewModel, FakeSpeaker, FakeDao) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val vm = TribunaViewModel(
                dao, "n1", speaker, FakePrefs(), debounceMs = 0, scope = scope,
                isDemonstrationProvider = { isDemonstration },
                detailPrefs = detailPrefs,
            )
            block(vm, speaker, dao)
            delay(60)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitCards(vm: TribunaViewModel) {
        val deadline = System.currentTimeMillis() + 2_000
        while (vm.cards.value.isEmpty() && System.currentTimeMillis() < deadline) delay(10)
    }

    /** Espera o autosave gravar uma cápsula que satisfaça [predicate]. */
    private suspend fun awaitCapsule(
        dao: FakeDao,
        predicate: (TribunaCapsule) -> Boolean,
    ): TribunaCapsule? {
        var parsed: TribunaCapsule? = null
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            parsed = dao.rows.value.firstOrNull()?.tribunaJson
                ?.let { tribunaCapsuleFromJson(it) }
            if (parsed != null && predicate(parsed)) return parsed
            delay(10)
        }
        return parsed
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

    // ---------- v4.2: cues, completude e fallback ----------

    @Test
    fun mapaCompletoSoComCuesPreenchidasEAncora() {
        val full = TribunaCapsule(
            sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
            cues = listOf(
                TribunaCue(TribunaCueKind.HOOK, "Abertura"),
                TribunaCue(TribunaCueKind.APPLICATION, "Aplicar"),
            ),
        )
        val emptyCue = full.copy(cues = full.cues + TribunaCue(TribunaCueKind.RECAP, " "))
        val cardFull = TribunaViewModel.TribunaCardUi(
            sectionId = "s1", order = 0, sectionTitle = "T", minutes = 5, capsule = full,
        )
        val cardEmpty = cardFull.copy(capsule = emptyCue)
        assertTrue(cardFull.mapComplete)
        assertTrue(!cardEmpty.mapComplete)
        assertTrue(cardFull.copy(capsule = capsule("s1")).legacyMap)
    }

    @Test
    fun editarCuePersisteTextoERefs() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cues = listOf(
                            TribunaCue(TribunaCueKind.SCRIPTURE, "Texto", refs = listOf("Sal 1:1")),
                        ),
                    ).toJson(),
                ),
            )
        )
    ) { vm, _, dao ->
        vm.load()
        // Espera a carga inicial no CI lento; editar antes de `load` perderia
        // a cápsula e o autosave não teria o que persistir.
        val loadDeadline = System.currentTimeMillis() + 2_000
        while (vm.cards.value.none { it.sectionId == "s1" } &&
            System.currentTimeMillis() < loadDeadline
        ) {
            delay(10)
        }
        assertTrue(vm.cards.value.any { it.sectionId == "s1" })
        vm.updateCueText("s1", 0, "Texto novo")
        vm.updateCueRefs("s1", 0, listOf("Sal 2:2", "Ef 1:1"))
        // Autosave é assíncrono; no CI o debounce 0 ainda pode atrasar.
        var parsed: com.bettertalker.app.domain.speech.TribunaCapsule? = null
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            parsed = dao.rows.value.first().tribunaJson?.let {
                com.bettertalker.app.domain.speech.tribunaCapsuleFromJson(it)
            }
            if (parsed?.cues?.firstOrNull()?.text == "Texto novo" &&
                parsed?.cues?.firstOrNull()?.refs == listOf("Sal 2:2", "Ef 1:1")
            ) {
                break
            }
            delay(10)
        }
        assertEquals("Texto novo", parsed?.cues?.first()?.text)
        assertEquals(listOf("Sal 2:2", "Ef 1:1"), parsed?.cues?.first()?.refs)
    }

    // ---------- v5.1: edição completa da folha, mapa vazio e detalhe ----------

    @Test
    fun addCueConverteCapsulaAntigaEPreservaCabecalho() = withVm(
        FakeDao(listOf(entity("s1", 0, capsule("s1", "Âncora ouro").toJson())))
    ) { vm, _, dao ->
        vm.load()
        awaitCards(vm)
        vm.addCue("s1", TribunaCueKind.HOOK)
        val card = vm.cards.value.first { it.sectionId == "s1" }
        val c = card.capsule!!
        assertEquals(2, c.cueSheetVersion)
        assertEquals("Âncora ouro", c.anchorPhrase)
        assertEquals("Ideia s1", c.mainIdea)
        assertEquals(1, c.cues.size)
        assertEquals(TribunaCueKind.HOOK, c.cues[0].kind)
        assertTrue(!card.legacyMap)

        val parsed = awaitCapsule(dao) { it.cueSheetVersion == 2 && it.cues.size == 1 }
        assertEquals("Âncora ouro", parsed?.anchorPhrase)
        assertEquals(TribunaCueKind.HOOK, parsed?.cues?.first()?.kind)
    }

    @Test
    fun removeCueRemoveSoOLembreteERestaurarDesfaz() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2,
                        cues = listOf(
                            TribunaCue(TribunaCueKind.HOOK, "Abertura"),
                            TribunaCue(TribunaCueKind.APPLICATION, "Aplicar"),
                        ),
                    ).toJson(),
                ),
            )
        )
    ) { vm, _, _ ->
        vm.load()
        awaitCards(vm)
        val before = vm.cards.value.first().capsule!!
        vm.removeCue("s1", 0)
        val after = vm.cards.value.first().capsule!!
        assertEquals(1, after.cues.size)
        assertEquals("Aplicar", after.cues[0].text)
        assertEquals("Âncora", after.anchorPhrase)

        vm.restoreCapsule("s1", before)
        assertEquals(before, vm.cards.value.first().capsule)
    }

    @Test
    fun moveCueTrocaVizinhosENaoPassaDosLimites() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2,
                        cues = listOf(
                            TribunaCue(TribunaCueKind.HOOK, "A"),
                            TribunaCue(TribunaCueKind.REASONING, "B"),
                            TribunaCue(TribunaCueKind.APPLICATION, "C"),
                        ),
                    ).toJson(),
                ),
            )
        )
    ) { vm, _, _ ->
        vm.load()
        awaitCards(vm)
        fun texts() = vm.cards.value.first().capsule!!.cues.map { it.text }

        vm.moveCue("s1", 0, 1)
        assertEquals(listOf("B", "A", "C"), texts())

        vm.moveCue("s1", 2, 1)
        assertEquals(listOf("B", "A", "C"), texts())

        vm.moveCue("s1", 0, -1)
        assertEquals(listOf("B", "A", "C"), texts())

        vm.moveCue("s1", 1, -1)
        assertEquals(listOf("A", "B", "C"), texts())
    }

    @Test
    fun editarTipoImagemTermosETempoPersiste() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2,
                        cues = listOf(TribunaCue(TribunaCueKind.REASONING, "Texto")),
                    ).toJson(),
                ),
            )
        )
    ) { vm, _, dao ->
        vm.load()
        awaitCards(vm)
        vm.updateCueKind("s1", 0, TribunaCueKind.IMAGE)
        vm.updateCueImage("s1", 0, "img-1", "Legenda")
        vm.updateKeyTerms("s1", listOf(" fé ", "", "amor"))
        vm.updateTimeMinutes("s1", 0)
        assertNull(vm.cards.value.first().capsule?.timeMinutes)
        vm.updateTimeMinutes("s1", 7)

        val local = vm.cards.value.first().capsule!!
        assertEquals(TribunaCueKind.IMAGE, local.cues[0].kind)
        assertEquals("img-1", local.cues[0].imageId)
        assertEquals("Legenda", local.cues[0].imageCaption)
        assertEquals(listOf("fé", "amor"), local.keyTerms)
        assertEquals(7, local.timeMinutes)

        val parsed = awaitCapsule(dao) {
            it.cues.firstOrNull()?.kind == TribunaCueKind.IMAGE &&
                it.timeMinutes == 7 &&
                it.keyTerms == listOf("fé", "amor")
        }
        assertEquals("img-1", parsed?.cues?.first()?.imageId)
        assertEquals(listOf("fé", "amor"), parsed?.keyTerms)
    }

    @Test
    fun converterCapsulaAntigaCriaMapaVazioSemPerderCabecalho() = withVm(
        FakeDao(listOf(entity("s1", 0, capsule("s1", "Âncora ouro").toJson())))
    ) { vm, _, dao ->
        vm.load()
        awaitCards(vm)
        vm.convertToCueSheet("s1")
        val card = vm.cards.value.first()
        assertTrue(card.emptyCueSheet)
        assertTrue(!card.legacyMap)
        assertEquals(TribunaMapStatus.INCOMPLETE, card.quality.status)
        assertTrue(card.quality.reasons.contains("sem lembretes"))
        assertEquals("Âncora ouro", card.capsule?.anchorPhrase)

        val parsed = awaitCapsule(dao) { it.cueSheetVersion == 2 }
        assertEquals(2, parsed?.cueSheetVersion)
        assertTrue(parsed?.cues?.isEmpty() == true)
        assertEquals("Âncora ouro", parsed?.anchorPhrase)
    }

    @Test
    fun folhaV2VaziaVemDoBancoComoMapaVazioENaoComoLegado() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2,
                    ).toJson(),
                ),
            )
        )
    ) { vm, _, _ ->
        vm.load()
        awaitCards(vm)
        val card = vm.cards.value.first()
        assertTrue(card.emptyCueSheet)
        assertTrue(!card.legacyMap)
        assertEquals(TribunaMapStatus.INCOMPLETE, card.quality.status)
    }

    @Test
    fun secoesComRefsSemRefsNosLembretesFicamIncompletas() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2, keyTerms = listOf("termo"),
                        cues = listOf(TribunaCue(TribunaCueKind.REASONING, "Argumento")),
                    ).toJson(),
                ).copy(bibleRefsJson = "[\"Sal 1:1\"]"),
            )
        )
    ) { vm, _, _ ->
        vm.load()
        awaitCards(vm)
        val card = vm.cards.value.first()
        assertTrue(card.sectionHasRefs)
        assertEquals(TribunaMapStatus.INCOMPLETE, card.quality.status)
        assertTrue(card.quality.reasons.contains("sem refs"))
    }

    @Test
    fun transicaoSoEhExigidaForaDaUltimaSecao() = withVm(
        FakeDao(
            listOf(
                entity(
                    "s1", 0,
                    TribunaCapsule(
                        sectionId = "s1", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2, keyTerms = listOf("termo"),
                        cues = listOf(TribunaCue(TribunaCueKind.REASONING, "Argumento")),
                    ).toJson(),
                ),
                entity(
                    "s2", 1,
                    TribunaCapsule(
                        sectionId = "s2", mainIdea = "Ideia", anchorPhrase = "Âncora",
                        cueSheetVersion = 2, keyTerms = listOf("termo"),
                        cues = listOf(TribunaCue(TribunaCueKind.APPLICATION, "Aplicar")),
                    ).toJson(),
                ),
            )
        )
    ) { vm, _, _ ->
        vm.load()
        awaitCards(vm)
        val first = vm.cards.value[0]
        val last = vm.cards.value[1]
        assertTrue(!first.isLastSection)
        assertTrue(first.quality.reasons.contains("sem transição"))
        assertEquals(TribunaMapStatus.INCOMPLETE, first.quality.status)
        assertTrue(last.isLastSection)
        assertEquals(TribunaMapStatus.COMPLETE, last.quality.status)
    }

    @Test
    fun detalheDaNotaEDaSecaoPersistemNoSeam() {
        val detailPrefs = FakeDetailPrefs()
        withVm(
            FakeDao(listOf(entity("s1", 0, capsule("s1").toJson()))),
            detailPrefs = detailPrefs,
        ) { vm, _, _ ->
            vm.setNoteDetail(TribunaDetail.DETAILED)
            vm.setSectionDetail("s1", TribunaDetail.COMPACT)
            delay(40)
            assertEquals(TribunaDetail.DETAILED, detailPrefs.note.value)
            assertEquals(TribunaDetail.COMPACT, detailPrefs.sections["s1"]?.value)
            assertEquals(TribunaDetail.DETAILED, vm.noteDetail.value)
        }
    }
}
