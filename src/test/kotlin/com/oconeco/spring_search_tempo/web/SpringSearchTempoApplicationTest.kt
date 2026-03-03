package com.oconeco.spring_search_tempo.web

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus


@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SpringSearchTempoApplicationTest : BaseIT() {

    @Test
    fun contextLoads() {
    }

    @Test
    fun springSessionWorks() {
        val createResponse: Response = RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/sessionCreate")
                .then()
                    .statusCode(HttpStatus.OK.value())
                .extract()
                .response()

        val sessionCookie = requireNotNull(
            createResponse.detailedCookies.firstOrNull {
                it.name.startsWith("tempo-session-") ||
                    it.name == "SESSION" ||
                    it.name == "JSESSIONID"
            }
        ) { "Expected session cookie in response headers=${createResponse.headers}" }

        RestAssured
                .given()
                    .cookie(sessionCookie.name, sessionCookie.value)
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/sessionRead")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body(Matchers.equalTo("test"))

    }

}
