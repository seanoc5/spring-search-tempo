package com.oconeco.spring_search_tempo.base.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


/**
 * AES-256-GCM symmetric encryption service for securing OAuth2 refresh tokens and
 * IMAP passwords at rest.
 *
 * The encryption key is loaded from the `app.onedrive.token-encryption-key` property
 * (typically set via the ONEDRIVE_TOKEN_ENCRYPTION_KEY environment variable).
 *
 * Envelope: `v1:` + Base64(IV || ciphertext || GCM tag). The version prefix exists
 * so future key rotation can introduce `v2:` etc. without re-encrypting at-rest data.
 * Legacy (unprefixed) ciphertexts are still accepted by decrypt() for backward
 * compatibility with OneDrive refresh tokens stored before the prefix was introduced.
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
        const val V1_PREFIX = "v1:"
    }

    private val secretKey: SecretKeySpec? by lazy {
        if (keyBase64.isBlank()) {
            log.warn("Token encryption key not configured. Token encryption/decryption will fail.")
            null
        } else {
            val keyBytes = Base64.getDecoder().decode(keyBase64)
            SecretKeySpec(keyBytes, "AES")
        }
    }

    @PostConstruct
    fun warnIfUnconfigured() {
        if (keyBase64.isBlank()) {
            log.warn(
                "app.onedrive.token-encryption-key is not configured " +
                    "(env ONEDRIVE_TOKEN_ENCRYPTION_KEY). " +
                    "At-rest encryption is disabled; the following features will fail when used: " +
                    "IMAP password storage (EmailAccount.encryptedPassword), " +
                    "OneDrive refresh token storage. " +
                    "Set the property and restart to enable encryption."
            )
        }
    }

    /**
     * Encrypt a plaintext string using AES-256-GCM. Output is `v1:` + Base64(IV || ciphertext || tag).
     *
     * @throws IllegalStateException if encryption key is not configured
     */
    fun encrypt(plaintext: String): String {
        val key = secretKey ?: throw IllegalStateException("Token encryption key not configured")

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return V1_PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypt a ciphertext envelope. Accepts both `v1:`-prefixed values and legacy
     * unprefixed Base64 (for OneDrive refresh tokens stored before the prefix existed).
     *
     * @throws IllegalStateException if encryption key is not configured
     */
    fun decrypt(ciphertext: String): String {
        val key = secretKey ?: throw IllegalStateException("Token encryption key not configured")

        val payload = if (ciphertext.startsWith(V1_PREFIX)) ciphertext.removePrefix(V1_PREFIX) else ciphertext
        val combined = Base64.getDecoder().decode(payload)

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
