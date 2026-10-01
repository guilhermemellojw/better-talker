package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscourseTypeTest {

    @Test
    fun enum_has4Values() {
        assertEquals(
            listOf(
                DiscourseType.S34_DISCOURSE,
                DiscourseType.TREASURES_TALK,
                DiscourseType.MINISTRY_PART,
                DiscourseType.AVULSO,
            ),
            DiscourseType.values().toList(),
        )
    }

    @Test
    fun s34_isDefault() {
        assertEquals(DiscourseType.S34_DISCOURSE, DiscourseType.fromNameOrDefault(null))
        assertEquals(DiscourseType.S34_DISCOURSE, DiscourseType.fromNameOrDefault(""))
    }

    @Test
    fun fromString_unknownFallsBackToS34() {
        assertEquals(DiscourseType.S34_DISCOURSE, DiscourseType.fromNameOrDefault("LEGADO"))
        assertEquals(DiscourseType.TREASURES_TALK, DiscourseType.fromNameOrDefault("TREASURES_TALK"))
    }
}
