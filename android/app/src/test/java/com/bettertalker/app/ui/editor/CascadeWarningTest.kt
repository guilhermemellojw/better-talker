package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.2.5f.2b: aviso de cascata da exclusão de seção (função pura). */
class CascadeWarningTest {

    @Test
    fun cascadeWarningMessage_zeroSubPoints_isEmpty() {
        assertEquals("", cascadeWarningMessage(0))
    }

    @Test
    fun cascadeWarningMessage_withSubPoints_mentionsCount() {
        val message = cascadeWarningMessage(3)
        assertTrue(message.contains("3"))
        assertTrue(message.contains("sub-pontos"))
    }
}
