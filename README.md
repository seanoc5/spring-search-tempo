# Spring Search Tempo

This app was created with Bootify.io - tips on working with the code [can be found here](https://bootify.io/next-steps/).

The app is a starting template for a full-text search engine. The 'example' use case is a local file system crawl, but the app is designed to be extensible to other data sources and potentially even enterprise and e-commerce search foundation.

For the curious: `tempo` is a portmanteau of `temporal`, `tempus`, which are Latin words for `time` and `search`. Throw in a touch of `template` and you (er, **I**) get `tempo`.

## Development

When starting the application `docker compose up` is called and the app will connect to the contained services. [Docker](https://www.docker.com/get-started/) must be available on the current system.

During development it is recommended to use the profile `local`. In IntelliJ `-Dspring.profiles.active=local` can be added in the VM options of the Run Configuration after enabling this property in "Modify options". Create your own `application-local.yml` file to override settings for development.

After starting the application it is accessible under `localhost:8089`.

## Testing requirements

Testcontainers is used for running the integration tests. Due to the reuse flag, the container will not shut down after the tests. It can be stopped manually if needed.

The `ModularityTest` verifies the module structure and adds a documentation in `build/spring-modulith-docs`.

## Build

The application can be tested and built using the following command:

```
gradlew clean build
```

Start your application with the following command - here with the profile `production`:

```
java -Dspring.profiles.active=production -jar ./build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar
```

If required, a Docker image can be created with the Spring Boot plugin. Add `SPRING_PROFILES_ACTIVE=production` as environment variable when running the container.

```
gradlew bootBuildImage --imageName=com.oconeco/spring-search-tempo
```

## Further readings

* [Gradle user manual](https://docs.gradle.org/)  
* [Spring Boot reference](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)  
* [Spring Data JPA reference](https://docs.spring.io/spring-data/jpa/reference/jpa.html)
* [Thymeleaf docs](https://www.thymeleaf.org/documentation.html)  
* [Bootstrap docs](https://getbootstrap.com/docs/5.3/getting-started/introduction/)  
* [Htmx in a nutshell](https://htmx.org/docs/)  
* [Learn Spring Boot with Thymeleaf](https://www.wimdeblauwe.com/books/taming-thymeleaf/)  
