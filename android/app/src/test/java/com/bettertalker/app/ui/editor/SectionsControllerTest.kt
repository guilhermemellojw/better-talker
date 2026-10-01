package com.bettertalker.app.ui.editor

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SectionValidationError
import com.bettertalker.app.domain.speech.SectionValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.2.5a: testes headless do [SectionsController].
 *
 * Padrão do projeto (sem `kotlinx-coroutines-test`): fakes de DAO +
 * `runBlocking`, com `debounceMs = 0` (ou 50 no teste de coalescência) e
 * [SectionsController.awaitSaves] / [SectionsController.close] para
 * determinismo (o coletor de Flow é infinito; `close` evita pendurar o
 * `runBlocking`).
 */
class SectionsControllerTest {

    private val nid = "n1"

    // ---------- fakes (StateFlow-backed: observação real) ----------

    /** Runner fake: fakes são atômicos por natureza (sem rollback real). */
    private class FakeTransactionRunner : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    /**
     * Fake de seções que honra `UNIQUE(noteId, order)` (Hotfix P0.2):
     * `upsert` com `order` colidente substitui (REPLACE), como o Room.
     * Sem isso, testes delete+add passavam por acidente (aceitavam duplicata).
     */
    private class FakeSections : SpeechSectionDao {
        val rows = MutableStateFlow<List<SpeechSectionEntity>>(emptyList())
        var upsertCalls = 0
            private set
        /** Simula FK CASCADE: deleteForNote também limpa sub-pontos órfãos. */
        var cascadeSubs: FakeSubPoints? = null

        override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
            rows.value.filter { it.noteId == noteId }.sortedBy { it.order }

        override suspend fun get(id: String): SpeechSectionEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(section: SpeechSectionEntity) {
            upsertCalls++
            rows.value = rows.value.filterNot {
                it.id == section.id ||
                    (it.noteId == section.noteId && it.order == section.order)
            } + section
        }

        override suspend fun upsertAll(sections: List<SpeechSectionEntity>) {
            sections.forEach { upsert(it) }
        }

        override suspend fun delete(section: SpeechSectionEntity) {
            rows.value = rows.value.filterNot { it.id == section.id }
        }

        override suspend fun deleteForNote(noteId: String) {
            val doomedIds = rows.value.filter { it.noteId == noteId }.map { it.id }.toSet()
            rows.value = rows.value.filterNot { it.noteId == noteId }
            // CASCADE simulado (o Room real apaga sub-pontos via FK).
            cascadeSubs?.let { subs ->
                subs.rows.value = subs.rows.value.filterNot { it.sectionId in doomedIds }
            }
        }

        override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
            rows.map { list -> list.filter { it.noteId == noteId }.sortedBy { it.order } }
    }

    /**
     * Fake de sub-pontos que honra `UNIQUE(sectionId, order)` (Hotfix P0.2).
     */
    private class FakeSubPoints : SubPointDao {
        val rows = MutableStateFlow<List<SubPointEntity>>(emptyList())
        val observeArgs = mutableListOf<List<String>>()
        var upsertAllCalls = 0
            private set

        override suspend fun forSection(sectionId: String): List<SubPointEntity> =
            rows.value.filter { it.sectionId == sectionId }.sortedBy { it.order }

        override suspend fun get(id: String): SubPointEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun forSections(sectionIds: List<String>): List<SubPointEntity> =
            rows.value.filter { it.sectionId in sectionIds }
                .sortedWith(compareBy({ it.sectionId }, { it.order }))

        override suspend fun upsert(subPoint: SubPointEntity) {
            rows.value = rows.value.filterNot {
                it.id == subPoint.id ||
                    (it.sectionId == subPoint.sectionId && it.order == subPoint.order)
            } + subPoint
        }

        override suspend fun upsertAll(subPoints: List<SubPointEntity>) {
            upsertAllCalls++
            subPoints.forEach { upsert(it) }
        }

        override suspend fun delete(subPoint: SubPointEntity) {
            rows.value = rows.value.filterNot { it.id == subPoint.id }
        }

