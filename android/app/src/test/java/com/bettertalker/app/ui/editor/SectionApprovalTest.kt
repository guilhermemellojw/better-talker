package com.bettertalker.app.ui.editor

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3a: flag "seção aprovada" — controller persiste via DAO + emite estado,
 * e o indicador de progresso deriva da lista (puro/testável).
 *
 * Mesmo padrão do [SectionsControllerTest]: fakes StateFlow-backed +
 * `runBlocking`, `debounceMs = 0` + `awaitSaves`/`close`.
 */
class SectionApprovalTest {

    private val nid = "n1"

    private class FakeTransactionRunner : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    private class FakeSections : SpeechSectionDao {
        val rows = MutableStateFlow<List<SpeechSectionEntity>>(emptyList())

        override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
            rows.value.filter { it.noteId == noteId }.sortedBy { it.order }

        override suspend fun get(id: String): SpeechSectionEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(section: SpeechSectionEntity) {
            rows.value = rows.value.filterNot {
                it.id == section.id ||
                    (it.noteId == section.noteId && it.order == section.order)
            } + section
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

    private class FakeSubPoints : SubPointDao {
        val rows = MutableStateFlow<List<SubPointEntity>>(emptyList())

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
            subPoints.forEach { upsert(it) }
        }

        override suspend fun delete(subPoint: SubPointEntity) {
            rows.value = rows.value.filterNot { it.id == subPoint.id }
        }

        override suspend fun deleteForSection(sectionId: String) {
            rows.value = rows.value.filterNot { it.sectionId == sectionId }
        }

        override fun observeForSections(sectionIds: List<String>): Flow<List<SubPointEntity>> =
            rows.map { list ->
                list.filter { it.sectionId in sectionIds }
                    .sortedWith(compareBy({ it.sectionId }, { it.order }))
            }
    }

    private fun section(id: String, order: Int, approvedAt: Long? = null) =
        SpeechSectionEntity(
            id = id, noteId = nid, order = order, role = "BODY", title = "S$order",
            minutes = 5, contentHtml = "", bibleRefsJson = "[]",
            publicationRefsJson = "[]", methodPrinciple = null,
            approvedAt = approvedAt, createdAt = 1L, updatedAt = 1L,
        )

    private fun controller(
        secs: FakeSections,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SectionsController(
        nid, secs, FakeSubPoints(), FakeTransactionRunner(), scope,
        debounceMs = 0, clock = { 777L },
    )

    @Test
    fun approveSection_marcaTimestampNoEstadoENoBanco() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0)) }
        val controller = controller(secs, this)
        try {
            controller.sections.first { it.isNotEmpty() }

            controller.approveSection("s1")
            controller.awaitSaves()

            assertEquals(777L, controller.sections.value.single().section.approvedAt)
            assertEquals(777L, secs.rows.value.single().approvedAt)
        } finally {
            controller.close()
        }
    }

    @Test
    fun unapproveSection_voltaANullNoEstadoENoBanco() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0, approvedAt = 123L)) }
        val controller = controller(secs, this)
        try {
            controller.sections.first { it.isNotEmpty() }
            assertEquals(123L, controller.sections.value.single().section.approvedAt)

            controller.unapproveSection("s1")
            controller.awaitSaves()

            assertNull(controller.sections.value.single().section.approvedAt)
            assertNull(secs.rows.value.single().approvedAt)
        } finally {
            controller.close()
        }
    }

    @Test
    fun approveSection_idInexistente_naoFazNada() = runBlocking {
        val secs = FakeSections().apply { rows.value = listOf(section("s1", 0)) }
        val controller = controller(secs, this)
        try {
            controller.sections.first { it.isNotEmpty() }

            controller.approveSection("nope")
            controller.awaitSaves()

            assertNull(controller.sections.value.single().section.approvedAt)
        } finally {
            controller.close()
        }
    }
}
