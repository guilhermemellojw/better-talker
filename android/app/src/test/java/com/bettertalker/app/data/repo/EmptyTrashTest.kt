package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.work.FakeNoteDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lixeira: seleção e exclusão em lote, sem Room/Android. */
class EmptyTrashTest {

    private fun note(id: String, trashed: Boolean) = NoteEntity(
        id = id,
        title = id,
        mdText = "",
        plainText = "",
        folderId = null,
        colorArgb = 0,
        pinned = false,
        trashed = trashed,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun emptyTrash_retorna_0_se_lixeira_vazia() = runBlocking {
        val dao = FakeNoteDao(listOf(note("ativa", trashed = false)))
        val deleted = mutableListOf<String>()

        val count = emptyTrashSelection(dao) { deleted += it }

        assertEquals(0, count)
        assertTrue(deleted.isEmpty())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun emptyTrash_deleta_todas_as_notas_trashed() = runBlocking {
        val dao = FakeNoteDao(
            listOf(
                note("a", trashed = true),
                note("b", trashed = true),
                note("ativa", trashed = false),
            )
        )
        val deleted = mutableListOf<String>()

        val count = emptyTrashSelection(dao) {
            deleted += it
            dao.delete(it)
        }

        assertEquals(2, count)
        assertEquals(listOf("a", "b"), deleted)
        assertEquals(0, dao.countTrashed())
        assertEquals(1, dao.rows.size)
        assertFalse(dao.rows.single().trashed)
    }

    @Test
    fun emptyTrash_nao_deleta_notas_ativas() = runBlocking {
        val dao = FakeNoteDao(
            listOf(
                note("trashed", trashed = true),
                note("ativa-1", trashed = false),
                note("ativa-2", trashed = false),
            )
        )
        val deleted = mutableListOf<String>()

        emptyTrashSelection(dao) {
            deleted += it
            dao.delete(it)
        }

        assertEquals(listOf("trashed"), deleted)
        assertTrue(dao.rows.any { it.id == "ativa-1" })
        assertTrue(dao.rows.any { it.id == "ativa-2" })
        assertFalse(dao.rows.any { it.trashed })
    }
}
