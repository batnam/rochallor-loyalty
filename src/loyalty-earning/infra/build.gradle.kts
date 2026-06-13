// loyalty-earning :infra — JPA persistence model + repositories (ADR-0001 "Onion Architecture").
//
// Depends on :domain; the :app module depends on :infra. Never the reverse. Holds the @Entity types
// (EarningRule, EarnSource, CapCounter, …) and their Spring Data repositories. No Spring Boot plugin.
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
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-json")

    // The JpaRules adapter parses the persisted dsl_json blob into the pure RuleDsl (DslParser).
    // Spring Boot 4's starter-json is Jackson 3 (tools.jackson); the platform standardises on
    // Jackson 2 (com.fasterxml) for stored/emitted JSON, so pin it explicitly here too — matching
    // the app module's jackson-datatype-jsr310:2.18.2 pin.
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
