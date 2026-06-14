// loyalty-integration-bridge — ingress gateway.
// Stack: Java 25 (LTS) · Spring Boot 4.x · Spring Kafka · networknt JSON Schema validator · Gradle Kotlin DSL.
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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-web")  // HTTPS ingress for the voucher webhook
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.networknt:json-schema-validator:1.5.6")
    // Spring Boot 4 defaults to Jackson 3 (tools.jackson); networknt + our code use Jackson 2
    // (com.fasterxml). Pin a Jackson 2 ObjectMapper + JSR-310 for Instant (see JacksonConfig).
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
