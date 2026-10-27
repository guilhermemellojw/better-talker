package com.bettertalker.app

import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.PasteKind
import com.bettertalker.app.data.util.PastePlan
import com.bettertalker.app.data.util.planPaste
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2.0 — fixture real V&M (apostila de 4 linhas, 10 min).
 *
 * Texto exato da validação no Redmi 9 (2026-10-25): título + 3 pontos com
 * `Jer. 45:3`, `w25.07 22 §§ 8-9`, `w25.07 20 § 2`, `Jer. 45:5` e
 * `w25.07 24 §§ 13-14`. O coletor canônico precisa devolver 5 refs únicas
 * (2 bíblicas + 3 publicações), a mesma lista que alimenta o checklist.
 */
class RealVmFixtureTest {

    companion object {
        val TEXT = """
            Ter esperança é o segredo para o contentamento (10 min)
            Parece que Baruque não estava satisfeito com sua situação na vida. (Jer. 45:3; w25.07 22 §§ 8-9)
            Sem contentamento, podemos perder nossa alegria e tomar decisões ruins. (w25.07 20 § 2)
            Jeová ajudou Baruque a mudar o seu ponto de vista dando a ele uma esperança. (Jer. 45:5; w25.07 24 §§ 13-14)
        """.trimIndent()
    }

    @Test
    fun detectaDiscursoVmComTituloEDuracao() {
        val plan = planPaste(TEXT)

        assertTrue("detectado como ${plan.kind}", plan.kind is PasteKind.VmTalk)
        assertEquals(DiscourseType.TREASURES_TALK, plan.discourseType)
        assertEquals("Ter esperança é o segredo para o contentamento", plan.noteTitle)
        assertEquals(10, plan.effectiveMinutes())
        assertEquals(2, plan.bibleRefs.size)
        assertEquals(3, plan.publicationRefs.size)
    }

    @Test
    fun corpoDaParteTemTresPontosECincoCards() {
        val plan = planPaste(TEXT)
        val bodies = plan.sections.filter { it.role == SectionRole.BODY }

        assertEquals(3, bodies.size)
        assertEquals(listOf("Ponto 1", "Ponto 2", "Ponto 3"), bodies.map { it.title })
        assertEquals(8, bodies.sumOf { it.minutes })
        assertTrue(bodies.all { it.contentHtml.isNotBlank() })
        assertEquals(2, bodies.sumOf { it.bibleRefs.size })
        assertEquals(3, bodies.sumOf { it.publicationRefs.size })
    }

    @Test
    fun coletorCanonicoDevolve2BiblicasE3Publicacoes() {
        val refs = NoteRefsCollector.collect(plainText = TEXT)

        assertEquals(2, refs.bibles.size)
        assertEquals(3, refs.publications.size)
        assertEquals(
            listOf("Jeremias 45:3", "Jeremias 45:5"),
            refs.bibles.map { "${it.label} ${it.chapter}:${it.verse}" },
        )
        assertEquals(
            listOf("w25.07 22 §§ 8-9", "w25.07 20 § 2", "w25.07 24 §§ 13-14"),
            refs.publications.map { it.raw },
        )
        assertTrue(refs.publications.all { it.pubKey == "w" && it.editionKey == "w|2025|7" })
    }

    /** `PastePlan` não expõe os minutos diretamente; a soma das seções é a duração efetiva. */
    private fun PastePlan.effectiveMinutes(): Int = sections.sumOf { it.minutes }
}
