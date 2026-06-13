rootProject.name = "loyalty-earning"

// Onion split (ADR-0001): the pure DSL kernel is its own subproject so the dependency rule is
// compiler-enforced — :domain has no Spring/JPA/Jackson on its classpath.
include(":domain")
include(":infra")
