package com.bettertalker.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** F2.1 — transições do progresso compartilhado. */
class LocalProgressTest {

    @Test
    fun reset_goes_idle_and_clears_partial() {
        LocalProgress.set(LocalPhase.Generating)
        LocalProgress.appendPartial("abc")
        LocalProgress.reset()
        assertEquals(LocalPhase.Idle, LocalProgress.phase.value)
        assertEquals("", LocalProgress.partialText.value)
    }

    @Test
    fun set_and_append_accumulate() {
        LocalProgress.reset()
        LocalProgress.set(LocalPhase.LoadingModel)
        assertEquals(LocalPhase.LoadingModel, LocalProgress.phase.value)
        LocalProgress.set(LocalPhase.Generating)
        LocalProgress.appendPartial("x")
        LocalProgress.appendPartial("y")
        assertEquals("xy", LocalProgress.partialText.value)
        LocalProgress.reset()
    }
}
