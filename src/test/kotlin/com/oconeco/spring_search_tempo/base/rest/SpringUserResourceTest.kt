package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus



@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    
)
class SpringUserResourceTest : BaseIT() {

    @Autowired
    lateinit var springUserRepository: SpringUserRepository

    @Test
    fun getAllSpringUsers_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springUsers")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(2))
                    .body("_embedded.springUserDTOList.get(0).id", Matchers.equalTo(1600))
                    .body("_links.self.href",
                    Matchers.endsWith("/api/springUsers?page=0&size=20&sort=id,asc"))
    }

    @Test
    fun getAllSpringUsers_filtered() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springUsers?filter=1601")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(1))
                    .body("_embedded.springUserDTOList.get(0).id", Matchers.equalTo(1601))
    }

    @Test
    fun getSpringUser_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springUsers/1600")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("firstName",
                    Matchers.equalTo("Sed faucibus turpis in eu mi bibendum neque."))
                    .body("_links.self.href", Matchers.endsWith("/api/springUsers/1600"))
    }

    @Test
    fun getSpringUser_notFound() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springUsers/2266")
                .then()
                    .statusCode(HttpStatus.NOT_FOUND.value())
                    .body("code", Matchers.equalTo("NOT_FOUND"))
    }

    @Test
    fun createSpringUser_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/springUserDTORequest.json"))
                .`when`()
                    .post("/api/springUsers")
                .then()
                    .statusCode(HttpStatus.CREATED.value())
        Assertions.assertEquals(3, springUserRepository.count())  // 2 from springUserData.sql + 1 new
    }

    @Test
    fun createSpringUser_missingField() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/springUserDTORequest_missingField.json"))
                .`when`()
                    .post("/api/springUsers")
                .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .body("code", Matchers.equalTo("VALIDATION_FAILED"))
                    .body("fieldErrors.get(0).property", Matchers.equalTo("label"))
                    .body("fieldErrors.get(0).code", Matchers.equalTo("REQUIRED_NOT_NULL"))
    }

    @Test
    fun updateSpringUser_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/springUserDTORequest.json"))
                .`when`()
                    .put("/api/springUsers/1600")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("_links.self.href", Matchers.endsWith("/api/springUsers/1600"))
        Assertions.assertEquals("Pellentesque nec nam aliquam sem. Risus viverra adipiscing at in tellus.",
                springUserRepository.findById(1600).orElseThrow().firstName)
        Assertions.assertEquals(2, springUserRepository.count())
    }

    @Test
    fun deleteSpringUser_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .delete("/api/springUsers/1600")
                .then()
                    .statusCode(HttpStatus.NO_CONTENT.value())
        Assertions.assertEquals(1, springUserRepository.count())
    }

}
