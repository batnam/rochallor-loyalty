rootProject.name = "loyalty-campaign"

// Onion split: the pure decision kernel (Winner Selection algorithm + the Campaign/Drawing
// state machines) is its own subproject so the dependency rule is compiler-enforced — :domain has no
// Spring/JPA on its classpath. :app → :infra → :domain.
include(":domain")
include(":infra")
