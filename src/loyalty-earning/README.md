# loyalty-earning

The **event-driven Rule Engine** — turns translated Earn Events into `Earned` Ledger entries
([C4 L3 `loyalty-earning`](../../docs/c4/level-3-loyalty-earning.md)).

It consumes the bridge's `loyalty.earn.translated.v1` (customer-scoped `EarnEvent`), resolves the
customer to a `loyalty-core` Member, evaluates every active per-Program **Earning Rule** written in a
constrained JSON DSL, enforces caps, calls `loyalty-core`'s `POST /ledger/earn` to award points, and
publishes `loyalty.earning.points_earned.v1`. It is **stateless on the hot path** (its only state is
its own RDS) and scales with upstream event throughput, not app traffic. It is also the **only**
component allowed to interpret the rule DSL (the Anti-Corruption Layer for the rule grammar).

> **New here?** Read [`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) — a self-contained design + user guide
> (the DSL, the hot-path flow, authoring/dry-run, data/config reference, run & operate).

## Invariants it upholds

- **Idempotency before work** — the Rule Engine short-circuits replays via `idempotency_key` before
  touching the rule cache or DSL; core's `(sourceRef, entryType)` uniqueness is the second line of defence.
- **Conflict = sum** — every matching Rule fires independently and writes its own Ledger entry with a
  distinct `sourceRef = eventId:ruleId`, so a single EarnEvent can produce multiple entries (each
  individually auditable and reversible).
- **Caps as conditional UPDATEs** — `cap_counter` is decremented by a single
  `UPDATE … WHERE remaining >= :n` (no SELECT-then-UPDATE race); "0 rows affected" = exhausted → drop.
- **DSL is constrained, not Turing-complete** — a small audited decision-table grammar, schema-validated
  at save; the interpreter is a **pure function** shared by the hot path and dry-run.
- **No direct Ledger writes** — earning is a *client* of core's Ledger API; it never touches `point_ledger`.
- **Transactional outbox** — `loyalty.earning.points_earned.v1` is staged in the engine's transaction and
  relayed to MSK separately (at-least-once; dedup downstream by `eventId`).

## Slice status

| Slice | Status |
|---|---|
| DSL Interpreter (pure: predicates, FIRST/COLLECT, RATE/FIXED, tier multiplier, rounding, perEventMax, balances) | ✅ scaffolded (TDD) |
| DSL Validator (networknt schema gate against `earning-rule.schema.json`) | ✅ scaffolded (TDD) |
| Cap Counter (window-key derivation + atomic conditional decrement) | ✅ scaffolded (TDD) |
| Rule Engine (idempotency → resolve → eval → cap → ledger → outbox → idempotency) | ✅ scaffolded (TDD) |
| Earn Event Consumer (`loyalty.earn.translated.v1`) + Member Resolver (REST → core) | ✅ scaffolded |
| Ledger Client (REST → core `POST /ledger/earn`) + Outbox Relay + PointsEarned event | ✅ scaffolded |
| Earn Source / Rule CRUD + dry-run + approval-gated status, hash-chained audit | ✅ scaffolded |
| Dry-run evaluator over the EarnEvent replay store (side-effect-free) | ✅ scaffolded |
| Cap Purge Job (nightly, ShedLock) | ✅ scaffolded |

**Deferred from this scaffold:** the tier-benefit **multiplier** for `tierMultiplier` rules (the
`MemberContext` is neutral — multiplier 1.0 — until Program/tier config ownership lands; rules default
`tierMultiplier:false`); deferred Earn Source families (engagement / state-derived / referral —
[open-items §2](../../docs/supporting-artifacts/open-items-v1-earning.md)); S3 WORM sealing of the audit
archive; the 4-eyes `RULE_ACTIVATION` workflow itself (delegated to BEP — earning only hardens the
`confirm` seam with `bepApprovalRef`); mTLS wiring (cluster infra).

## Build & run

Stack: **Java 25** · **Spring Boot 4.x** · **Spring Data JPA + PostgreSQL** · **Flyway** · **ShedLock**
· **Spring Kafka** · **networknt json-schema-validator** · **Gradle (Kotlin DSL)** — synced with
`loyalty-core` / `loyalty-integration-bridge`.

```bash
./gradlew test          # fast unit suite (DSL/caps/engine) + Testcontainers IT (Postgres + Kafka + WireMock-stubbed core)
./gradlew bootRun       # needs Postgres ($DB_URL), a Kafka broker ($KAFKA_BOOTSTRAP_SERVERS), and core ($CORE_BASE_URL)
```

> Requires a **JDK 25** toolchain. Flyway owns the schema; Hibernate runs `ddl-auto: validate`. The IT
> stubs core's Ledger API + member lookup with WireMock, so no sibling service is needed to run it.
