import org.springframework.boot.gradle.tasks.run.BootRun
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("kapt") version "1.9.25"
}

group = "com.oconeco"
version = "0.5.3"

springBoot {
    buildInfo()
}

kotlin {
    jvmToolchain {
        this.languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.3")
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.2")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.batch:spring-batch-core")
    implementation("org.springframework.batch:spring-batch-infrastructure")
    implementation("org.springframework.batch:spring-batch-integration")

    runtimeOnly("org.postgresql:postgresql")

    // pgvector support for vector embeddings
    implementation("com.pgvector:pgvector:0.1.6")

    // Spring AI + Ollama for embedding generation
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("io.github.wimdeblauwe:error-handling-spring-boot-starter:4.6.0")
    implementation("org.springframework.boot:spring-boot-starter-hateoas")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("nz.net.ultraq.thymeleaf:thymeleaf-layout-dialect")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.webjars:bootstrap:5.3.8")
    implementation("org.webjars.npm:bootstrap-icons:1.11.3")
    implementation("org.webjars.npm:htmx.org:2.0.7")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")

    implementation("org.mapstruct:mapstruct:1.6.3")

    // Apache Tika for text extraction from various file formats
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")

    // Stanford CoreNLP for NLP processing (Named Entity Recognition, POS tagging, parsing)
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.5")
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.5:models") {
        // Exclude unnecessary model files to reduce size if needed
        // Can be selective about which models to include
    }

    // Email crawling - Jakarta Mail for IMAP/SMTP
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // HTML text extraction (for email body processing)
    implementation("org.jsoup:jsoup:1.18.1")

    // SQLite JDBC driver for reading Firefox places.sqlite
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // OneDrive / Microsoft Graph SDK
    implementation("com.microsoft.graph:microsoft-graph:6.5.0")
    implementation("com.azure:azure-identity:1.14.2")

    kapt("org.mapstruct:mapstruct-processor:1.6.3")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.batch:spring-batch-test")

    // GreenMail for IMAP testing
    testImplementation("com.icegreen:greenmail-junit5:2.0.1")
}

kapt {
    includeCompileClasspath = false
}

tasks.withType<KaptWithoutKotlincTask>().configureEach {
    kaptProcessJvmArgs.add("-Xmx768m")
}

tasks.getByName<BootRun>("bootRun") {
    environment["SPRING_PROFILES_ACTIVE"] = environment["SPRING_PROFILES_ACTIVE"] ?: "local"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.set(listOf("-Xjsr305=strict", "-Xjvm-default=all"))
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Increase heap size for tests due to Stanford CoreNLP models and Testcontainers
    maxHeapSize = "4g"
    jvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200",
        "-Xms512m",
        "-Xmx4g"
    )
}

// Handle duplicate dependencies in bootJar
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("printAppVersion") {
    doLast {
        println(project.version)
    }
}
