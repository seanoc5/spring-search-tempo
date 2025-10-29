package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.repos.SpringRoleRepository
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
class SpringRoleResourceTest : BaseIT() {

    @Autowired
    lateinit var springRoleRepository: SpringRoleRepository

    @Test
    @Sql(value = ["/data/springRoleData.sql"])
    fun getAllSpringRoles_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springRoles")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("page.totalElements", Matchers.equalTo(2))
                    .body("_embedded.springRoleDTOList.get(0).id", Matchers.equalTo(1700))
                    .body("_links.self.href",
                    Matchers.endsWith("/api/springRoles?page=0&size=20&sort=id,asc"))
    }

    @Test
    @Sql(value = ["/data/springRoleData.sql"])
    fun getSpringRole_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springRoles/1700")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("label",
                    Matchers.equalTo("Dictum fusce ut placerat orci nulla pellentesque dignissim enim."))
                    .body("_links.self.href", Matchers.endsWith("/api/springRoles/1700"))
    }

    @Test
    fun getSpringRole_notFound() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .get("/api/springRoles/2366")
                .then()
                    .statusCode(HttpStatus.NOT_FOUND.value())
                    .body("code", Matchers.equalTo("NOT_FOUND"))
    }

    @Test
    fun createSpringRole_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/springRoleDTORequest.json"))
                .`when`()
                    .post("/api/springRoles")
                .then()
                    .statusCode(HttpStatus.CREATED.value())
        Assertions.assertEquals(1, springRoleRepository.count())
    }

    @Test
    fun createSpringRole_missingField() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/springRoleDTORequest_missingField.json"))
                .`when`()
                    .post("/api/springRoles")
                .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value())
                    .body("code", Matchers.equalTo("VALIDATION_FAILED"))
                    .body("fieldErrors.get(0).property", Matchers.equalTo("label"))
                    .body("fieldErrors.get(0).code", Matchers.equalTo("REQUIRED_NOT_NULL"))
    }

    @Test
    @Sql(value = ["/data/springRoleData.sql"])
    fun updateSpringRole_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                    .contentType(ContentType.JSON)
                    .body(readResource("/requests/springRoleDTORequest.json"))
                .`when`()
                    .put("/api/springRoles/1700")
                .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("_links.self.href", Matchers.endsWith("/api/springRoles/1700"))
        Assertions.assertEquals("Ullamcorper eget nulla facilisi etiam dignissim diam.",
                springRoleRepository.findById(1700).orElseThrow().label)
        Assertions.assertEquals(2, springRoleRepository.count())
    }

    @Test
    @Sql(value = ["/data/springRoleData.sql"])
    fun deleteSpringRole_success() {
        RestAssured
                .given()
                    .accept(ContentType.JSON)
                .`when`()
                    .delete("/api/springRoles/1700")
                .then()
                    .statusCode(HttpStatus.NO_CONTENT.value())
        Assertions.assertEquals(1, springRoleRepository.count())
    }

}
