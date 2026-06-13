// loyalty-admin-bff — Bank Employee Portal (BEP) edge API (C4 L2 BFFs; loyalty-admin-bff.yaml).
// Aggregation only: no datastore, no Kafka. It fans out to loyalty-core / loyalty-earning /
// loyalty-redemption / loyalty-campaign over REST and composes their responses for the BEP.
// Stack: Java 25 (LTS) · Spring Boot 4.x · Spring MVC (RestClient) · Gradle Kotlin DSL — synced with
// the backend services. JPA / Flyway / Kafka / ShedLock / Postgres are intentionally absent.

plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.batnam.loyalty"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")

    // Spring Boot 4 defaults to Jackson 3 (tools.jackson); we pin Jackson 2 (com.fasterxml) + JSR-310
    // so the JSON we read from upstreams and emit to clients matches the canonical platform shapes.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Stubs all four upstream services (core / earning / redemption / campaign) in the IT — a BFF has
    // no datastore of its own, so the only integration surface is the outbound REST it aggregates.
    // Standalone (shaded) jar avoids Jetty/Jakarta clashes with Spring Boot 4.
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
