package com.oconeco.spring_search_tempo.base.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Smoke test that exercises a real `analyze()` call against the default
 * `parse.model`. Pins the contract from issue #131: the parser model
 * referenced by `StanfordNLPService` MUST be present on the classpath in
 * a default `./gradlew test` run, with no separately-downloaded models.
 *
 * Constructs the service directly (no Spring context) so the default-value
 * fallback on the `@Value` parameter is exercised — i.e. this also fails
 * if someone later removes the `englishPCFG.ser.gz` default without
 * adding the SR jar to the build.
 *
 * ~5 s to initialize the pipeline; runs once per process.
 */
class StanfordNLPServiceSmokeTest {

    @Test
    fun `pipeline initializes with default bundled parser model and analyzes a short sentence`() {
        val service = StanfordNLPService()

        val result = service.analyze("Barack Obama visited Berlin on Tuesday.")

        assertThat(result.sentences).hasSize(1)
        assertThat(result.posTag).isNotEmpty()
        // PCFG parser populates a constituency tree; this also catches a
        // pipeline that initialized but lost the `parse` annotator.
        assertThat(result.dependencyParses).hasSize(1)
        assertThat(result.dependencyParses[0].constituencyTree).isNotBlank()
    }
}
