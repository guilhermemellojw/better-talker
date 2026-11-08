package com.bettertalker.app.domain.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTriggerCodecTest {

    @Test
    fun roundTripPreservaEstados() {
        val states = mapOf(
            HelpTriggerId.EMPTY_LIBRARY to HelpTriggerState(
                seen = true,
                dismissed = true,
                lastShownAt = 123L,
            ),
            HelpTriggerId.NO_KEY to HelpTriggerState(seen = true, lastShownAt = 456L),
        )
        assertEquals(states, HelpTriggerCodec.decode(HelpTriggerCodec.encode(states)))
    }

    @Test
    fun vazioENaoCorrompidoNaoCrasham() {
        assertTrue(HelpTriggerCodec.decode(null).isEmpty())
        assertTrue(HelpTriggerCodec.decode("").isEmpty())
        assertTrue(HelpTriggerCodec.decode("lixo;NO_KEY,1,0").isEmpty())
        assertEquals(1, HelpTriggerCodec.decode("NO_KEY,1,0,10").size)
    }
}
