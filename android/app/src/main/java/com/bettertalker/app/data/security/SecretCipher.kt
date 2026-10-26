package com.bettertalker.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A1 — cifra de segredo local.
 *
 * A chave BYOD do DeepSeek nunca é persistida em texto claro. O valor
 * cifrado vai para o DataStore; a chave AES fica no Android Keystore
 * (não exportável), protegida pelo hardware quando disponível.
 *
 * Puro/testável no envelope; a implementação Android é validada no aparelho.
 */
interface SecretCipher {
    /** Cifra e devolve um envelope Base64 (IV + ciphertext). */
    fun encrypt(plaintext: String): String

    /** Decifra; null quando a chave foi invalidada ou o envelope é inválido. */
    fun decrypt(encoded: String): String?

    /** Remove a chave do Keystore (usado em reset explícito). */
    fun clear()
}

/**
 * Envelope autenticado: [ivLen][iv][ciphertext+GCM tag], em Base64.
 * O IV é aleatório por operação; o GCM detecta adulteração.
 */
internal object SecretEnvelope {
    private const val MIN_IV = 12
    private const val MAX_IV = 16

    fun seal(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size in MIN_IV..MAX_IV) { "IV inválido" }
        val out = ByteArray(1 + iv.size + ciphertext.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        ciphertext.copyInto(out, 1 + iv.size)
        return Base64.getEncoder().encodeToString(out)
    }

    /** Devolve IV + ciphertext; null para Base64 inválido ou tamanho suspeito. */
    fun open(encoded: String): Pair<ByteArray, ByteArray>? = runCatching {
        val data = Base64.getDecoder().decode(encoded)
        require(data.size > 1)
        val ivLen = data[0].toInt() and 0xFF
        require(ivLen in MIN_IV..MAX_IV)
        require(data.size > 1 + ivLen)
        val iv = data.copyOfRange(1, 1 + ivLen)
        val body = data.copyOfRange(1 + ivLen, data.size)
        iv to body
    }.getOrNull()
}

/**
 * Implementação real: AES/GCM com chave não exportável no Android Keystore.
 * Qualquer falha de decifra (chave invalidada, enrolamento novo, corrupção)
 * retorna null — o chamador limpa o valor e pede a chave de novo.
 */
class KeystoreSecretCipher(
    private val alias: String = KEY_ALIAS,
) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SecretEnvelope.seal(cipher.iv, body)
    }

    override fun decrypt(encoded: String): String? {
        val (iv, body) = SecretEnvelope.open(encoded) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun clear() {
        runCatching {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        const val KEY_ALIAS = "better_talker_deepseek_api_key_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
