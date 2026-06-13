// loyalty-campaign :domain — the pure decision kernel (ADR-0001 "Onion Architecture").
//
// No Spring, no JPA, no Jackson: just the deterministic Winner Selection algorithm + its strategy enum
// and the Campaign / Drawing state machines (status transition rules). The `java` plugin only — any
// Spring/JPA import here is a compile error, which is the whole point of the separate subproject.
plugins {
    java
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
