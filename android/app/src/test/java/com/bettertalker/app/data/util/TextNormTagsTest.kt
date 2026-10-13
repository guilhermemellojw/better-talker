package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F5f (E2E no device) — o modelo às vezes embrulha campos de texto em tags
 * próprias ("<sugestão>…</sugestão>") e deixa fechamentos órfãos.
 */
class TextNormTagsTest {

    @Test
    fun caso4_wrapperComAcento() {
        assertEquals("texto", stripPromptTags("<sugestão>texto</sugestão>"))
    }

    @Test
    fun caso5_semTags_inalterado() {
        val s = "Esboço prático (sugestão) — base em João 3:16."
        assertEquals(s, stripPromptTags(s))
    }

    @Test
    fun caso6_multiplasTags_conteudoPreservado() {
        assertEquals("um dois", stripPromptTags("<a>um</a> <b>dois</b>"))
    }

    @Test
    fun caso7_aninhadoSimplorio_comportamentoDefinido() {
        // Não é parser HTML: o loop de pares resolve este caso; documentado.
        assertEquals("x", stripPromptTags("<a><b>x</b></a>"))
    }

    @Test
    fun tagOrfaDeFechamento_removida() {
        // Caso real observado no card do #3c ("… </sugestão>").
        assertEquals("ação concreta.", stripPromptTags("ação concreta. </sugestão>"))
    }

    @Test
    fun trimFinal() {
        assertEquals("x", stripPromptTags("  <a>x</a>  "))
    }

    @Test
    fun menorQueSemFechamento_naoEhTag() {
        assertEquals("3 < 5 e 7 > 2", stripPromptTags("3 < 5 e 7 > 2"))
    }
}
