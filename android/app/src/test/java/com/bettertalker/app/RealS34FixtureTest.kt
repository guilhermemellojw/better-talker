package com.bettertalker.app

import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.util.NoteRefsCollector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C2.0/C2.2 — fixture real: S-34_T_003 (4 seções/30 min) do Redmi 9.
 *
 * Baseline histórico (v2.8.x): 75 instâncias no parser (67 bíblicas + 8
 * publicações). O C2.2 passou a detectar `bt` e os dois `kr` do texto, então
 * o parser chega a 78 (67 bíblicas + 11 publicações).
 *
 * O coletor canônico deduplica as 8 referências de Ezequiel 1:12-19 que já
 * estão dentro do range 1:1-28: 70 refs únicas (59 bíblicas + 11 publicações).
 */
class RealS34FixtureTest {

    private fun text(): String =
        javaClass.getResourceAsStream("/fixtures/S-34_T_003.txt")!!
            .bufferedReader()
            .readText()
            .replace("\uFEFF", "")
            .replace("\u200b", " ")
            .replace("\u00a0", " ")
            .replace("\r\n", "\n")

    @Test
    fun fixtureRealMantemEstruturaDe4SecoesE30Minutos() {
        val doc = S34Parser.parseS34(text())

        assertEquals(4, doc.sections.size)
        assertEquals(30, doc.sections.sumOf { it.minutes ?: 0 })
        assertEquals(
            listOf(
                "JEOVÁ É UM DEUS DE ORGANIZAÇÃO",
                "A ORGANIZAÇÃO DE JEOVÁ É UNIDA",
                "A ORGANIZAÇÃO DE JEOVÁ ESTÁ SEMPRE AVANÇANDO",
                "CONTINUE AVANÇANDO COM A ORGANIZAÇÃO DE JEOVÁ",
            ),
            doc.sections.map { it.title },
        )
    }

    @Test
    fun fixtureRealTem78ReferenciasEstruturadas() {
        val doc = S34Parser.parseS34(text())
        val bible = doc.references.count { it.type == S34RefType.BIBLE }
        val publications = doc.references.count { it.type == S34RefType.PUBLICATION }

        assertEquals(78, doc.references.size)
        assertEquals(67, bible)
        assertEquals(11, publications)
    }

    @Test
    fun coletorCanonicoDedupaPara70RefsUnicas() {
        val raw = text()
        val doc = S34Parser.parseS34(raw)
        val refs = NoteRefsCollector.collect(
            noteTitle = doc.title,
            plainText = raw,
        )

        assertEquals(11, refs.publications.size)
        assertEquals(59, refs.bibles.size)
        assertEquals(70, refs.publications.size + refs.bibles.size)

        // Ezequiel 1:12-19 repete 8 versículos do range 1:1-28.
        assertEquals(
            8,
            doc.references.size - (refs.publications.size + refs.bibles.size),
        )
        val ezekielRaw = doc.references.count {
            it.type == S34RefType.BIBLE && it.bible?.label == "Ezequiel" && it.bible.chapter == 1
        }
        val ezekielUnique = refs.bibles.count { it.label == "Ezequiel" && it.chapter == 1 }
        assertEquals(36, ezekielRaw)
        assertEquals(28, ezekielUnique)
    }
}
