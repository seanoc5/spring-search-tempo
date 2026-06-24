import org.springframework.boot.gradle.tasks.run.BootRun
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("kapt") version "1.9.25"
}

group = "com.oconeco"
version = "0.5.4"

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

    // Flyway for tracked, repeatable database migrations.
    // Coexists with ddl-auto: update during rapid development (see ADR-004).
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // pgvector support for vector embeddings
    implementation("com.pgvector:pgvector:0.1.6")

    // Spring AI + Ollama for embedding generation
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Caffeine: bounded, observable in-memory caches for hot-path processors
    // (see CombinedCrawlProcessor). Version managed by Spring Boot BOM.
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("io.github.wimdeblauwe:error-handling-spring-boot-starter:4.6.0")
    implementation("org.springframework.boot:spring-boot-starter-hateoas")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("nz.net.ultraq.thymeleaf:thymeleaf-layout-dialect")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.webjars:bootstrap:5.3.8")
    implementation("org.webjars.npm:bootstrap-icons:1.11.3")
    implementation("org.webjars.npm:htmx.org:2.0.7")

    implementation("org.mapstruct:mapstruct:1.6.3")

    // Apache Tika for text extraction from various file formats
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    // java-diff-utils for line/paragraph-level diffs in the smart-diff feature (issue #144).
    // ~200 KB jar with no transitive dependencies. Apache 2.0.
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")

    // Apache Commons Compress for per-entry enumeration of .zip/.tar/.7z/.jar archives (issue #118).
    // Tika already pulls in commons-compress transitively, but pinning the dep makes our
    // dependency on the API surface explicit and survives future Tika upgrades.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Stanford CoreNLP for NLP processing (Named Entity Recognition, POS tagging, parsing)
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.5")
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.5:models") {
        // Exclude unnecessary model files to reduce size if needed
        // Can be selective about which models to include
    }

    // Stanford SR (shift-reduce) parser — opt-in via `./gradlew downloadSRParser`.
    // The model jar is too large (~600 MB) to bundle in the deploy artifact, so
    // we ship PCFG as the default and let operators drop the SR jar into `libs/`.
    // `developmentOnly` puts the jar on bootRun's classpath (so the StanfordNLPService
    // classpath probe finds englishSR.ser.gz) but excludes it from `bootJar` and
    // `test` runtime — so `./gradlew test` keeps using bundled PCFG, and the deploy
    // artifact stays slim unless someone explicitly bundles the jar.
    developmentOnly(fileTree("libs") { include("stanford-srparser-*.jar") })

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
    testImplementation("org.springframework.security:spring-security-test")
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

    // Pin the Docker Engine API version negotiated by Testcontainers' shaded
    // docker-java client. Without this, the client defaults to API 1.32 and
    // is rejected by modern Docker daemons (Docker 25+ requires API >= 1.40).
    // The `api.version` system property is read by
    // `org.testcontainers.shaded.com.github.dockerjava.core.DefaultDockerClientConfig`.
    systemProperty("api.version", System.getProperty("api.version", "1.45"))
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

// Opt-in download of the Stanford shift-reduce (SR) parser model jar.
// PCFG is the bundled default (see issue #131 / PR #135); SR is ~20× faster
// and ~1 F1 point more accurate but the model is ~600 MB so we don't ship it.
// Run `./gradlew downloadSRParser` once on a dev/prod box, restart, and
// StanfordNLPService auto-detects englishSR.ser.gz on the classpath.
tasks.register("downloadSRParser") {
    group = "build setup"
    description = "Download the Stanford SR (shift-reduce) parser model jar into libs/ for faster NLP."

    val srJarName = "stanford-srparser-2014-10-23-models.jar"
    // Stanford pins one SR model release per CoreNLP version family; the
    // 2014-10-23 jar is the one that ships with CoreNLP 4.5.x (it's what
    // `Maven Central edu.stanford.nlp:stanford-corenlp:4.5.5:models-english`
    // documents and what nlp.stanford.edu/software/srparser.html links to).
    val srJarUrl = "https://nlp.stanford.edu/software/$srJarName"
    val libsDir = layout.projectDirectory.dir("libs")
    val srJarFile = libsDir.file(srJarName).asFile

    outputs.file(srJarFile)
    outputs.upToDateWhen { srJarFile.exists() && srJarFile.length() > 0L }

    doLast {
        if (srJarFile.exists() && srJarFile.length() > 0L) {
            val sizeMB = srJarFile.length() / (1024 * 1024)
            println("Stanford SR parser already present at ${srJarFile.relativeTo(projectDir)} (${sizeMB} MB); skipping download.")
            return@doLast
        }
        srJarFile.parentFile.mkdirs()
        val tmpFile = File(srJarFile.parentFile, "${srJarName}.part")
        println("Downloading Stanford SR parser from $srJarUrl ...")
        try {
            val url = URI(srJarUrl).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw GradleException("HTTP ${conn.responseCode} ${conn.responseMessage}")
            }
            conn.inputStream.use { input: InputStream ->
                tmpFile.outputStream().use { output: OutputStream ->
                    input.copyTo(output)
                }
            }
            if (tmpFile.length() == 0L) {
                tmpFile.delete()
                throw GradleException("Downloaded SR parser jar is empty ($srJarUrl)")
            }
            tmpFile.renameTo(srJarFile)
            val sizeMB = srJarFile.length() / (1024 * 1024)
            println("Downloaded ${sizeMB} MB to ${srJarFile.relativeTo(projectDir)}")
            println("Restart the app; StanfordNLPService will auto-detect SR on classpath.")
        } catch (e: Exception) {
            tmpFile.delete()
            throw GradleException(
                "Failed to download Stanford SR parser from $srJarUrl: ${e.message}. " +
                "Check network connectivity or set app.nlp.parse-model to point at a local model.",
                e,
            )
        }
    }
}
