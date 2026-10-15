package com.bettertalker.app.data.domain

import com.bettertalker.app.data.util.normalizeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #15d — expansão de query com sinônimos curados. Puro, sem Android. */
class QueryExpansionTest {

    @Test
    fun mortosInconscientes_expandeParaMorteAlmaSono() {
        val out = expandQuery("como explicar que os mortos estão inconscientes")
        assertTrue(out.contains("morte"))
        assertTrue(out.contains("alma"))
        assertTrue(out.contains("sono"))
    }

    @Test
    fun esperanca_expandeParaParaisoEReino() {
        val out = expandQuery("esperança")
        assertTrue(out.contains("paraíso"))
        assertTrue(out.contains("reino"))
    }

    @Test
    fun palavraDesconhecida_retornaQueryOriginal() {
        val q = "xyzabc palavra desconhecida"
        assertEquals(q, expandQuery(q))
    }

    @Test
    fun dedupe_naoDuplicaSinonimos() {
        val out = expandQuery("morte morte")
        assertEquals(1, Regex("\\balma\\b").findAll(out).count())
        assertEquals(1, Regex("\\bressurreição\\b").findAll(out).count())
    }

    @Test
    fun caseInsensitive_reconheceMaiuscula() {
        assertTrue(expandQuery("Morte").contains("alma"))
    }

    @Test
    fun nuncaRemoveTermosOriginais() {
        val q = "o que a bíblia diz sobre a morte"
        val out = expandQuery(q)
        assertTrue(out.startsWith(q))
    }

    @Test
    fun chaves_estaoNormalizadas() {
        // Invariante: as chaves casam com normalizeText(query) — qualquer
        // acento na chave torna o par inalcançável (bug pego no build).
        QUERY_SYNONYMS.keys.forEach { k ->
            assertEquals("chave não normalizada: $k", k, normalizeText(k))
        }
    }
}
