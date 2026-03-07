import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
    id("com.gradleup.shadow") version "8.3.0"
    application
}

group = "com.oconeco"
version = providers.gradleProperty("remoteCrawlerVersion")
    .orElse("0.1.0")
    .get()

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // CLI argument parsing
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")

    // JSON serialization (Jackson - no version conflicts)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    // Apache Tika for text extraction
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.23.1")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

application {
    mainClass.set("com.oconeco.remotecrawler.MainKt")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("remote-crawler")
    archiveClassifier.set("")
    archiveVersion.set(version.toString())

    // Merge service files (important for Tika)
    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "com.oconeco.remotecrawler.MainKt",
            "Implementation-Title" to "Remote Crawler CLI",
            "Implementation-Version" to version
        )
    }
}

// Convenience task to build the fat JAR
tasks.register("buildCli") {
    dependsOn(tasks.shadowJar)
    doLast {
        println("Built: build/libs/remote-crawler-${version}.jar")
    }
}
