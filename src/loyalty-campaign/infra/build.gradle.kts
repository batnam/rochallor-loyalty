// loyalty-campaign :infra — JPA persistence + the transactional-outbox relay.
//
// Depends on :domain; the :app module depends on :infra. Never the reverse. Holds the @Entity types
// (Campaign, Drawing, DrawingEntry, WinnerRecord) + their Spring Data repositories, the hash-chained
// audit-log writer, the ShedLock-guarded OutboxRelay, and the @ConfigurationProperties (mirroring
// loyalty-core's CoreProperties placement). campaign has no outbound HTTP, so no spring-web here.
plugins {
    `java-library`
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
    }
}

dependencies {
    api(project(":domain"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-json")
    // OutboxRelay stages + drains via KafkaTemplate.
    api("org.springframework.kafka:spring-kafka")
    // OutboxRelay.drain() is @SchedulerLock-guarded (multi-pod safety) — L3 §4.
    api("net.javacrumbs.shedlock:shedlock-spring:${property("shedlockVersion")}")
    // Jackson 2 (com.fasterxml) for outbox payloads — Spring Boot 4's starter-json is Jackson 3.
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
