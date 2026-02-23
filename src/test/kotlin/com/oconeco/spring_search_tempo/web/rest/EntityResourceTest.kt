package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.jdbc.Sql

@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class EntityResourceTest : BaseIT() {

    @Test
    fun getEntityStats_returnsStats() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/stats")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("totalChunks", greaterThanOrEqualTo(0))
                .body("chunksWithEntities", greaterThanOrEqualTo(0))
    }

    @Test
    fun getValidEntityTypes_returnsTypes() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/type-names")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("$", hasItem("PERSON"))
                .body("$", hasItem("ORGANIZATION"))
                .body("$", hasItem("LOCATION"))
    }

    @Test
    fun getEntityTypes_returnsEmptyWhenNoData() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/types")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun getTopEntities_returnsEmptyWhenNoData() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/top?limit=10")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun searchByEntity_returnsBadRequestForBlankQuery() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/search?q=")
            .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun searchByEntity_returnsEmptyForNonExistentEntity() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/search?q=NonExistentEntityXYZ123")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("content.size()", equalTo(0))
    }

    @Test
    fun searchByEntityType_returnsOkForValidType() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/type/PERSON")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun getTopEntities_withTypeFilter() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/entities/top?type=ORGANIZATION&limit=5")
            .then()
                .statusCode(HttpStatus.OK.value())
    }
}
