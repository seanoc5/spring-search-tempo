package com.oconeco.spring_search_tempo.base.service

import com.microsoft.graph.models.Drive
import com.microsoft.graph.serviceclient.GraphServiceClient
import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.config.OneDriveConfiguration
import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.azure.core.credential.TokenRequestContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64


/**
 * Data class for Microsoft profile information obtained during OAuth2 flow.
 */
data class MicrosoftProfile(
    val microsoftAccountId: String,
    val email: String?,
    val displayName: String?
)

/**
 * Service for managing OneDrive OAuth2 PKCE flow and Microsoft Graph API connections.
 *
 * Uses the OAuth2 Authorization Code flow with PKCE (no client secret required)
 * for personal Microsoft accounts.
 *
 * The /consumers authority is used for personal accounts.
 * For work/school accounts, use /organizations or a tenant-specific authority.
 */
@Service
class OneDriveConnectionService(
    private val config: OneDriveConfiguration,
    private val oneDriveAccountService: OneDriveAccountService,
    private val tokenEncryptionService: TokenEncryptionService
) {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveConnectionService::class.java)
        private const val AUTHORITY = "https://login.microsoftonline.com/consumers"
        private const val TOKEN_ENDPOINT = "$AUTHORITY/oauth2/v2.0/token"
        private const val AUTHORIZE_ENDPOINT = "$AUTHORITY/oauth2/v2.0/authorize"
    }

    private val httpClient = HttpClient.newHttpClient()

    /**
     * Generate a PKCE code verifier (random 43-128 character string).
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate the PKCE code challenge from a code verifier (S256).
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Build the Microsoft OAuth2 authorization URL with PKCE parameters.
     *
     * @param state CSRF protection token (stored in session)
     * @param codeVerifier PKCE code verifier (stored in session)
     * @return Full authorization URL to redirect the user to
     */
    fun buildAuthorizationUrl(state: String, codeVerifier: String): String {
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val scopeParam = config.scopes.joinToString(" ")

        return "$AUTHORIZE_ENDPOINT?" + listOf(
            "client_id=${enc(config.clientId)}",
            "response_type=code",
            "redirect_uri=${enc(config.redirectUri)}",
            "scope=${enc(scopeParam)}",
            "state=${enc(state)}",
            "code_challenge=${enc(codeChallenge)}",
            "code_challenge_method=S256",
            "response_mode=query"
        ).joinToString("&")
    }

    /**
     * Exchange an authorization code for tokens using PKCE.
     *
     * @param code The authorization code from Microsoft callback
     * @param codeVerifier The PKCE code verifier (from session)
     * @return Pair of (access token, Microsoft profile info)
     */
    fun exchangeCodeForTokens(code: String, codeVerifier: String): Pair<String, MicrosoftProfile> {
        val scopeParam = config.scopes.joinToString(" ")

        val body = listOf(
            "client_id=${enc(config.clientId)}",
            "code=${enc(code)}",
            "redirect_uri=${enc(config.redirectUri)}",
            "grant_type=authorization_code",
            "code_verifier=${enc(codeVerifier)}",
            "scope=${enc(scopeParam)}"
        ).joinToString("&")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            log.error("Token exchange failed: status={}, body={}", response.statusCode(), response.body())
            throw RuntimeException("Token exchange failed: HTTP ${response.statusCode()}")
        }

        val json = parseJsonMap(response.body())
        val accessToken = json["access_token"] ?: throw RuntimeException("No access_token in response")
        val refreshToken = json["refresh_token"] ?: throw RuntimeException("No refresh_token in response")

        // Use access token to fetch user profile
        val profile = fetchUserProfile(accessToken)

        // Encrypt and store refresh token
        val encryptedRefreshToken = tokenEncryptionService.encrypt(refreshToken)

        return Pair(accessToken, profile)

        // Note: the caller should store encryptedRefreshToken via oneDriveAccountService.storeTokens()
    }

    /**
     * Exchange code, store tokens, and return all needed info.
     *
     * @return Triple of (accessToken, encryptedRefreshToken, profile)
     */
    fun exchangeCodeAndEncryptTokens(code: String, codeVerifier: String): Triple<String, String, MicrosoftProfile> {
        val scopeParam = config.scopes.joinToString(" ")

        val body = listOf(
            "client_id=${enc(config.clientId)}",
            "code=${enc(code)}",
            "redirect_uri=${enc(config.redirectUri)}",
            "grant_type=authorization_code",
            "code_verifier=${enc(codeVerifier)}",
            "scope=${enc(scopeParam)}"
        ).joinToString("&")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            log.error("Token exchange failed: status={}, body={}", response.statusCode(), response.body())
            throw RuntimeException("Token exchange failed: HTTP ${response.statusCode()}")
        }

        val json = parseJsonMap(response.body())
        val accessToken = json["access_token"] ?: throw RuntimeException("No access_token in response")
        val refreshToken = json["refresh_token"] ?: throw RuntimeException("No refresh_token in response")

        val profile = fetchUserProfile(accessToken)
        val encryptedRefreshToken = tokenEncryptionService.encrypt(refreshToken)

        return Triple(accessToken, encryptedRefreshToken, profile)
    }

    /**
     * Refresh the access token using a stored refresh token.
     *
     * @param accountId The OneDrive account ID
     * @return A fresh access token
     */
    fun refreshAccessToken(accountId: Long): String {
        val refreshToken = oneDriveAccountService.getDecryptedRefreshToken(accountId)
            ?: throw RuntimeException("No refresh token stored for account $accountId")

        val scopeParam = config.scopes.joinToString(" ")

        val body = listOf(
            "client_id=${enc(config.clientId)}",
            "refresh_token=${enc(refreshToken)}",
            "grant_type=refresh_token",
            "scope=${enc(scopeParam)}"
        ).joinToString("&")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            log.error("Token refresh failed for account {}: status={}", accountId, response.statusCode())
            throw RuntimeException("Token refresh failed: HTTP ${response.statusCode()}")
        }

        val json = parseJsonMap(response.body())
        val newAccessToken = json["access_token"] ?: throw RuntimeException("No access_token in refresh response")
        val newRefreshToken = json["refresh_token"]

        // Refresh tokens rotate - store the new one if provided
        if (newRefreshToken != null) {
            val encrypted = tokenEncryptionService.encrypt(newRefreshToken)
            oneDriveAccountService.storeTokens(accountId, encrypted)
        }

        return newAccessToken
    }

    /**
     * Get a GraphServiceClient for an account by refreshing the access token.
     */
    fun getGraphClient(accountId: Long): GraphServiceClient {
        val accessToken = refreshAccessToken(accountId)
        return createGraphClient(accessToken)
    }

    /**
     * Execute a block with a GraphServiceClient.
     */
    fun <T> withGraphClient(accountId: Long, block: (GraphServiceClient) -> T): T {
        val client = getGraphClient(accountId)
        return block(client)
    }

    /**
     * Create a GraphServiceClient from an access token.
     */
    fun createGraphClient(accessToken: String): GraphServiceClient {
        val credential = StaticTokenCredential(accessToken)
        return GraphServiceClient(credential)
    }

    /**
     * Test connection by fetching /me/drive.
     *
     * @return Drive info if successful
     */
    fun testConnection(accountId: Long): Drive {
        return withGraphClient(accountId) { client ->
            client.me().drive().get()
        }
    }

    /**
     * Fetch user profile using the access token.
     */
    private fun fetchUserProfile(accessToken: String): MicrosoftProfile {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://graph.microsoft.com/v1.0/me"))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            log.error("Failed to fetch user profile: status={}", response.statusCode())
            throw RuntimeException("Failed to fetch user profile: HTTP ${response.statusCode()}")
        }

        val json = parseJsonMap(response.body())
        return MicrosoftProfile(
            microsoftAccountId = json["id"] ?: "unknown",
            email = json["userPrincipalName"] ?: json["mail"],
            displayName = json["displayName"]
        )
    }

    /**
     * Simple JSON string field parser for token endpoint responses.
     * Only handles top-level string and number fields (no nesting).
     */
    private fun parseJsonMap(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)"\s*:\s*"([^"]*?)"""")
        pattern.findAll(json).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2]
        }
        // Also capture numeric values
        val numPattern = Regex(""""(\w+)"\s*:\s*(\d+)""")
        numPattern.findAll(json).forEach { match ->
            result.putIfAbsent(match.groupValues[1], match.groupValues[2])
        }
        return result
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

}


/**
 * Simple TokenCredential that always returns a static access token.
 * Used when we've already obtained the access token via manual token refresh.
 */
private class StaticTokenCredential(private val accessToken: String) : TokenCredential {
    override fun getToken(request: TokenRequestContext): Mono<AccessToken> {
        return Mono.just(AccessToken(accessToken, OffsetDateTime.now().plusHours(1)))
    }

    override fun getTokenSync(request: TokenRequestContext): AccessToken {
        return AccessToken(accessToken, OffsetDateTime.now().plusHours(1))
    }
}
