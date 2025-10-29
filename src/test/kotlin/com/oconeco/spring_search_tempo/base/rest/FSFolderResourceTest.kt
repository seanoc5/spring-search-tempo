package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
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
class FSFolderResourceTest : BaseIT() {

    @Autowired
    lateinit var fSFolderRepository: FSFolderRepository

    @Test
    @Sql(value = ["/data/fSFolderData.sql"])
    fun getAllFSFolders_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFolders")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(2))
                    .body("_embedded.fSFolderDTOList.get(0).id", Matchers.equalTo(1400))
                    .body("_links.self.href",
                    Matchers.endsWith("/api/fSFolders?page=0&size=20&sort=id,asc"))
    }

    @Test
    @Sql(value = ["/data/fSFolderData.sql"])
    fun getAllFSFolders_filtered() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFolders?filter=1401")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(1))
                    .body("_embedded.fSFolderDTOList.get(0).id", Matchers.equalTo(1401))
    }

    @Test
    @Sql(value = ["/data/fSFolderData.sql"])
    fun getFSFolder_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFolders/1400")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("uri", Matchers.equalTo("Dolor sit amet consectetur adipiscing elit."))
                    .body("_links.self.href", Matchers.endsWith("/api/fSFolders/1400"))
    }

    @Test
    fun getFSFolder_notFound() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/fSFolders/2066")
                .then()
                    .statusCode(HttpStatus.NOT_FOUND.value())
                    .body("code", Matchers.equalTo("NOT_FOUND"))
    }

    @Test
    fun createFSFolder_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/fSFolderDTORequest.json"))
                .`when`()
                    .post("/api/fSFolders")
                .then()
                    .statusCode(HttpStatus.CREATED.value())
        Assertions.assertEquals(1, fSFolderRepository.count())
    }

    @Test
    fun createFSFolder_missingField() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/fSFolderDTORequest_missingField.json"))
                .`when`()
                    .post("/api/fSFolders")
                .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .body("code", Matchers.equalTo("VALIDATION_FAILED"))
                    .body("fieldErrors.get(0).property", Matchers.equalTo("uri"))
                    .body("fieldErrors.get(0).code", Matchers.equalTo("REQUIRED_NOT_NULL"))
    }

    @Test
    @Sql(value = ["/data/fSFolderData.sql"])
    fun updateFSFolder_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/fSFolderDTORequest.json"))
                .`when`()
                    .put("/api/fSFolders/1400")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("_links.self.href", Matchers.endsWith("/api/fSFolders/1400"))
        Assertions.assertEquals("Sed blandit libero volutpat sed cras ornare arcu dui vivamus.",
                fSFolderRepository.findById(1400).orElseThrow().uri)
        Assertions.assertEquals(2, fSFolderRepository.count())
    }

    @Test
    @Sql(value = ["/data/fSFolderData.sql"])
    fun deleteFSFolder_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .delete("/api/fSFolders/1400")
                .then()
                    .statusCode(HttpStatus.NO_CONTENT.value())
        Assertions.assertEquals(1, fSFolderRepository.count())
    }

}
