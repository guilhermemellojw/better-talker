package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscourseTypeTest {

    @Test
    fun enum_has6Values() {
        assertEquals(
            listOf(
                DiscourseType.S34_DISCOURSE,
                DiscourseType.TREASURES_TALK,
                DiscourseType.MINISTRY_PART,
                DiscourseType.FREE_DEMO,
                DiscourseType.MINISTRY_TALK,
                DiscourseType.AVULSO,
            ),
            DiscourseType.values().toList(),
        )
    }

    @Test
    fun freeDemo_isDistinctFromMinistryPart() {
        assertEquals(
            DiscourseType.FREE_DEMO,
            DiscourseType.fromNameOrDefault("FREE_DEMO"),
        )
        assertEquals(
            DiscourseType.MINISTRY_PART,
            DiscourseType.fromNameOrDefault("MINISTRY_PART"),
        )
    }

    @Test
    fun ministryTalk_isDistinctFromDemo() {
        // Fase A: a parte 6 "Discurso" não pode ser confundida com diálogo.
        assertEquals(
            DiscourseType.MINISTRY_TALK,
            DiscourseType.fromNameOrDefault("MINISTRY_TALK"),
        )
        assertEquals(
            DiscourseType.MINISTRY_PART,
            DiscourseType.fromNameOrDefault("MINISTRY_PART"),
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
