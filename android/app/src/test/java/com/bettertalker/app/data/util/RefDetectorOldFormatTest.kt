package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefDetectorOldFormatTest {

    @Test
    fun detect_w94_1_8_page3_detectsOldFormat() {
        val ref = RefDetector.detect("(w94 1/8 3)").single()
        assertEquals("w", ref.pubKey)
        assertEquals("w|1994|8|1", ref.editionKey)
        assertTrue(ref.label.contains("pág. 3"))
    }

    @Test
    fun detect_w86_15_9_range_detectsOldFormat() {
        val ref = RefDetector.detect("(w86 15/9 3-4)").single()
        assertEquals("w|1986|9|15", ref.editionKey)
        assertTrue(ref.label.contains("pág. 3"))
    }

    @Test
    fun detect_g93_8_1_detectsOldAwake() {
        val ref = RefDetector.detect("(g93 8/1 4-10)").single()
        assertEquals("g", ref.pubKey)
        assertEquals("g|1993|1|8", ref.editionKey)
        assertTrue(ref.label.contains("pág. 4"))
    }

    @Test
    fun detect_g91_8_8_detectsOldAwake() {
        assertEquals("g|1991|8|8", RefDetector.detect("(g91 8/8 28)").single().editionKey)
    }

    @Test
    fun detect_oldFormat_dotSeparator_works() {
        assertEquals("w|1982|3|15", RefDetector.detect("(w82 15.3)").single().editionKey)
    }

    @Test
    fun detect_oldFormat_noPage_works() {
        assertEquals("w|1994|8|1", RefDetector.detect("conforme (w94 1/8)").single().editionKey)
    }

    @Test
    fun detect_oldFormat_withSuffix_works() {
        val ref = RefDetector.detect("(g92 22/3 28, em inglês)").single()
        assertEquals("g|1992|3|22", ref.editionKey)
    }

    @Test
    fun detect_modernFormat_stillWorks() {
        val ref = RefDetector.detect("(w21.08 18 § 13)").single()
        assertEquals("w|2021|8", ref.editionKey)
    }

    @Test
    fun detect_modernAwake_stillWorks() {
        assertEquals("g|2007|6", RefDetector.detect("(g 6/07 8 § 2)").single().editionKey)
    }

    @Test
    fun detect_pubVsStudyDistinct() {
        val pub = RefDetector.detect("(w94 1/8 3)").single().editionKey
        val study = RefDetector.detect("(w94 15/8 3)").single().editionKey
        assertEquals("w|1994|8|1", pub)
        assertEquals("w|1994|8|15", study)
        assertNotEquals(pub, study)
    }
}
