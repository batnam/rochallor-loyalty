// loyalty-earning :domain — the pure kernel (ADR-0001 "Onion Architecture with a pure domain model").
//
// PURITY IS ENFORCED BY THE ABSENCE OF DEPENDENCIES HERE. No Spring, no JPA, no Jackson on this
// classpath — so the DSL semantics (RuleDsl, Condition, DslInterpreter, …) stay framework-free and
// unit-testable in isolation. Keep this dependency list empty (plain Java only).
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
    // Domain unit tests live in the :loyalty-earning (app) test source set, which already has JUnit.
}
