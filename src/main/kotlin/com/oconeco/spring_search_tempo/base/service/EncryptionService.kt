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
 * AES-256-GCM symmetric encryption service for securing at-rest secrets, including
 * OAuth2 refresh tokens (OneDrive) and IMAP passwords.
 *
 * The encryption key is loaded from the `app.security.encryption-key` property
 * (typically set via the APP_ENCRYPTION_KEY environment variable).
 *
 * For backward compatibility, if `app.security.encryption-key` is blank the service
 * falls back to the legacy `app.onedrive.token-encryption-key` / `ONEDRIVE_TOKEN_ENCRYPTION_KEY`
 * value and logs a deprecation warning. Same AES-GCM key, no re-encryption needed —
 * operators should rename the property/env at their convenience.
 *
 * Envelope: `v1:` + Base64(IV || ciphertext || GCM tag). The version prefix exists
 * so future key rotation can introduce `v2:` etc. without re-encrypting at-rest data.
 * Legacy (unprefixed) ciphertexts are still accepted by decrypt() for backward
 * compatibility with OneDrive refresh tokens stored before the prefix was introduced.
 */
@Service
class EncryptionService(
    @Value("\${app.security.encryption-key:}") private val primaryKey: String,
    @Value("\${app.onedrive.token-encryption-key:}") private val legacyKey: String
) {

    companion object {
        private val log = LoggerFactory.getLogger(EncryptionService::class.java)
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        const val V1_PREFIX = "v1:"
    }

    private val keyBase64: String = when {
        primaryKey.isNotBlank() -> primaryKey
        legacyKey.isNotBlank() -> legacyKey
        else -> ""
    }

    private val secretKey: SecretKeySpec? by lazy {
        if (keyBase64.isBlank()) {
            log.warn("Encryption key not configured. Encryption/decryption will fail.")
            null
        } else {
            val keyBytes = Base64.getDecoder().decode(keyBase64)
            SecretKeySpec(keyBytes, "AES")
        }
    }

    @PostConstruct
    fun warnIfUnconfigured() {
        if (primaryKey.isBlank() && legacyKey.isNotBlank()) {
            log.warn(
                "app.onedrive.token-encryption-key is deprecated; " +
                    "rename to app.security.encryption-key (env APP_ENCRYPTION_KEY). " +
                    "Continuing with the legacy value for now."
            )
        }
        if (keyBase64.isBlank()) {
            log.warn(
                "app.security.encryption-key is not configured " +
                    "(env APP_ENCRYPTION_KEY). " +
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
        val key = secretKey ?: throw IllegalStateException(
            "Encryption key not configured (set app.security.encryption-key / env APP_ENCRYPTION_KEY)"
        )

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
        val key = secretKey ?: throw IllegalStateException(
            "Encryption key not configured (set app.security.encryption-key / env APP_ENCRYPTION_KEY)"
        )

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

/**
 * @deprecated Renamed to [EncryptionService] — this service encrypts more than OneDrive tokens
 * (IMAP passwords too). Update references; this typealias will be removed in a future release.
 */
@Deprecated(
    message = "Renamed to EncryptionService — this service encrypts IMAP passwords too, not just OneDrive tokens.",
    replaceWith = ReplaceWith("EncryptionService")
)
typealias TokenEncryptionService = EncryptionService
