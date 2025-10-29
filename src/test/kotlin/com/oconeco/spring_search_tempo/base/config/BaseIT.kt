package com.oconeco.spring_search_tempo.base.config

import io.restassured.RestAssured
import io.restassured.config.SessionConfig
import jakarta.annotation.PostConstruct
import java.nio.charset.StandardCharsets
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.jdbc.SqlMergeMode
import org.springframework.util.StreamUtils
import org.testcontainers.containers.PostgreSQLContainer


/**
 * Abstract base class to be extended by every IT test. Starts the Spring Boot context with a
 * Datasource connected to the Testcontainers Docker instance. The instance is reused for all tests,
 * with all data wiped out before each test.
 */
@ActiveProfiles("it")
@Sql(value = [
    "/data/clearAll.sql",
    "/data/springUserData.sql"
])
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
abstract class BaseIT {

    @LocalServerPort
    var serverPort = 0

    @PostConstruct
    fun initRestAssured() {
        RestAssured.port = serverPort
        RestAssured.urlEncodingEnabled = false
        RestAssured.config =
                RestAssured.config().sessionConfig(SessionConfig().sessionIdName("SESSION"))
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    fun readResource(resourceName: String): String =
            StreamUtils.copyToString(this.javaClass.getResourceAsStream(resourceName),
            StandardCharsets.UTF_8)


    companion object {

        @ServiceConnection
        val postgreSQLContainer = PostgreSQLContainer("postgres:18.0")

        const val LOGIN = "login"

        const val PASSWORD = "Bootify!"

        init {
            postgreSQLContainer.withReuse(true)
                    .start()
        }

    }

}
