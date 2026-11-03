package com.bettertalker.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictsTest {
    @Test
    fun reconheceSufixoDeConflito() {
        assertTrue(isConflictNoteTitle("Discurso (conflito)"))
        assertTrue(isConflictNoteTitle("Discurso (CONFLITO)  "))
    }

    @Test
    fun naoConfundeTituloNormal() {
        assertFalse(isConflictNoteTitle("Discurso"))
        assertFalse(isConflictNoteTitle("O que é conflito?"))
    }
}
