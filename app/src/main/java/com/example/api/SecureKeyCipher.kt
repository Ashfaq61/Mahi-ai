package com.example.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SecureKeyCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/**
 * Hardware/TEE-backed Android Keystore AES-256-GCM cipher.
 * Keys never leave the Android Keystore and are never stored in plaintext.
 */
class AndroidKeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SecureKeyCipher {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "mahi_ai_api_key_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val PAYLOAD_PREFIX = "ak1:"
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)

        return PAYLOAD_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        if (!cipherText.startsWith(PAYLOAD_PREFIX)) {
            throw IllegalArgumentException("Unsupported encrypted key format")
        }
        val raw = Base64.decode(cipherText.removePrefix(PAYLOAD_PREFIX), Base64.NO_WRAP)
        if (raw.size < 2) throw IllegalArgumentException("Corrupted encrypted key payload")

        val ivLength = raw[0].toInt() and 0xFF
        if (raw.size <= 1 + ivLength) throw IllegalArgumentException("Invalid IV length")

        val iv = raw.copyOfRange(1, 1 + ivLength)
        val encrypted = raw.copyOfRange(1 + ivLength, raw.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}

/**
 * Deterministic AES-256-GCM cipher for JVM / Robolectric unit tests where
 * AndroidKeyStore service is unavailable. Production always uses AndroidKeystoreCipher.
 */
class InMemoryAesGcmCipher(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : SecureKeyCipher {

    private val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
    private val random = SecureRandom()

    override fun encrypt(plainText: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return "mem1:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        if (!cipherText.startsWith("mem1:")) {
            throw IllegalArgumentException("Unsupported test cipher payload")
        }
        val raw = Base64.decode(cipherText.removePrefix("mem1:"), Base64.NO_WRAP)
        val ivLength = raw[0].toInt() and 0xFF
        val iv = raw.copyOfRange(1, 1 + ivLength)
        val encrypted = raw.copyOfRange(1 + ivLength, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
