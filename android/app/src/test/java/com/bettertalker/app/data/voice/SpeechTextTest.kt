package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 (voz): preparação do texto pós-gate para o TTS. Puro/testável. */
class SpeechTextTest {

    @Test
    fun prepareForSpeech_removeTagsEVerbalizaAvisos() {
        val out = prepareForSpeech(
            "Veja: 〈sugestão〉uma metáfora do rio〈/sugestão〉. 💡 Sugestão criativa: o rio. " +
                "⚠️ Revise: números sem apoio: 5."
        )
        assertTrue(out, out.contains("uma metáfora do rio"))
        assertFalse(out, out.contains("〈"))
        assertTrue(out, out.contains("Uma sugestão criativa: o rio."))
        assertTrue(out, out.contains("Atenção: revise. números sem apoio: 5."))
    }

    @Test
    fun prepareForSpeech_limpaMarkdown() {
        val out = prepareForSpeech(
            "## Título\n- item um\n- item dois\n**forte** e *itálico* e [link](https://x.com)"
        )
        assertEquals("Título\nitem um\nitem dois\nforte e itálico e link", out)
    }

    @Test
    fun prepareForSpeech_naoLêProveniencia() {
        val out = prepareForSpeech("📖 No acervo: it “Gedalias” §4\nTexto real da resposta.")
        assertEquals("Texto real da resposta.", out)
    }

    @Test
    fun prepareForSpeech_vazioPermaneceVazio() {
        assertEquals("", prepareForSpeech(""))
        assertEquals("", prepareForSpeech("   \n  "))
    }
}
