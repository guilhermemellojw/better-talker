package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEDAGOGY-1-MIN / P7 — reprodução independente dos bloqueadores F7/F8
 * do parecer de E no SHA auditado `7476cf79`.
 *
 * F7: reconhecimento Unicode de marcadores discursivos acentuados.
 * F8: preservação byte a byte de aspas, rótulos TRUST e referências,
 * sem tokens internos/restauração aninhada.
 */
class PedagogicalUnicodeLiteralTest {

    @Test
    fun f7a_inicioDeTextoAcentuadoMaiusculo() {
        val raw = "É importante ressaltar que João 3:16 fala do amor."
        val out = simplifyPedagogicalText(raw)
        assertFalse("F7: $out", out.contains("É importante ressaltar"))
        assertTrue(out.contains("João 3:16"))
        assertTrue(out.contains("fala do amor"))
    }

    @Test
    fun f7b_inicioDeFraseDepoisDePontoExclamacaoInterrogacaoENovaLinha() {
        val cases = listOf(
            "Fato confirmado. É importante notar que João 3:16 fala do amor.",
            "Fato confirmado! Importa ressaltar que João 3:16 fala do amor.",
            "Fato confirmado? Vale lembrar que João 3:16 fala do amor.",
            "Fato confirmado.\nÉ importante destacar que João 3:16 fala do amor.",
            "é importante ressaltar que João 3:16 fala do amor.",
        )
        cases.forEach { raw ->
            val out = simplifyPedagogicalText(raw)
            assertFalse("F7: $out", Regex("""(?iu)(é importante|importa|vale)""").containsMatchIn(out))
            assertTrue(out.contains("João 3:16"))
        }
    }

    @Test
    fun f7c_negacaoCondicaoEQualificadorPreservados() {
        val negado = "Não é importante ressaltar que o texto foi revisado."
        assertEquals(negado, simplifyPedagogicalText(negado))
        val condicao = "Se é importante notar que o texto foi revisado, continue."
        assertEquals(condicao, simplifyPedagogicalText(condicao))
        val qualificador = "Apenas é importante destacar que o texto foi revisado."
        assertEquals(qualificador, simplifyPedagogicalText(qualificador))
    }

    @Test
    fun f7d_limitesUnicodeNaoTransformamPalavraComum() {
        val raw = "café importante ressaltar que o texto foi revisado."
        assertEquals(raw, simplifyPedagogicalText(raw))
    }

    @Test
    fun f8a_rotuloComCitacaoPermaneceByteAByte() {
        val rotulo = "Trecho recuperado: “amor paciente” — João 3:16"
        val out = simplifyPedagogicalText("Basicamente, $rotulo.")
        assertTrue("F8: $out", out.contains(rotulo))
        assertFalse(out.contains('\u0000'))
    }

    @Test
    fun f8b_aspasRetasTipograficasMultiplasEIdenticas() {
        val raw = "Basicamente, \"amor\" e “amor” e «amor» — João 3:16."
        val out = simplifyPedagogicalText(raw)
        assertTrue(out.contains("\"amor\""))
        assertTrue(out.contains("“amor”"))
        assertTrue(out.contains("«amor»"))
        assertEquals(3, Regex("""amor""").findAll(out).count())
        assertFalse(out.contains('\u0000'))
    }

    @Test
    fun f8c_rotuloContendoExpressaoSimplificavelNaoEAlterado() {
        val rotulo = "Trecho recuperado: basicamente, em outras palavras, é importante ressaltar."
        assertEquals(rotulo, simplifyPedagogicalText(rotulo))
    }

    @Test
    fun f8d_idempotenciaESemTokensInternos() {
        val raw = "Basicamente, “A” e “A”. João 3:16. Simplesmente, “B”."
        val once = simplifyPedagogicalText(raw)
        val twice = simplifyPedagogicalText(once)
        assertEquals(once, twice)
        assertFalse(once.contains('\u0000'))
        assertFalse(once.contains("\\u0000"))
        assertTrue(once.contains("“A” e “A”"))
        assertTrue(once.contains("João 3:16"))
        assertTrue(once.contains("“B”"))
    }

    @Test
    fun f8e_referenciaAdJacenteAsAspas() {
        val raw = "Basicamente, “amor paciente”João 3:16 — Simplifique."
        val out = simplifyPedagogicalText(raw)
        assertTrue(out.contains("“amor paciente”João 3:16"))
        assertFalse(out.contains("Basicamente"))
        assertFalse(out.contains('\u0000'))
    }
}
