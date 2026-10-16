package com.bettertalker.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #20-fix-b — a rota da Tribuna leva o noteId. Puro. */
class TribunaNavigationTest {

    @Test
    fun rotaTribunaCarregaONoteId() {
        assertEquals("tribuna/{noteId}", Routes.TRIBUNA)
        assertEquals("tribuna/note-123", Routes.tribuna("note-123"))
        assertTrue(Routes.TRIBUNA.contains("{noteId}"))
    }
}
