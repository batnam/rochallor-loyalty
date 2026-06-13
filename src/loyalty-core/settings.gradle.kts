rootProject.name = "loyalty-core"

// Onion split (ADR-0001): three rings as subprojects so the dependency rule is compiler-enforced.
//   :domain — pure kernel, no Spring/JPA on its classpath
//   :infra  — JPA persistence + messaging adapters; depends on :domain
//   (root)  — the :app Spring Boot application; depends on :infra and :domain
include(":domain")
include(":infra")
