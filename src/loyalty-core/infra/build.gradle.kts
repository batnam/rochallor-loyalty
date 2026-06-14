// loyalty-core :infra — persistence + messaging adapters.
//
// Depends on :domain (implements its ports); the :app module depends on :infra. Never the reverse.
// Holds the JPA @Entity persistence model, Spring Data repositories, the outbox relay, the canonical
// event envelopes, and the port adapters (JpaMembers). Spring + JPA live here, NOT in :domain.
//
// No Spring Boot plugin: this is a plain library on the classpath of the :app boot jar.
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
    }
}

dependencies {
    api(project(":domain"))

    // Persistence + messaging. `api` so the :app module sees the entity/repo/event types it consumes.
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.kafka:spring-kafka")
    api("org.springframework.boot:spring-boot-starter-json")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
