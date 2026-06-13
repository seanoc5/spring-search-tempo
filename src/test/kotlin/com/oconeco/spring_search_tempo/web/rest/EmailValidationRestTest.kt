package com.oconeco.spring_search_tempo.web.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.service.EmailAccountForm
import com.oconeco.spring_search_tempo.base.service.EmailConfigValidationService
import com.oconeco.spring_search_tempo.base.service.TcpReachabilityProbe
import com.oconeco.spring_search_tempo.base.service.email.EmailTestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * Integration tests for `/api/email/accounts/validate` — the dry-run REST
 * endpoint from issue #6. Uses MockMvc + a real GreenMail server so we
 * exercise the full pipeline (jackson body binding → service → IMAP
 * probe → wire response) without booting the full Spring context.
 */
@DisplayName("EmailValidationResource REST — POST /api/email/accounts/validate")
class EmailValidationRestTest {

    companion object {
        @JvmField
        @RegisterExtension
        val greenMail = EmailTestFixtures.imapExtension()
    }

    private lateinit var mockMvc: MockMvc
    private val mapper = ObjectMapper()

    @BeforeEach
    fun setup() {
        val service = EmailConfigValidationService(TcpReachabilityProbe())
        val controller = EmailValidationResource(service)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
        EmailTestFixtures.createUser(
            greenMail,
            EmailTestFixtures.DEFAULT_USER,
            EmailTestFixtures.DEFAULT_PASSWORD
        )
    }

    @Test
    @DisplayName("POST returns 200 with folder count on success")
    fun postReturnsOk() {
        val form = EmailAccountForm(
            host = "127.0.0.1",
            port = greenMail.imap.port,
            username = EmailTestFixtures.DEFAULT_USER,
            password = EmailTestFixtures.DEFAULT_PASSWORD,
            useSsl = false
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/email/accounts/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(form))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("OK"))
            .andExpect(jsonPath("$.folderCount").isNumber)
            .andExpect { result ->
                val folderCount = mapper.readTree(result.response.contentAsString)["folderCount"].asInt()
                assertThat(folderCount).isGreaterThan(0)
            }
    }

    @Test
    @DisplayName("POST returns 400 with structured error on auth failure")
    fun postReturnsBadRequestOnAuthFailure() {
        val form = EmailAccountForm(
            host = "127.0.0.1",
            port = greenMail.imap.port,
            username = EmailTestFixtures.DEFAULT_USER,
            password = "wrong",
            useSsl = false
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/email/accounts/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(form))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.outcome").value("AUTH_FAILED"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }
}
