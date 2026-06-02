package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Pins down the Test-Connection password-collision bug:
 *
 *   The Test Connection input field used `name="password"` — the same name as the
 *   IMAP-password save field. HTMX `hx-include="closest form"` submitted BOTH on
 *   the wire as repeated `password=` params. Spring concatenated them with a
 *   comma, producing e.g. `gdxt saph ivib vmfp,` — Gmail rejected with
 *   `[AUTHENTICATIONFAILED]`. Users saw "Authentication failed" on Test
 *   Connection even with a valid Gmail App Password.
 *
 * The fix renames the Test Connection input to `validationPassword` and updates
 * the controller to bind that param instead of `password`. These tests verify
 * both halves of the wiring without needing to actually connect to Gmail:
 *
 *  1. Sending `validationPassword=<valid-format>` + an unreachable host → the
 *     validation service reports UNREACHABLE (not LOGIN_REJECTED/"missing
 *     password"). Proves the rename param IS being read.
 *
 *  2. Sending only the legacy `password=` param (no validationPassword) → the
 *     service reports LOGIN_REJECTED "Missing required fields". Proves the
 *     controller no longer accidentally reads from the legacy field.
 *
 *  3. Sending BOTH `validationPassword=real` and `password=` (simulating the
 *     original DOM-duplicate-name bug) → UNREACHABLE (NOT auth_failed and NOT
 *     login_rejected). Proves Spring never sees a comma-joined password.
 *
 * Uses 127.0.0.1:1 (refused immediately on Linux) so the TCP probe fails fast.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController.validate — Test Connection form binding (issue: GMAIL_APP_PASSWORD comma-join bug)")
class EmailAccountValidateControllerIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("validationPassword form param is the one read — host=refused yields UNREACHABLE, NOT missing-password")
    fun validationPasswordIsRead() {
        mockMvc.perform(
            post("/emailAccounts/validate")
                .header("HX-Request", "true")
                .param("email", "user@example.com")
                .param("imapHost", "127.0.0.1")
                .param("imapPort", "1")
                .param("useSsl", "false")
                .param("validationPassword", "any-non-blank-value")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            // Must reach the TCP probe (UNREACHABLE) — not be short-circuited at
            // the "missing required fields" precheck which only fires when
            // password is blank. If validationPassword isn't being read, the
            // form.password would be null → LOGIN_REJECTED would fire instead.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("UNREACHABLE")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Missing required fields"))))
    }

    @Test
    @DisplayName("Legacy `password=` form param is IGNORED — controller binds only validationPassword now")
    fun legacyPasswordParamIsIgnored() {
        mockMvc.perform(
            post("/emailAccounts/validate")
                .header("HX-Request", "true")
                .param("email", "user@example.com")
                .param("imapHost", "127.0.0.1")
                .param("imapPort", "1")
                .param("useSsl", "false")
                // Note: ONLY the legacy `password` field. No `validationPassword`.
                // If the controller still bound from `password`, this would
                // reach the TCP probe (UNREACHABLE). With the fix, form.password
                // is null → LOGIN_REJECTED "Missing required fields".
                .param("password", "legacy-field-should-not-be-read")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("LOGIN_REJECTED")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Missing required fields")))
    }

    @Test
    @DisplayName("Regression guard: both validationPassword and legacy password present → NOT comma-joined, validationPassword wins")
    fun bothFieldsPresent_noCommaJoin() {
        mockMvc.perform(
            post("/emailAccounts/validate")
                .header("HX-Request", "true")
                .param("email", "user@example.com")
                .param("imapHost", "127.0.0.1")
                .param("imapPort", "1")
                .param("useSsl", "false")
                .param("validationPassword", "real-app-password")
                // Simulates the old bug: legacy field also present (e.g. from a
                // stale browser-cached form). MUST NOT be merged into the
                // validationPassword binding.
                .param("password", "")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("UNREACHABLE")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Missing required fields"))))
    }
}
