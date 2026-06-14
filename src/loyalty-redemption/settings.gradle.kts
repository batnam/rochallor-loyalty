rootProject.name = "loyalty-redemption"

// Onion split: the pure decision kernel (Saga decider, Eligibility engine, Fulfillment SPI)
// is its own subproject so the dependency rule is compiler-enforced — :domain has no Spring/JPA on its
// classpath. :app → :infra → :domain.
include(":domain")
include(":infra")
