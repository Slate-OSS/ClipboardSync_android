package com.finitecode.clipboardsync

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object EncryptionManager {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 128

    /**
     * Decrypt AES-GCM encrypted data
     */
    fun decrypt(encryptedData: ByteArray, keyHex: String): ByteArray? {
        return try {
            val key = hexStringToKey(keyHex)

            // Extract nonce, ciphertext, and tag
            val nonce = encryptedData.copyOfRange(0, NONCE_SIZE)
            val ciphertext = encryptedData.copyOfRange(NONCE_SIZE, encryptedData.size - 16)
            val tag = encryptedData.copyOfRange(encryptedData.size - 16, encryptedData.size)

            // Reconstruct sealed box
            val fullCiphertext = ciphertext + tag

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE, nonce)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            cipher.doFinal(fullCiphertext)
        } catch (e: Exception) {
            println("❌ Decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Convert hex string to AES key
     */
    fun hexStringToKey(hexString: String): SecretKeySpec {
        val bytes = hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return SecretKeySpec(bytes, 0, bytes.size, "AES")
    }

    /**
     * Convert key to hex string
     */
    fun keyToHexString(key: ByteArray): String {
        return key.joinToString("") { "%02x".format(it) }
    }
}
