package com.bettertalker.app.data.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ImportValidationTest {

    private fun temp(bytes: ByteArray, suffix: String): File {
        val f = File.createTempFile("import-", "-$suffix")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    @Test
    fun pdf_valido_passa() {
        assertNull(ImportValidation.invalidReason(temp("%PDF-1.7".toByteArray(), "x.pdf"), DocKind.PDF))
    }

    @Test
    fun pdf_que_eh_html_falha() {
        assertNotNull(ImportValidation.invalidReason(temp("<html>".toByteArray(), "x.pdf"), DocKind.PDF))
    }

    @Test
    fun jwpub_que_eh_pagina_falha() {
        assertNotNull(ImportValidation.invalidReason(temp("<html>".toByteArray(), "x.jwpub"), DocKind.JWPUB))
    }

    @Test
    fun zip_valido_passa() {
        assertNull(ImportValidation.invalidReason(temp(byteArrayOf(0x50, 0x4B, 3, 4), "x.zip"), DocKind.ZIP))
    }

    @Test
    fun rtf_valido_passa() {
        assertNull(ImportValidation.invalidReason(temp("{\\rtf1".toByteArray(), "x.rtf"), DocKind.RTF))
    }

    @Test
    fun arquivo_vazio_falha() {
        assertNotNull(ImportValidation.invalidReason(temp(ByteArray(0), "x.pdf"), DocKind.PDF))
    }
}
