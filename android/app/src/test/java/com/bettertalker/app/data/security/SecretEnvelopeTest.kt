package com.bettertalker.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1 — envelope do segredo (IV + GCM) sem Android Keystore. Puro/testável. */
class SecretEnvelopeTest {

    private val iv = ByteArray(12) { it.toByte() }

    @Test
    fun roundTrip_preservaOsBytes() {
        val body = "sk-teste-123".toByteArray(Charsets.UTF_8)
        val encoded = SecretEnvelope.seal(iv, body)
        val opened = SecretEnvelope.open(encoded)
        assertNotNull(opened)
        assertEquals(iv.toList(), opened!!.first.toList())
        assertEquals(body.toList(), opened.second.toList())
    }

    @Test
    fun payloadNaoContemTextoClaro() {
        val encoded = SecretEnvelope.seal(iv, "sk-segredo-visivel".toByteArray())
        assertFalse(encoded.contains("sk-segredo-visivel"))
        assertTrue(encoded.matches(Regex("[A-Za-z0-9+/=]+")))
    }

    @Test
    fun base64Invalido_retornaNull() {
        assertNull(SecretEnvelope.open("não é base64"))
    }

    @Test
    fun ivComTamanhoInvalido_retornaNull() {
        val bad = ByteArray(1 + 4 + 16)
        bad[0] = 4
        assertNull(SecretEnvelope.open(java.util.Base64.getEncoder().encodeToString(bad)))
    }

    @Test
    fun semCiphertext_retornaNull() {
        val onlyIv = ByteArray(1 + 12)
        onlyIv[0] = 12
        assertNull(SecretEnvelope.open(java.util.Base64.getEncoder().encodeToString(onlyIv)))
    }
}
