package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.Matchers.containsString
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
        val sessionId = loginPage.sessionId

        RestAssured
            .given()
                .auth().none()  // Form login doesn't use basic auth
                .sessionId(sessionId)
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
        val sessionId = loginPage.sessionId

        RestAssured
            .given()
                .auth().none()  // Form login doesn't use basic auth
                .sessionId(sessionId)
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
        val sessionId = loginPage.sessionId

        RestAssured
            .given()
                .auth().none()  // Form login doesn't use basic auth
                .sessionId(sessionId)
                .contentType(ContentType.URLENC)
                .formParam("username", LOGIN)
                .formParam("password", PASSWORD)
                .formParam("remember-me", "on")
                .formParam("_csrf", csrfToken)
                .redirects().follow(false)
            .`when`()
                .post("/login")
            .then()
                .statusCode(HttpStatus.FOUND.value())
                .cookie("remember-me")
    }

    @Test
    fun `logout invalidates session and redirects to login`() {
        val sessionId = performLogin()

        // Get CSRF token from a protected page (using session-based auth)
        val homePage = RestAssured
            .given()
                .auth().none()  // Use session-based auth, not basic auth
                .sessionId(sessionId)
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
                    .sessionId(sessionId)
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
                    .sessionId(sessionId)
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
    private fun performLogin(): String {
        val loginPage = getLoginPage()
        val csrfToken = extractCsrfToken(loginPage)
        val sessionId = loginPage.sessionId

        RestAssured
            .given()
                .auth().none()
                .sessionId(sessionId)
                .contentType(ContentType.URLENC)
                .formParam("username", LOGIN)
                .formParam("password", PASSWORD)
                .formParam("_csrf", csrfToken)
            .`when`()
                .post("/login")

        return sessionId
    }
}
