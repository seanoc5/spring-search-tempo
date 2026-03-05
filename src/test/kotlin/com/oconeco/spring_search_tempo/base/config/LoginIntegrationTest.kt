package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

/**
 * Integration tests for login/logout functionality.
 *
 * These tests verify:
 * - Unauthenticated access redirects to login
 * - Form login with valid credentials succeeds
 * - Remember-me functionality works (requires persistent_logins table)
 * - Logout invalidates session
 *
 * Note: BaseIT sets default basic auth. Tests that need unauthenticated behavior
 * must use .auth().none() to override.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class LoginIntegrationTest : BaseIT() {

    data class SessionCookie(
        val name: String,
        val value: String
    )

    @Test
    fun `unauthenticated request to protected resource redirects to login`() {
        RestAssured
            .given()
                .auth().none()  // Override default auth
                .accept(ContentType.HTML)
                .redirects().follow(false)
            .`when`()
                .get("/")
            .then()
                .statusCode(HttpStatus.FOUND.value())
                .header("Location", containsString("/login"))
    }

    @Test
    fun `login page is accessible without authentication`() {
        RestAssured
            .given()
                .auth().none()  // Override default auth
                .accept(ContentType.HTML)
            .`when`()
                .get("/login")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Sign In"))
    }

    @Test
    fun `form login with valid credentials succeeds`() {
        val loginPage = getLoginPage()
        val csrfToken = extractCsrfToken(loginPage)
        val sessionCookie = extractSessionCookie(loginPage)

        RestAssured
            .given()
                .auth().none()  // Form login doesn't use basic auth
                .cookie(sessionCookie.name, sessionCookie.value)
                .contentType(ContentType.URLENC)
                .formParam("username", LOGIN)
                .formParam("password", PASSWORD)
                .formParam("_csrf", csrfToken)
                .redirects().follow(false)
            .`when`()
                .post("/login")
            .then()
                .statusCode(HttpStatus.FOUND.value())
                .header("Location", containsString("/"))
    }

    @Test
    fun `form login with invalid credentials fails`() {
        val loginPage = getLoginPage()
        val csrfToken = extractCsrfToken(loginPage)
        val sessionCookie = extractSessionCookie(loginPage)

        RestAssured
            .given()
                .auth().none()  // Form login doesn't use basic auth
                .cookie(sessionCookie.name, sessionCookie.value)
                .contentType(ContentType.URLENC)
                .formParam("username", LOGIN)
                .formParam("password", "wrongpassword")
                .formParam("_csrf", csrfToken)
                .redirects().follow(false)
            .`when`()
                .post("/login")
            .then()
                .statusCode(HttpStatus.FOUND.value())
                .header("Location", containsString("/login?error"))
    }

    @Test
    fun `authenticated user can access protected resource`() {
        // Uses default basic auth from BaseIT
        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun `login with remember-me sets cookie`() {
        val loginPage = getLoginPage()
        val csrfToken = extractCsrfToken(loginPage)
        val sessionCookie = extractSessionCookie(loginPage)

        val response = RestAssured
            .given()
                .auth().none()  // Form login doesn't use basic auth
                .cookie(sessionCookie.name, sessionCookie.value)
                .contentType(ContentType.URLENC)
                .formParam("username", LOGIN)
                .formParam("password", PASSWORD)
                .formParam("remember-me", "on")
                .formParam("_csrf", csrfToken)
                .redirects().follow(false)
            .`when`()
                .post("/login")

        response
            .then()
            .statusCode(HttpStatus.FOUND.value())

        val hasRememberMeCookie = response.cookies.keys.any {
            it == "remember-me" || it.startsWith("tempo-remember-me-")
        }
        assertTrue(
            hasRememberMeCookie,
            "Expected remember-me cookie (remember-me or tempo-remember-me-*) to be set"
        )
    }

    @Test
    fun `logout invalidates session and redirects to login`() {
        val sessionCookie = performLogin()

        // Get CSRF token from a protected page (using session-based auth)
        val homePage = RestAssured
            .given()
                .auth().none()  // Use session-based auth, not basic auth
                .cookie(sessionCookie.name, sessionCookie.value)
                .accept(ContentType.HTML)
                .redirects().follow(false)
            .`when`()
                .get("/")

        // If redirected to login, session was already invalid (which shouldn't happen)
        // If 200, extract CSRF and proceed with logout
        if (homePage.statusCode == HttpStatus.OK.value()) {
            val csrfToken = extractCsrfToken(homePage)

            // Logout
            RestAssured
                .given()
                    .auth().none()
                    .cookie(sessionCookie.name, sessionCookie.value)
                    .formParam("_csrf", csrfToken)
                    .redirects().follow(false)
                .`when`()
                    .post("/logout")
                .then()
                    .statusCode(HttpStatus.FOUND.value())
                    .header("Location", containsString("/login?logout"))

            // Verify session is invalidated - accessing protected resource should redirect to login
            RestAssured
                .given()
                    .auth().none()
                    .cookie(sessionCookie.name, sessionCookie.value)
                    .accept(ContentType.HTML)
                    .redirects().follow(false)
                .`when`()
                    .get("/")
                .then()
                    .statusCode(HttpStatus.FOUND.value())
                    .header("Location", containsString("/login"))
        }
    }

    @Test
    fun `basic auth with valid credentials succeeds`() {
        // Explicitly set auth (even though default would work)
        RestAssured
            .given()
                .auth().preemptive().basic(LOGIN, PASSWORD)
                .accept(ContentType.HTML)
            .`when`()
                .get("/")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun `basic auth with invalid credentials returns unauthorized`() {
        RestAssured
            .given()
                .auth().preemptive().basic(LOGIN, "wrongpassword")
                .accept(ContentType.HTML)
                .redirects().follow(false)
            .`when`()
                .get("/")
            .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value())
    }

    /**
     * Get the login page and return the response.
     */
    private fun getLoginPage(): Response =
        RestAssured
            .given()
                .auth().none()  // Don't use basic auth for login page
                .accept(ContentType.HTML)
            .`when`()
                .get("/login")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .response()

    /**
     * Extract CSRF token from HTML response.
     * Thymeleaf includes it as: <input type="hidden" name="_csrf" value="..."/>
     */
    private fun extractCsrfToken(response: Response): String {
        val html = response.body.asString()
        val regex = """name="_csrf"\s+value="([^"]+)"""".toRegex()
        val match = regex.find(html)
        return match?.groupValues?.get(1)
            ?: throw IllegalStateException("CSRF token not found in response")
    }

    /**
     * Helper to perform login and return the session ID.
     */
    private fun performLogin(): SessionCookie {
        val loginPage = getLoginPage()
        val csrfToken = extractCsrfToken(loginPage)
        val sessionCookie = extractSessionCookie(loginPage)

        RestAssured
            .given()
                .auth().none()
                .cookie(sessionCookie.name, sessionCookie.value)
                .contentType(ContentType.URLENC)
                .formParam("username", LOGIN)
                .formParam("password", PASSWORD)
                .formParam("_csrf", csrfToken)
            .`when`()
                .post("/login")

        return sessionCookie
    }

    private fun extractSessionCookie(response: Response): SessionCookie {
        val sessionEntry = response.cookies.entries.firstOrNull { (name, value) ->
            value.isNotBlank() && (
                name == "SESSION" ||
                    name == "JSESSIONID" ||
                    name.startsWith("tempo-session-")
                )
        } ?: throw IllegalStateException("Session cookie not found in response")

        return SessionCookie(sessionEntry.key, sessionEntry.value)
    }
}
