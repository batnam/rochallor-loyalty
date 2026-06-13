// loyalty-core :domain — the pure kernel (ADR-0001 "Onion Architecture with a pure domain model").
//
// PURITY IS ENFORCED BY THE ABSENCE OF DEPENDENCIES HERE. There is intentionally no Spring and no
// JPA on this classpath, so `import jakarta.persistence.*` or `import org.springframework.*` inside
// :domain simply will not compile. Keep this dependency list empty (plain Java + java.time only).
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

dependencies {
    // intentionally empty — the domain depends on nothing but the JDK.
    // Domain unit tests live in the :loyalty-core (app) test source set, which already has JUnit.
}