        override suspend fun deleteForSection(sectionId: String) {
            rows.value = rows.value.filterNot { it.sectionId == sectionId }
        }

        override fun observeForSections(sectionIds: List<String>): Flow<List<SubPointEntity>> {
            observeArgs += sectionIds
            return rows.map { list ->
                list.filter { it.sectionId in sectionIds }
                    .sortedWith(compareBy({ it.sectionId }, { it.order }))
            }
        }
    }

    // ---------- helpers ----------

    private fun section(
        id: String,
        order: Int,
        role: String = "BODY",
        title: String = "S$order",
        html: String = "",
        minutes: Int = 5,
    ) = SpeechSectionEntity(
        id = id, noteId = nid, order = order, role = role, title = title,
        minutes = minutes, contentHtml = html, bibleRefsJson = "[]",
        publicationRefsJson = "[]", methodPrinciple = null,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun subPoint(id: String, sectionId: String, order: Int, developed: String = "") =
        SubPointEntity(
            id = id, sectionId = sectionId, order = order, outlineText = "ponto $id",
            bibleRefsJson = "[]", publicationRefsJson = "[]", instruction = null,
            developedHtml = developed, createdAt = 1L, updatedAt = 1L,
        )

    // ---------- testes ----------

    @Test
    fun observe_emptyDb_emitsEmptyList() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            assertEquals(emptyList<SectionUiState>(), controller.sections.first())
        } finally {
            controller.close()
        }
    }

    @Test
    fun observe_populatedDb_emitsSectionsWithSubPoints() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "INTRO"), section("s2", 1))
        }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("sp1", "s2", 0, "a"), subPoint("sp2", "s2", 1, "b"))
        }
        val controller = SectionsController(nid, secs, subs, FakeTransactionRunner(), this, debounceMs = 0)
        try {
            val state = controller.sections.first { it.isNotEmpty() }
            assertEquals(listOf("s1", "s2"), state.map { it.section.id })
            assertEquals(0, state[0].subPoints.size)
            assertEquals(listOf("sp1", "sp2"), state[1].subPoints.map { it.id })
        } finally {
            controller.close()
        }
    }

    @Test
    fun observe_ordersByOrder() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s3", 2), section("s1", 0), section("s2", 1))
        }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("sp2", "s3", 1), subPoint("sp1", "s3", 0))
        }
        val controller = SectionsController(nid, secs, subs, FakeTransactionRunner(), this, debounceMs = 0)
        try {
            val state = controller.sections.first { it.isNotEmpty() }
            assertEquals(listOf("s1", "s2", "s3"), state.map { it.section.id })
            assertEquals(listOf("sp1", "sp2"), state.last().subPoints.map { it.id })
        } finally {
            controller.close()
        }
    }

    @Test
    fun onSectionTitle_updatesStateAndSchedulesSave() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0)) }
        val controller = SectionsController(nid, secs, FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.sections.first { it.isNotEmpty() }

            controller.onSectionTitle("s1", "Novo título")
            controller.awaitSaves()

            val state = controller.sections.value.single()
            assertEquals("Novo título", state.section.title)
            assertFalse("isDirty deve zerar após o save", state.isDirty)
            assertFalse("isSaving deve zerar após o save", state.isSaving)
            assertEquals("Novo título", secs.rows.value.single().title)
            assertEquals(1, secs.upsertCalls)
        } finally {
            controller.close()
        }
    }

    @Test
    fun onSectionContent_updatesHtml_andSaves() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "INTRO")) }
        val controller = SectionsController(nid, secs, FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.sections.first { it.isNotEmpty() }

            controller.onSectionContent("s1", "<p>conteúdo</p>")
            controller.awaitSaves()

            assertEquals("<p>conteúdo</p>", secs.rows.value.single().contentHtml)
            assertEquals("<p>conteúdo</p>", controller.sections.value.single().section.contentHtml)
        } finally {
            controller.close()
        }
    }

    @Test
    fun onSubPointContent_updatesDevelopedHtml_andSavesParent() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0)) }
        val subs = FakeSubPoints().apply { rows.value = listOf(subPoint("sp1", "s1", 0)) }
        val controller = SectionsController(nid, secs, subs, FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.sections.first { it.isNotEmpty() }

            controller.onSubPointContent("sp1", "<p>dev</p>")
            controller.awaitSaves()

            assertEquals("<p>dev</p>", subs.rows.value.single().developedHtml)
            assertTrue("sub-pontos precisam ser persistidos", subs.upsertAllCalls >= 1)
            assertEquals(
                "<p>dev</p>",
                controller.sections.value.single().subPoints.single().developedHtml
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun autosave_debounceCoalescesRapidEdits() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0)) }
        val controller = SectionsController(nid, secs, FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 50)
        try {
            controller.sections.first { it.isNotEmpty() }

            controller.onSectionTitle("s1", "A")
            controller.onSectionTitle("s1", "AB")
            controller.onSectionTitle("s1", "ABC")
            controller.awaitSaves()

            assertEquals("ABC", secs.rows.value.single().title)
            assertEquals("edits rápidos devem coalescer em 1 save", 1, secs.upsertCalls)
        } finally {
            controller.close()
        }
    }

    @Test
    fun aggregateHtml_concatenatesCorrectly() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(
                section("i", 0, "INTRO", html = "intro"),
                section("b", 1),
                section("c", 2, "CONCLUSION", html = "fim"),
            )
        }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("p1", "b", 0, "a"), subPoint("p2", "b", 1, "b"))
        }
        val controller = SectionsController(nid, secs, subs, FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.sections.first { it.isNotEmpty() }

            assertEquals("intro\na\nb\nfim", controller.aggregateHtml())
            // Débito 3.2.5a: MD cai no HTML agregado por enquanto.
            assertEquals(controller.aggregateHtml(), controller.aggregateMd())
        } finally {
            controller.close()
        }
    }

    @Test
    fun observeForSections_neverCalledWithEmptyList() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0)) }
        val subs = FakeSubPoints().apply { rows.value = listOf(subPoint("sp1", "s1", 0)) }
        val controller = SectionsController(nid, secs, subs, FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.sections.first { it.isNotEmpty() }

            // Esvazia a nota: guard deve trocar para flowOf(emptyList()) sem
            // chamar o DAO com [] (IN () seria inválido no SQL).
            secs.rows.value = emptyList()
            controller.sections.first { it.isEmpty() }

            assertTrue("DAO deve ter sido observado com ids", subs.observeArgs.isNotEmpty())
            assertTrue("nunca com lista vazia", subs.observeArgs.all { it.isNotEmpty() })
        } finally {
            controller.close()
        }
    }

    // ---------- 3.2.5d: API section-aware ----------

    @Test
    fun queueInsert_appendsToPending() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            assertEquals(emptyList<SectionAwareInsert>(), controller.pendingInserts.value)
            val insert = SectionAwareInsert("s1", null, "## T\n\ncorpo")
            controller.queueInsert(insert)
            controller.queueInsert(SectionAwareInsert("s2", "sp9", "md", "H"))
            assertEquals(listOf(insert, SectionAwareInsert("s2", "sp9", "md", "H")), controller.pendingInserts.value)
        } finally {
            controller.close()
        }
    }

    @Test
    fun consumeInsert_removesFromPending() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            val a = SectionAwareInsert("s1", null, "a")
            val b = SectionAwareInsert("s2", "sp1", "b")
            controller.queueInsert(a)
            controller.queueInsert(b)
            controller.consumeInsert(a)
            assertEquals(listOf(b), controller.pendingInserts.value)
        } finally {
            controller.close()
        }
    }

    @Test
    fun consumeInsert_differentInstance_doesNotRemove() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.queueInsert(SectionAwareInsert("s1", null, "a"))
            // data class: igualdade é por valor — instância igual remove;
            // conteúdo diferente não remove.
            controller.consumeInsert(SectionAwareInsert("s1", null, "outro"))
            assertEquals(1, controller.pendingInserts.value.size)
            controller.consumeInsert(SectionAwareInsert("s1", null, "a"))
            assertEquals(emptyList<SectionAwareInsert>(), controller.pendingInserts.value)
        } finally {
            controller.close()
        }
    }

    @Test
    fun onSelectionChange_updatesSelectionFlow() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            assertEquals(null, controller.selection.value)
            val ctx = SelectionContext("s1", "sp1", "trecho", "<p>trecho</p>")
            controller.onSelectionChange(ctx)
            assertEquals(ctx, controller.selection.value)
        } finally {
            controller.close()
        }
    }

    @Test
    fun onSelectionChange_null_clearsSelection() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            controller.onSelectionChange(SelectionContext("s1", null, "x", "<p>x</p>"))
            controller.onSelectionChange(null)
            assertEquals(null, controller.selection.value)
        } finally {
            controller.close()
        }
    }

    // ---------- 3.2.5f-pre: revalidação estrutural ----------

    @Test
    fun validationResult_nullWhenSectionsEmpty() = runBlocking {
        val controller = SectionsController(nid, FakeSections(), FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            assertEquals(null, controller.validationResult.first())
        } finally {
            controller.close()
        }
    }

    @Test
    fun validationResult_updatesWhenSectionsChange() = runBlocking {
        val secs = FakeSections()
        val controller = SectionsController(nid, secs, FakeSubPoints(), FakeTransactionRunner(), this, debounceMs = 0)
        try {
            assertEquals(null, controller.validationResult.first())
            // 2 BODYs num MINISTRY_PART: inválido (o tipo vem do VM via setter).
            controller.discourseType = DiscourseType.MINISTRY_PART
            secs.rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"))
            val result = controller.validationResult.first { it != null }
            assertTrue(result is SectionValidationResult.Invalid)
            assertTrue(
                (result as SectionValidationResult.Invalid).errors
                    .contains(SectionValidationError.WRONG_BODY_COUNT)
            )
        } finally {
            controller.close()
        }
    }

    // ---------- 3.2.5f.1: CRUD ----------

    private fun crudController(
        secs: FakeSections,
        subs: FakeSubPoints,
        scope: kotlinx.coroutines.CoroutineScope,
        ids: MutableList<String> = mutableListOf(),
    ) = SectionsController(
        nid, secs, subs, FakeTransactionRunner(), scope,
        debounceMs = 0,
        idProvider = { "new-${ids.size.also { ids.add("x$it") }}" },
        clock = { 1000L },
    ).also { secs.cascadeSubs = subs }

    @Test
    fun addSection_appendsAtEnd() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            val created = controller.addSection(SectionRole.BODY)
            assertEquals("new-0", created.id)
            assertEquals(1, created.order)
            assertEquals("Novo ponto", created.title)
            assertEquals(5, created.minutes)
            val state = controller.sections.value
            assertEquals(listOf("s1", "new-0"), state.map { it.section.id })
            assertEquals(listOf(0, 1), state.map { it.section.order })
            controller.awaitSaves()
            assertTrue(secs.rows.value.any { it.id == "new-0" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun addSection_afterSection_insertsAtIndex() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.addSection(SectionRole.INTRO, afterSectionId = "s1")
            val state = controller.sections.value
            assertEquals(listOf("s1", "new-0", "s2"), state.map { it.section.id })
            assertEquals(listOf(0, 1, 2), state.map { it.section.order })
            assertEquals(SectionRole.INTRO, state[1].section.role)
        } finally {
            controller.close()
        }
    }

    @Test
    fun addSection_renormalizesOrders() = runBlocking {
        val secs = FakeSections().apply {
            // ordens quebradas no banco (legado): o add renormaliza tudo.
            rows.value = listOf(section("s1", 5, "BODY"), section("s2", 9, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.addSection(SectionRole.CONCLUSION)
            assertEquals(
                listOf(0, 1, 2),
                controller.sections.value.map { it.section.order }
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun removeSection_removesFromState_andDeletesOnDao() {
        runBlocking {
            val secs = FakeSections().apply {
                rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"))
            }
            val controller = crudController(secs, FakeSubPoints(), this)
            try {
                controller.sections.first { it.isNotEmpty() }
                controller.removeSection("s1")
                assertEquals(listOf("s2"), controller.sections.value.map { it.section.id })
                assertEquals(listOf(0), controller.sections.value.map { it.section.order })
                secs.rows.first { list -> list.none { it.id == "s1" } }
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun removeSection_renormalizesOrders() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"), section("s3", 2, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.removeSection("s2")
            val state = controller.sections.value
            assertEquals(listOf("s1", "s3"), state.map { it.section.id })
            assertEquals(listOf(0, 1), state.map { it.section.order })
        } finally {
            controller.close()
        }
    }

    @Test
    fun moveSection_up_swapsWithPrevious() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.moveSection("s2", MoveDirection.UP)
            val state = controller.sections.value
            assertEquals(listOf("s2", "s1"), state.map { it.section.id })
            assertEquals(listOf(0, 1), state.map { it.section.order })
            controller.awaitSaves()
        } finally {
            controller.close()
        }
    }

    @Test
    fun moveSection_down_swapsWithNext() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.moveSection("s1", MoveDirection.DOWN)
            assertEquals(
                listOf("s2", "s1"),
                controller.sections.value.map { it.section.id }
            )
            controller.awaitSaves()
        } finally {
            controller.close()
        }
    }

    @Test
    fun moveSection_atExtremes_noOp() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.moveSection("s1", MoveDirection.UP)
            controller.moveSection("s2", MoveDirection.DOWN)
            controller.moveSection("nope", MoveDirection.UP)
            assertEquals(
                listOf("s1", "s2"),
                controller.sections.value.map { it.section.id }
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun updateSectionRole_changesRole() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.updateSectionRole("s1", SectionRole.INTRO)
            assertEquals(SectionRole.INTRO, controller.sections.value.single().section.role)
            controller.awaitSaves()
            assertEquals("INTRO", secs.rows.value.single().role)
        } finally {
            controller.close()
        }
    }

    @Test
    fun addSubPoint_appendsAtEnd() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val subs = FakeSubPoints().apply { rows.value = listOf(subPoint("sp1", "s1", 0)) }
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            val created = controller.addSubPoint("s1")
            assertEquals("new-0", created?.id)
            assertEquals("s1", created?.sectionId)
            val points = controller.sections.value.single().subPoints
            assertEquals(listOf("sp1", "new-0"), points.map { it.id })
            assertEquals(listOf(0, 1), points.map { it.order })
            controller.awaitSaves()
            assertTrue(subs.rows.value.any { it.id == "new-0" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun addSubPoint_afterSubPoint_insertsAtIndex() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("sp1", "s1", 0), subPoint("sp2", "s1", 1))
        }
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.addSubPoint("s1", afterSubPointId = "sp1")
            assertEquals(
                listOf("sp1", "new-0", "sp2"),
                controller.sections.value.single().subPoints.map { it.id }
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun removeSubPoint_removesFromState_andDeletesOnDao() {
        runBlocking {
            val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
            val subs = FakeSubPoints().apply {
                rows.value = listOf(subPoint("sp1", "s1", 0), subPoint("sp2", "s1", 1))
            }
            val controller = crudController(secs, subs, this)
            try {
                controller.sections.first { it.isNotEmpty() }
                controller.removeSubPoint("sp1")
                val points = controller.sections.value.single().subPoints
                assertEquals(listOf("sp2"), points.map { it.id })
                assertEquals(listOf(0), points.map { it.order })
                subs.rows.first { list -> list.none { it.id == "sp1" } }
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun moveSubPoint_up_swaps() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("sp1", "s1", 0), subPoint("sp2", "s1", 1))
        }
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.moveSubPoint("sp2", MoveDirection.UP)
            val points = controller.sections.value.single().subPoints
            assertEquals(listOf("sp2", "sp1"), points.map { it.id })
            assertEquals(listOf(0, 1), points.map { it.order })
            controller.awaitSaves()
        } finally {
            controller.close()
        }
    }

    @Test
    fun updateSubPointOutlineText_changesText() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val subs = FakeSubPoints().apply { rows.value = listOf(subPoint("sp1", "s1", 0)) }
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.updateSubPointOutlineText("sp1", "novo texto")
            assertEquals(
                "novo texto",
                controller.sections.value.single().subPoints.single().outlineText
            )
            controller.awaitSaves()
            assertEquals("novo texto", subs.rows.value.single().outlineText)
        } finally {
            controller.close()
        }
    }

    // ---------- Hotfix P0.2: tripwires de perda silenciosa (delete+add) ----------

    @Test
    fun removeSection_persistsRenormalizedOrders() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"), section("s3", 2, "BODY"))
        }
        val controller = crudController(secs, FakeSubPoints(), this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.removeSection("s2")
            controller.awaitSaves()
            // Aguarda convergência do Flow após delete+reinsert atômico.
            val persisted = secs.rows.first { list ->
                list.size == 2 && list.none { it.id == "s2" }
            }.sortedBy { it.order }
            assertEquals(listOf("s1", "s3"), persisted.map { it.id })
            assertEquals(listOf(0, 1), persisted.map { it.order })
        } finally {
            controller.close()
        }
    }

    @Test
    fun addSection_afterRemove_doesNotDeleteAnySection() = runBlocking {
        val secs = FakeSections().apply {
            rows.value = listOf(section("s1", 0, "BODY"), section("s2", 1, "BODY"), section("s3", 2, "BODY"))
        }
        val subs = FakeSubPoints()
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.removeSection("s2")
            controller.awaitSaves()
            secs.rows.first { list -> list.size == 2 && list.none { it.id == "s2" } }

            controller.addSection(SectionRole.BODY)
            controller.awaitSaves()
            val persisted = secs.rows.first { list -> list.size == 3 }.sortedBy { it.order }
            assertEquals(3, persisted.size)
            assertEquals(listOf(0, 1, 2), persisted.map { it.order })
            assertTrue(persisted.any { it.id == "s1" })
            assertTrue(persisted.any { it.id == "s3" })
            assertTrue(persisted.any { it.id == "new-0" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun removeSubPoint_persistsRenormalizedOrders() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("sp1", "s1", 0), subPoint("sp2", "s1", 1), subPoint("sp3", "s1", 2))
        }
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.removeSubPoint("sp2")
            controller.awaitSaves()
            val persisted = subs.rows.first { list ->
                list.size == 2 && list.none { it.id == "sp2" }
            }.sortedBy { it.order }
            assertEquals(listOf("sp1", "sp3"), persisted.map { it.id })
            assertEquals(listOf(0, 1), persisted.map { it.order })
        } finally {
            controller.close()
        }
    }

    @Test
    fun addSubPoint_afterRemove_doesNotDeleteAnySubPoint() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, "BODY")) }
        val subs = FakeSubPoints().apply {
            rows.value = listOf(subPoint("sp1", "s1", 0), subPoint("sp2", "s1", 1), subPoint("sp3", "s1", 2))
        }
        val controller = crudController(secs, subs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            controller.removeSubPoint("sp2")
            controller.awaitSaves()
            subs.rows.first { list -> list.size == 2 && list.none { it.id == "sp2" } }

            controller.addSubPoint("s1")
            controller.awaitSaves()
            val persisted = subs.rows.first { list -> list.size == 3 }.sortedBy { it.order }
            assertEquals(3, persisted.size)
            assertEquals(listOf(0, 1, 2), persisted.map { it.order })
            assertTrue(persisted.any { it.id == "sp1" })
            assertTrue(persisted.any { it.id == "sp3" })
            assertTrue(persisted.any { it.id == "new-0" })
        } finally {
            controller.close()
        }
    }
}
