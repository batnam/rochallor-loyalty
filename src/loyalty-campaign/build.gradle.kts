// loyalty-campaign — Campaigns + Drawings (sweepstakes) service (C4 L3 loyalty-campaign).
// Stack: Java 25 (LTS) · Spring Boot 4.x · Spring Data JPA + PostgreSQL · Flyway · ShedLock
//        · Spring Kafka · Gradle Kotlin DSL — synced with loyalty-core / loyalty-earning / loyalty-redemption.
// No outbound HTTP: campaign only serves REST (CRUD + the T-13 entry surface) and *emits* events through
// the transactional outbox. The in-pod Drawing Scheduler fires Winner Selection under ShedLock. The IT
// needs only Postgres + Kafka (no WireMock) — campaign has no synchronous downstream dependency.
// NOTE: pin/verify exact versions against your environment — Spring Boot 4.x + Java 25 are recent.

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

extra["shedlockVersion"] = "5.16.0"

dependencies {
    // Onion rings (ADR-0001): app → infra → domain.
    implementation(project(":domain"))
    implementation(project(":infra"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.kafka:spring-kafka")

    // Migrations. Spring Boot 4 splits autoconfiguration into modules — flyway-core alone does not
    // wire Flyway into the context; spring-boot-flyway provides the autoconfiguration.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Distributed lock for multi-pod @Scheduled jobs (Drawing Scheduler + Outbox Relay) — L3 §4/§5.
    implementation("net.javacrumbs.shedlock:shedlock-spring:${property("shedlockVersion")}")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${property("shedlockVersion")}")

    // Spring Boot 4 defaults to Jackson 3 (tools.jackson); we pin Jackson 2 (com.fasterxml) +
    // JSR-310 so outbox / Kafka payloads serialize the same way core + earning + redemption emit them.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
