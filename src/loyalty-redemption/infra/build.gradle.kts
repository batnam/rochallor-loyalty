// loyalty-redemption :infra — JPA persistence + outbound clients + Fulfillment adapters.
//
// Depends on :domain; the :app module depends on :infra. Never the reverse. Holds the @Entity types +
// Spring Data repositories, the JPA adapters for the domain ports, the RestClient-based ledger/partner
// clients, the Fulfillment adapter implementations + registry, the outbox relay, and the
// @ConfigurationProperties (mirroring loyalty-core's CoreProperties placement). No Spring Boot plugin.
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
    // RestClient for the ledger / Payment Hub / voucher / campaign clients (no servlet container needed).
    api("org.springframework:spring-web")
    // OutboxRelay stages + drains via KafkaTemplate.
    api("org.springframework.kafka:spring-kafka")
    // Jackson 2 (com.fasterxml) for outbox payloads — Spring Boot 4's starter-json is Jackson 3.
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
