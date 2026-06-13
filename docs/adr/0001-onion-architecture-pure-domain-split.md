---
status: accepted
---

# Onion Architecture with a pure (framework-free) domain model

We are moving all seven Java services to Onion Architecture: each service splits into a `:domain` Gradle subproject (pure Java — no Spring, no `jakarta.persistence` on its classpath), an `:infra` subproject holding JPA adapters and mappers, and an `:app` subproject for orchestration and wiring. The domain owns narrow ports (`Members`, `Ledger`, `Reservations`, …); infrastructure satisfies them. We chose the **full pure-domain split (A2)** over keeping `@Entity`-annotated domain objects behind ports (A1) because only A2 puts the dependency rule under compiler control — a `:domain` subproject with no JPA dependency makes an outward import fail to compile, rather than relying on convention or an ArchUnit test that can be muted.

## Considered options

- **A1 — narrow ports, keep `@Entity` domain objects ("DDD-on-Spring").** Cheaper, gets the test seam, but the domain still imports `jakarta.persistence` and the dependency rule is convention-only. Rejected: it doesn't deliver Onion's core promise, and the mapping layer A2 adds is not "shallow" here because the persistence model genuinely diverges from the domain model (see below).
- **A2 — pure domain model + separate persistence model + mapper (chosen).**

## Key boundary decisions

- **`Member` is the aggregate root of the points-posting boundary.** It owns its balance/tier/cohort projections and its open cohorts, is the only producer of new Point Ledger Entries (preserving invariant P5 and Ledger immutability), and records Domain Events for the outbox rather than publishing them. It loads *current state + this-transaction entries only* — never the unbounded Ledger history. See CONTEXT.md "Aggregate Root".
- **`Reservation` is a deliberately separate aggregate, NOT part of Member.** It keeps its own row lock, its `sumActiveHeld` SQL aggregate, and its TTL sweeper. Folding reservations into the Member aggregate was rejected: it would replace the O(1) effective-balance SUM with an O(n-held) collection load, widen the Member write lock, and force the background TTL sweeper to take `Member`-for-update — contending head-on with the earn hot path.
- **`RedeemableBalance` is a pure domain service** (`effective(member, heldTotal)`), so the redeem-eligibility check is testable with no database, without reservations living inside the Member aggregate.

## Consequences

- The save adapter, not the domain, owns the row lock + same-transaction flush + outbox drain — the unit-of-work lifecycle moves behind the `Members` port.
- In-memory port fakes become the second adapter, so domain-logic tests no longer require Testcontainers/Postgres.
- A per-service build restructure (one-time cost) and hand-written (or MapStruct) mappers between each domain object and its persistence record.
