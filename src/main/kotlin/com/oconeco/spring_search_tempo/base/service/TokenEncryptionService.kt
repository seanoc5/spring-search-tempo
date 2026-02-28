package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


/**
 * AES-256-GCM symmetric encryption service for securing OAuth2 refresh tokens at rest.
 *
 * The encryption key is loaded from the `app.onedrive.token-encryption-key` property
 * (typically set via the ONEDRIVE_TOKEN_ENCRYPTION_KEY environment variable).
 *
 * Format: Base64(IV || ciphertext || GCM tag)
 */
@Service
class TokenEncryptionService(
    @Value("\${app.onedrive.token-encryption-key:}") private val keyBase64: String
) {

    companion object {
        private val log = LoggerFactory.getLogger(TokenEncryptionService::class.java)
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val secretKey: SecretKeySpec? by lazy {
        if (keyBase64.isBlank()) {
            log.warn("OneDrive token encryption key not configured. Token encryption/decryption will fail.")
            null
        } else {
            val keyBytes = Base64.getDecoder().decode(keyBase64)
            SecretKeySpec(keyBytes, "AES")
        }
    }

    /**
     * Encrypt a plaintext string using AES-256-GCM.
     *
     * @param plaintext The string to encrypt
     * @return Base64-encoded ciphertext (IV + encrypted data + GCM tag)
     * @throws IllegalStateException if encryption key is not configured
     */
    fun encrypt(plaintext: String): String {
        val key = secretKey ?: throw IllegalStateException("OneDrive token encryption key not configured")

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Concatenate IV + ciphertext
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypt a Base64-encoded ciphertext string using AES-256-GCM.
     *
     * @param ciphertext Base64-encoded string (IV + encrypted data + GCM tag)
     * @return The decrypted plaintext string
     * @throws IllegalStateException if encryption key is not configured
     */
    fun decrypt(ciphertext: String): String {
        val key = secretKey ?: throw IllegalStateException("OneDrive token encryption key not configured")

        val combined = Base64.getDecoder().decode(ciphertext)

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Check if the encryption key is configured.
     */
    fun isConfigured(): Boolean = keyBase64.isNotBlank()

}
