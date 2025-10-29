package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.repos.AnnotationRepository
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.test.context.jdbc.Sql


@ApplicationModuleTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES
)
class AnnotationResourceTest : BaseIT() {

    @Autowired
    lateinit var annotationRepository: AnnotationRepository

    @Test
    @Sql(value = ["/data/annotationData.sql"])
    fun getAllAnnotations_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/annotations")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(2))
                    .body("_embedded.annotationDTOList.get(0).id", Matchers.equalTo(1500))
                    .body("_links.self.href",
                    Matchers.endsWith("/api/annotations?page=0&size=20&sort=id,asc"))
    }

    @Test
    @Sql(value = ["/data/annotationData.sql"])
    fun getAllAnnotations_filtered() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/annotations?filter=1501")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(1))
                    .body("_embedded.annotationDTOList.get(0).id", Matchers.equalTo(1501))
    }

    @Test
    @Sql(value = ["/data/annotationData.sql"])
    fun getAnnotation_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/annotations/1500")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("label",
                    Matchers.equalTo("Dictum fusce ut placerat orci nulla pellentesque dignissim enim."))
                    .body("_links.self.href", Matchers.endsWith("/api/annotations/1500"))
    }

    @Test
    fun getAnnotation_notFound() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/annotations/2166")
                .then()
                    .statusCode(HttpStatus.NOT_FOUND.value())
                    .body("code", Matchers.equalTo("NOT_FOUND"))
    }

    @Test
    fun createAnnotation_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/annotationDTORequest.json"))
                .`when`()
                    .post("/api/annotations")
                .then()
                    .statusCode(HttpStatus.CREATED.value())
        Assertions.assertEquals(1, annotationRepository.count())
    }

    @Test
    fun createAnnotation_missingField() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/annotationDTORequest_missingField.json"))
                .`when`()
                    .post("/api/annotations")
                .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .body("code", Matchers.equalTo("VALIDATION_FAILED"))
                    .body("fieldErrors.get(0).property", Matchers.equalTo("label"))
                    .body("fieldErrors.get(0).code", Matchers.equalTo("REQUIRED_NOT_NULL"))
    }

    @Test
    @Sql(value = ["/data/annotationData.sql"])
    fun updateAnnotation_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/annotationDTORequest.json"))
                .`when`()
                    .put("/api/annotations/1500")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("_links.self.href", Matchers.endsWith("/api/annotations/1500"))
        Assertions.assertEquals("Ullamcorper eget nulla facilisi etiam dignissim diam.",
                annotationRepository.findById(1500).orElseThrow().label)
        Assertions.assertEquals(2, annotationRepository.count())
    }

    @Test
    @Sql(value = ["/data/annotationData.sql"])
    fun deleteAnnotation_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .delete("/api/annotations/1500")
                .then()
                    .statusCode(HttpStatus.NO_CONTENT.value())
        Assertions.assertEquals(1, annotationRepository.count())
    }

}
