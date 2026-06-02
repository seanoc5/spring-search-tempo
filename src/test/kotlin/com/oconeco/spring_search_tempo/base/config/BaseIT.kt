package com.oconeco.spring_search_tempo.base.config

import io.restassured.RestAssured
import io.restassured.config.SessionConfig
import jakarta.annotation.PostConstruct
import java.nio.charset.StandardCharsets
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.jdbc.SqlConfig
import org.springframework.test.context.jdbc.SqlMergeMode
import org.springframework.util.StreamUtils
import org.testcontainers.containers.PostgreSQLContainer


/**
 * Abstract base class to be extended by every IT test. Starts the Spring Boot context with a
 * Datasource connected to the Testcontainers Docker instance. The instance is reused for all tests,
 * with all data wiped out before each test.
 *
 * `clearAll.sql` is a PL/pgSQL DO block (issue #46) that discovers user tables from `pg_tables`
 * and TRUNCATE-CASCADE-s them. PL/pgSQL relies on `;` internally and Spring 6.2's `ScriptUtils`
 * splitter is not dollar-quote aware, so we configure the EOF-sentinel separator; each script
 * is forwarded to the JDBC driver as one string and PostgreSQL parses the `$$` boundary itself.
 */
@ActiveProfiles("it")
@Sql(
    scripts = ["/data/clearAll.sql", "/data/springUserData.sql"],
    config = SqlConfig(separator = "^^^ END OF SCRIPT ^^^")
)
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
abstract class BaseIT {

    // `@LocalServerPort` requires the `local.server.port` property to be set
    // by Spring Boot's servlet container. In the default `MOCK` web
    // environment that property is never registered, so a strict
    // `@LocalServerPort` field would fail injection on every subclass that
    // doesn't request `RANDOM_PORT`/`DEFINED_PORT`. Use `@Value` with a
    // sentinel default so MOCK subclasses just see `-1` and the RestAssured
    // wiring becomes a no-op for them.
    @Value("\${local.server.port:-1}")
    var serverPort: Int = -1

    @PostConstruct
    fun initRestAssured() {
        // Skip RestAssured port wiring when running under MOCK web env
        // (no real port). Subclasses that need RestAssured must declare
        // `@SpringBootTest(webEnvironment = RANDOM_PORT)` themselves.
        if (serverPort <= 0) return
        RestAssured.port = serverPort
        RestAssured.urlEncodingEnabled = false
        RestAssured.config =
                RestAssured.config().sessionConfig(SessionConfig().sessionIdName("SESSION"))
        // Configure default authentication for all tests
        RestAssured.authentication = RestAssured.preemptive().basic(LOGIN, PASSWORD)
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    fun readResource(resourceName: String): String =
            StreamUtils.copyToString(this.javaClass.getResourceAsStream(resourceName),
            StandardCharsets.UTF_8)


    companion object {

        @ServiceConnection
        val postgreSQLContainer = PostgreSQLContainer("pgvector/pgvector:pg17")

        const val LOGIN = "login"

        const val PASSWORD = "Bootify!"

        init {
            postgreSQLContainer.withReuse(true)
                    .start()
        }

    }

}
