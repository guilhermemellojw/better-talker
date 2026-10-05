package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.repo.ImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 — falhas de import têm mensagem visível ao usuário (fim dos catches
 * silenciosos): texto humano do [ImportException] ou orientação genérica.
 */
class ImportMessagesTest {

    @Test
    fun formatoNaoSuportadoUsaMensagemDoImportException() {
        val msg = importFailureMessage(
            ImportException(
                ImportException.Reason.UNSUPPORTED,
                "Formato não suportado. Use DOCX, PDF, RTF ou JWPUB."
            )
        )
        assertEquals("Formato não suportado. Use DOCX, PDF, RTF ou JWPUB.", msg)
    }

    @Test
    fun falhaInesperadaTemMensagemGenerica() {
        val msg = importFailureMessage(RuntimeException("boom"))
        assertTrue(msg.contains("Não consegui importar o esboço"))
        assertTrue(msg.contains("DOCX"))
    }

    @Test
    fun parseFailedDoS34TemMensagemPropria() {
        assertTrue(S34_PARSE_FAILURE_MESSAGE.contains("S-34"))
        assertTrue(S34_PARSE_FAILURE_MESSAGE.contains("não consegui extrair as seções"))
    }
}
