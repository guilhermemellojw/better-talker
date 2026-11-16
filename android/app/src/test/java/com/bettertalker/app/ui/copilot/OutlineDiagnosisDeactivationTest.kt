package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.db.ChatEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineDiagnosisDeactivationTest {

    private fun entity(id: String, kind: String) = ChatEntity(
        id = id,
        noteId = "note-1",
        fromMe = false,
        kind = kind,
        payload = "{}",
        createdAt = 1L,
    )

    @Test
    fun desativaSomenteDiagnosticosAnteriores() {
        val items = listOf(
            entity("msg-1", "diagnosis"),
            entity("msg-2", "text"),
            entity("msg-3", "diagnosis"),
            entity("msg-4", "ideas"),
        )

        assertEquals(
            listOf("msg-1", "msg-3"),
            diagnosisMessageIdsToDeactivate(items),
        )
    }

    @Test
    fun semDiagnosticosNaoDesativaNada() {
        assertEquals(
            emptyList<String>(),
            diagnosisMessageIdsToDeactivate(
                listOf(entity("msg-1", "text"), entity("msg-2", "refs")),
            ),
        )
    }
}
