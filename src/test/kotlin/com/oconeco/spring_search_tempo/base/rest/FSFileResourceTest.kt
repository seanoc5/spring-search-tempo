package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
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
class FSFileResourceTest : BaseIT() {

    @Autowired
    lateinit var fSFileRepository: FSFileRepository

    @Test
    @Sql(value = ["/data/fSFileData.sql"])
    fun getAllFSFiles_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFiles")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(2))
                    .body("_embedded.fSFileDTOList.get(0).id", Matchers.equalTo(1000))
                    .body("_links.self.href",
                    Matchers.endsWith("/api/fSFiles?page=0&size=20&sort=id,asc"))
    }

    @Test
    @Sql(value = ["/data/fSFileData.sql"])
    fun getAllFSFiles_filtered() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFiles?filter=1001")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(1))
                    .body("_embedded.fSFileDTOList.get(0).id", Matchers.equalTo(1001))
    }

    @Test
    @Sql(value = ["/data/fSFileData.sql"])
    fun getFSFile_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFiles/1000")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("uri", Matchers.equalTo("Dolor sit amet consectetur adipiscing elit."))
                    .body("_links.self.href", Matchers.endsWith("/api/fSFiles/1000"))
    }

    @Test
    fun getFSFile_notFound() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFiles/1666")
                .then()
                    .statusCode(HttpStatus.NOT_FOUND.value())
                    .body("code", Matchers.equalTo("NOT_FOUND"))
    }

    @Test
    fun createFSFile_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/fSFileDTORequest.json"))
                .`when`()
                    .post("/api/fSFiles")
                .then()
                    .statusCode(HttpStatus.CREATED.value())
        Assertions.assertEquals(1, fSFileRepository.count())
    }

    @Test
    fun createFSFile_missingField() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/fSFileDTORequest_missingField.json"))
                .`when`()
                    .post("/api/fSFiles")
                .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .body("code", Matchers.equalTo("VALIDATION_FAILED"))
                    .body("fieldErrors.get(0).property", Matchers.equalTo("uri"))
                    .body("fieldErrors.get(0).code", Matchers.equalTo("REQUIRED_NOT_NULL"))
    }

    @Test
    @Sql(value = ["/data/fSFileData.sql"])
    fun updateFSFile_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/fSFileDTORequest.json"))
                .`when`()
                    .put("/api/fSFiles/1000")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("_links.self.href", Matchers.endsWith("/api/fSFiles/1000"))
        Assertions.assertEquals("Sed blandit libero volutpat sed cras ornare arcu dui vivamus.",
                fSFileRepository.findById(1000).orElseThrow().uri)
        Assertions.assertEquals(2, fSFileRepository.count())
    }

    @Test
    @Sql(value = ["/data/fSFileData.sql"])
    fun deleteFSFile_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .delete("/api/fSFiles/1000")
                .then()
                    .statusCode(HttpStatus.NO_CONTENT.value())
        Assertions.assertEquals(1, fSFileRepository.count())
    }

}
