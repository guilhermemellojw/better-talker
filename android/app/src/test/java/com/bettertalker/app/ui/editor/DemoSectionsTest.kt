package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DEMO 3.2.5b: valida a estrutura da fixture do demo (função pura, sem
 * Compose). A UI do componente não é testável na JVM (sem Robolectric).
 */
class DemoSectionsTest {

    @Test
    fun demoSections_has7Sections() {
        val sections = buildDemoSections()
        assertEquals(7, sections.size)
        assertEquals(
            listOf(
                SectionRole.INTRO,
                SectionRole.BODY, SectionRole.BODY, SectionRole.BODY,
                SectionRole.BODY, SectionRole.BODY,
                SectionRole.CONCLUSION,
            ),
            sections.map { it.section.role },
        )
        // ordem 0..6 contígua (convenção do domínio)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), sections.map { it.section.order })
        // INTRO/CONCLUSION desenvolvem em contentHtml, não em sub-pontos
        assertEquals(0, sections.first().subPoints.size)
        assertEquals(0, sections.last().subPoints.size)
    }

    @Test
    fun demoSections_s34Number35Has28SubPoints() {
        val sections = buildDemoSections()
        assertEquals(28, sections.sumOf { it.subPoints.size })
        assertEquals(
            listOf(5, 5, 6, 9, 3),
            sections.filter { it.section.role == SectionRole.BODY }.map { it.subPoints.size },
        )
    }

    @Test
    fun demoSections_editorCountMatchesComponentDesign() {
        // 1 INTRO + 28 sub-pontos + 1 CONCLUSION = 30 editores vivos.
        // (BODY não tem editor próprio — é desenvolvida ponto a ponto.)
        val editors = buildDemoSections().sumOf {
            if (it.section.role == SectionRole.BODY) it.subPoints.size else 1
        }
        assertEquals(30, editors)
    }
}
