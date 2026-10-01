package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionValidationError
import com.bettertalker.app.domain.speech.SectionValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hotfix P0: estado auto-contido do aviso estrutural (JVM puro). */
class StructureWarningHolderTest {

    @Test
    fun initialState_isNull() {
        val holder = StructureWarningHolder()
        assertNull(holder.warning.value)
    }

    @Test
    fun update_withInvalid_setsWarning() {
        val holder = StructureWarningHolder()
        val errors = listOf(SectionValidationError.NO_BODY)
        holder.update(SectionValidationResult.Invalid(errors))
        assertEquals(errors, holder.warning.value?.errors)
    }

    @Test
    fun update_withValid_clearsWarning() {
        val holder = StructureWarningHolder()
        holder.update(SectionValidationResult.Invalid(listOf(SectionValidationError.NO_BODY)))
        holder.update(SectionValidationResult.Valid)
        assertNull(holder.warning.value)
    }

    @Test
    fun update_withNull_clearsWarning() {
        val holder = StructureWarningHolder()
        holder.update(SectionValidationResult.Invalid(listOf(SectionValidationError.NO_BODY)))
        holder.update(null)
        assertNull(holder.warning.value)
    }

    @Test
    fun dismiss_clearsWarning() {
        val holder = StructureWarningHolder()
        holder.update(SectionValidationResult.Invalid(listOf(SectionValidationError.NO_BODY)))
        holder.dismiss()
        assertNull(holder.warning.value)
    }
}
