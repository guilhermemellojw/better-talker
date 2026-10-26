package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.RefDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Card/checklist: publicação + unidade, sem achatar duas unidades da mesma
 * publicação nem duplicar o que o label já traz.
 */
class RefDisplayTest {

    private fun ref(
        label: String,
        kind: RefDetector.Kind = RefDetector.Kind.BOOK,
        article: String? = null,
        chapter: String? = null,
        page: Int? = null,
        paragraph: Int? = null,
        end: Int? = null,
        pubKey: String = "lmd",
        editionKey: String = "book|lmd",
    ) = RefDetector.DetectedRef(
        raw = label,
        kind = kind,
        pubKey = pubKey,
        editionKey = editionKey,
        label = label,
        article = article,
        chapter = chapter,
        page = page,
        paragraph = paragraph,
        paragraphEnd = end,
    )

    @Test
    fun licaoEPontoAparecemNaLinha() {
        val r = ref("Ame as Pessoas", chapter = "lição 9", paragraph = 5)
        assertEquals("Ame as Pessoas — lição 9, ponto 5", refDisplayLabel(r))
        assertEquals("lição 9, ponto 5", refUnitLabel(r))
    }

    @Test
    fun apendiceNaoDuplicaUnidadeNoLabel() {
        val r = ref("Ame as Pessoas — Apêndice A", chapter = "Apêndice A")
        assertEquals("Ame as Pessoas — Apêndice A", refDisplayLabel(r))
        assertEquals("Apêndice A", refUnitLabel(r))
    }

    @Test
    fun duasUnidadesDaMesmaPublicacaoTemChavesDiferentes() {
        val a = ref("Ame as Pessoas — Apêndice A", chapter = "Apêndice A")
        val b = ref("Ame as Pessoas", chapter = "lição 9", paragraph = 5)
        assertEquals("lmd", refGroupKey(a))
        assertEquals("lmd", refGroupKey(b))
        assertEquals("Ame as Pessoas — 2 referências", refGroupHeader(a, 2))
        assertEquals("Ame as Pessoas — 2 referências", refGroupHeader(b, 2))
    }

    @Test
    fun revistaMantemLabelComPaginaEParagrafo() {
        val r = ref(
            "A Sentinela 7/2025 • pág. 22 §§ 8-9",
            kind = RefDetector.Kind.MAGAZINE,
            page = 22,
            paragraph = 8,
            end = 9,
            pubKey = "w",
            editionKey = "w|2025|7",
        )
        assertEquals("A Sentinela 7/2025 • pág. 22 §§ 8-9", refDisplayLabel(r))
        assertEquals("w|2025|7", refGroupKey(r))
    }
}
