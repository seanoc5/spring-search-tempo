package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit test for the audit startup validator (issue #105, acceptance criterion `e`).
 *
 * Three cases: happy path, peek-depth < 1, peek-depth > absolute-max-depth.
 * Each error message must include both numeric values and both property
 * names so an operator can fix the YAML in one read.
 */
class AuditConfigValidatorTest {

    @Test
    @DisplayName("happy path: 1 <= peek-depth <= absolute-max-depth passes")
    fun happyPath() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = 3)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 50)

        AuditConfigValidator(auditProps, crawlConfig).validate()
        // No exception; validate() returned normally.
    }

    @Test
    @DisplayName("peek-depth < 1 throws IllegalStateException with both value and property name")
    fun peekDepthTooLow() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = 0)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 50)

        assertThatThrownBy { AuditConfigValidator(auditProps, crawlConfig).validate() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("app.audit.hidden-gem-peek-depth")
            .hasMessageContaining("=0")
            .hasMessageContaining(">= 1")
    }

    @Test
    @DisplayName("peek-depth > absolute-max-depth throws with both values + property names")
    fun peekDepthExceedsAbsolute() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = 51)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 50)

        assertThatThrownBy { AuditConfigValidator(auditProps, crawlConfig).validate() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("app.audit.hidden-gem-peek-depth")
            .hasMessageContaining("=51")
            .hasMessageContaining("app.crawl.absolute-max-depth")
            .hasMessageContaining("=50")
    }

    @Test
    @DisplayName("boundary: peek-depth == absolute-max-depth passes (<= is inclusive)")
    fun boundaryEqualPasses() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = 50)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 50)

        AuditConfigValidator(auditProps, crawlConfig).validate()
    }

    @Test
    @DisplayName("boundary: peek-depth == 1 passes (>= 1 is inclusive)")
    fun boundaryMinimumPasses() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = 1)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 50)

        AuditConfigValidator(auditProps, crawlConfig).validate()
    }

    @Test
    @DisplayName("negative peek-depth also throws (treated the same as 0)")
    fun negativePeekDepth() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = -1)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 50)

        assertThatThrownBy { AuditConfigValidator(auditProps, crawlConfig).validate() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("=-1")
    }

    @Test
    @DisplayName("error message names BOTH properties when peek-depth > absolute (operator-friendly)")
    fun errorMessageNamesBothProperties() {
        val auditProps = AuditProperties(hiddenGemPeekDepth = 100)
        val crawlConfig = CrawlConfiguration(absoluteMaxDepth = 20)

        val ex = runCatching { AuditConfigValidator(auditProps, crawlConfig).validate() }
            .exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
        val msg = ex!!.message!!
        // An operator should be able to find both knobs from the message.
        assertThat(msg).contains("app.audit.hidden-gem-peek-depth", "app.crawl.absolute-max-depth")
        assertThat(msg).contains("100", "20")
    }
}
