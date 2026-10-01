package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.2.5d: data classes dos contratos section-aware (puras, sem Compose). */
class EditorContractsTest {

    @Test
    fun selectionContext_equality_works() {
        val a = SelectionContext("s1", "sp1", "texto", "<p>texto</p>")
        val b = SelectionContext("s1", "sp1", "texto", "<p>texto</p>")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun sectionAwareInsert_equality_works() {
        val a = SectionAwareInsert("s1", null, "## T\n\ncorpo", "T")
        val b = SectionAwareInsert("s1", null, "## T\n\ncorpo", "T")
        assertEquals(a, b)
    }

    @Test
    fun sectionAwareInsert_defaultHeading_isNull() {
        assertNull(SectionAwareInsert("s1", "sp1", "md").heading)
    }
}
