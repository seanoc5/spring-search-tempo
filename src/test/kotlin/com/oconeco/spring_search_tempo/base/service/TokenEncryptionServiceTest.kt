package com.oconeco.spring_search_tempo.base.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

@DisplayName("TokenEncryptionService — envelope and roundtrip")
class TokenEncryptionServiceTest {

    private val key: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val service = TokenEncryptionService(key)

    @Test
    @DisplayName("encrypt emits v1: prefix and roundtrips through decrypt")
    fun encryptEmitsVersionPrefixAndRoundtrips() {
        val plaintext = "s3cret!"
        val ciphertext = service.encrypt(plaintext)

        assertTrue(ciphertext.startsWith("v1:"), "expected v1: envelope prefix, got: $ciphertext")
        assertNotEquals(plaintext, ciphertext)
        assertEquals(plaintext, service.decrypt(ciphertext))
    }

    @Test
    @DisplayName("encrypt with the same plaintext twice yields different ciphertexts (IV randomization)")
    fun encryptIsNondeterministic() {
        val plaintext = "p@ss"
        val a = service.encrypt(plaintext)
        val b = service.encrypt(plaintext)
        assertNotEquals(a, b)
        assertEquals(plaintext, service.decrypt(a))
        assertEquals(plaintext, service.decrypt(b))
    }

    @Test
    @DisplayName("decrypt accepts legacy unprefixed ciphertext (backward compat with OneDrive tokens)")
    fun decryptAcceptsLegacyUnprefixedCiphertext() {
        val plaintext = "legacy-onedrive-token"
        val v1Ciphertext = service.encrypt(plaintext)
        val legacyCiphertext = v1Ciphertext.removePrefix("v1:")

        // Sanity: the legacy form is just the raw Base64(IV || ciphertext || tag).
        assertNotNull(Base64.getDecoder().decode(legacyCiphertext))

        assertEquals(plaintext, service.decrypt(legacyCiphertext))
    }

    @Test
    @DisplayName("isConfigured reflects key presence")
    fun isConfiguredReflectsKey() {
        assertTrue(service.isConfigured())
        assertTrue(!TokenEncryptionService("").isConfigured())
    }
}
