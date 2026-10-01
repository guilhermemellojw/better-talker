package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SubPointValidatorTest {

    private fun point(
        id: String,
        order: Int,
        outlineText: String = "Ponto $id",
        sectionId: String = "sec1",
    ) = SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = outlineText,
        bibleRefs = emptyList(), publicationRefs = emptyList(),
        instruction = null, developedHtml = "", createdAt = 1L, updatedAt = 2L,
    )

    @Test
    fun validList_returnsValid() {
        assertEquals(
            SubPointValidationResult.Valid,
            SubPointValidator.validate(listOf(point("a", 0), point("b", 1), point("c", 2))),
        )
    }

    @Test
    fun blankOutlineText_returnsInvalid() {
        val result = SubPointValidator.validate(
            listOf(point("a", 0, outlineText = "  "))
        ) as SubPointValidationResult.Invalid
        assertEquals(true, result.errors.contains(SubPointValidationError.BLANK_OUTLINE_TEXT))
    }

    @Test
    fun blankSectionId_returnsInvalid() {
        val result = SubPointValidator.validate(
            listOf(point("a", 0, sectionId = ""))
        ) as SubPointValidationResult.Invalid
        assertEquals(true, result.errors.contains(SubPointValidationError.BLANK_SECTION_ID))
    }

    @Test
    fun nonContiguousOrder_returnsInvalid() {
        val result = SubPointValidator.validate(
            listOf(point("a", 0), point("b", 2))
        ) as SubPointValidationResult.Invalid
        assertEquals(true, result.errors.contains(SubPointValidationError.NON_CONTIGUOUS_ORDER))
    }

    @Test
    fun negativeOrder_returnsNegativeOrder() {
        val result = SubPointValidator.validate(
            listOf(point("a", -1))
        ) as SubPointValidationResult.Invalid
        assertEquals(true, result.errors.contains(SubPointValidationError.NEGATIVE_ORDER))
    }

    @Test
    fun emptyList_returnsValid() {
        assertEquals(SubPointValidationResult.Valid, SubPointValidator.validate(emptyList()))
    }
}
