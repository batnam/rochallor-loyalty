# Rochallor Loyalty Platform — Sample Earning-Rule DSL Library

> **Artifact §11.4** of [`enterprise-architect.md`](../enterprise-architect.md#114-supporting-artifacts-to-build).
> Canonical examples of the constrained Earning-Rule DSL, one per Earn Source, with documented behaviour. The DSL grammar is formalised as a JSON Schema at [`docs/dsl/schema/earning-rule.schema.json`](../dsl/schema/earning-rule.schema.json); the example rules live in [`docs/dsl/examples/`](dsl/examples/) and each stores into one `earning_rule.dsl_json` row ([loyalty-earning DDL](data-catalogue.md#102-loyalty-earning)).

> **Note — this artifact also *defines* the DSL.** The DSL philosophy is fixed (constrained, non-Turing-complete, decision-table-first authoring); this artifact fixes the grammar. The canonical shape here is the **decision-table** form: one rule = an ordered list of `when → earn` rows + a hit policy + caps. This decision-table grammar is **the v1 canonical form**; the JSON Schema is the normative contract and this prose is illustrative.

---

## 1. Grammar reference

A rule's `dsl_json` is a decision table:

```jsonc
{
  "dslVersion": 1,                 // const 1
  "earnSource": "CARD_SPEND",      // must match earn_source.earn_source_code
  "inputs": ["mcc","amount"],      // declarative: fields the rows read (drives the UI)
  "hitPolicy": "FIRST",            // FIRST | COLLECT
  "tierMultiplier": true,          // apply the member's tier multiplier?
  "rounding": "FLOOR",             // FLOOR | ROUND | CEIL
  "rows": [ { "when": {…}, "earn": {…} } ],
  "caps": { … }
}
```

### 1.1 `when` — predicate (per row)
A flat **field → condition** map; multiple fields are **ANDed**. The field names are read from the canonical event payload (e.g. `CardSpendPosted.mcc`). A condition is one of:

| Form | Meaning |
|---|---|
| `"USD"` / `5` / `true` | equality shorthand |
| `"*"` | wildcard — matches anything (the catch-all / else row) |
| `{ "in": [...] }` / `{ "nin": [...] }` | membership / non-membership |
| `{ "eq": x }` / `{ "ne": x }` | explicit (in)equality |
| `{ "gt|gte|lt|lte": n }` | numeric comparison |
| `{ "between": [lo, hi] }` | inclusive range |

There is **no** `and`/`or`/`not` nesting and **no** expression language — OR is expressed by `in` or by additional rows. This is what keeps the DSL non-Turing-complete: a bad rule fails *schema validation at save*, it cannot loop or call out.

### 1.2 `earn` — formula (per row)
| `type` | Fields | Points formula |
|---|---|---|
| `RATE` | `perAmount` (>0), `points` | `points × (eventAmount / perAmount)` |
| `FIXED` | `points` | `points` (flat; ignores amount) |

Optional `balances` (default `["qualifying","redeemable"]`) selects which balance columns receive the delta — e.g. a promo crediting `["redeemable"]` only.

### 1.3 `hitPolicy`
- **`FIRST`** — rows are evaluated top-down; the first matching row wins and evaluation stops. Use for priority/category tables and exclusions (put the exclusion row first with `points: 0`).
- **`COLLECT`** — every matching row contributes; the per-row points are summed. Use for stacking bonuses.

### 1.4 `caps`
Closed set. `perEventMax` is stateless; the per-member caps accumulate in `cap_counter (programId, ruleId, memberId, window_key)`. These are **per-rule** caps; the Program-level **Source-Aggregate cap** (per Earn Source, across *all* that source's rules) and the Program per-member daily ceiling are a **separate enforcement layer outside the DSL** ([§7.4](../enterprise-architect.md#74-fraud--audit)).

| Cap | Window |
|---|---|
| `perEventMax` | a single event |
| `perMemberPerDay` | calendar day — **per-rule** (≠ the Program Source-Aggregate cap; see above) |
| `perMemberPerMonth` | calendar month |
| `perMemberPerRule` | lifetime per member (`1` = one-time award; `null` = unlimited) |

### 1.5 Evaluation order (Rule Engine)
1. **Dedup** the inbound event by `eventId` (`idempotency_key` table) — already-seen ⇒ drop.
2. **Fan out** to each Program the member is enrolled in (broadcast); steps 3–8 run per `(program, rule)` independently.
3. For each ACTIVE rule whose `earnSource` matches and whose validity window covers the event, evaluate `rows` per `hitPolicy` → raw points per row.
4. **Tier multiplier** — if `tierMultiplier`, multiply each row's points by the member's current `tier.benefits.multiplier` (default `1.0`).
5. **Rounding** — apply `rounding` to each row's points; for `COLLECT`, sum the rounded rows.
6. **`perEventMax`** — clamp the rule's total for this event.
7. **Per-member caps** — clamp cumulative against `cap_counter`; the awarded amount is `min(computed, remaining)`.
8. If net points > 0, call `loyalty-core` `POST /ledger/earn` with `sourceRef = "<eventId>:rule:<ruleId>"` (the idempotency key), the `balances`, and `expires_at` snapshotted from Program/Tier expiry; increment `cap_counter`.

Because each rule writes its own ledger entry under its own `rule_id`, stacking rules (base + campaign) produce **separate, independently-attributable** entries.

### 1.6 Dry-run
The same interpreter runs the BEP dry-run sandbox (`POST /programs/{programId}/rules/{ruleId}/dry-run`, [api-catalogue §3.2](api-catalogue.md)) against a historical event window — safe precisely because the DSL has no side effects.

---

## 2. Examples (with worked behaviour)

All examples validate against the schema; malformed variants (missing `perAmount`, unknown operator, `type: SCRIPT`, lowercase `earnSource`, negative `points`, extra keys) are **rejected** — see §3.

### 2.1 [`card-spend-tiered-mcc.json`](../dsl/examples/card-spend-tiered-mcc.json) — tiered category rates *(the headline)*
`FIRST` table: dining 3×, grocery 2×, everything-else 1×; tier multiplier on; daily cap 1000.

> **Event** `CardSpendPosted {amount: 40.00, currency: USD, mcc: "5812"}`, member tier **Gold (×1.5)**.
> Row 1 matches (dining) → `3 × 40 = 120` → ×1.5 = `180` → FLOOR `180` → ≤ perEventMax 500 → **+180 qualifying, +180 redeemable**, one `Earned` entry.
> Same event but `mcc 5411` (grocery) → row 2 → `2×40×1.5 = 120`. `mcc 5999` → row 3 → `1×40×1.5 = 60`.
> If the member already earned 900 today from this rule, the daily cap clamps the 180 to **100**.

### 2.2 [`card-spend-flat.json`](../dsl/examples/card-spend-flat.json) — simplest base rate
One row, `1 point / 1 USD`, tier multiplier on, daily cap 1000. The minimal viable earn rule; the `amount ≥ 1` guard avoids zero/penny noise.

### 2.3 [`balance-threshold-bonus.json`](../dsl/examples/balance-threshold-bonus.json) — core-banking balance maintenance *(DSL fixture; Earn Source deferred from v1)*
Earn Source `BALANCE_THRESHOLD` (from `corebank` account-state snapshots). `FIXED 500` when `balance ≥ 5000 USD`; **no** tier multiplier; `perMemberPerMonth: 500`.

> The Core Banking Adapter may emit a snapshot more than once a month; the monthly cap ensures the maintenance bonus is awarded **at most once per month** regardless of snapshot cadence. First qualifying snapshot in the month → +500; subsequent ones clamp to 0.

> **v1 status:** kept as a DSL-grammar conformance fixture (validates against `earning-rule.schema.json`). The `BALANCE_THRESHOLD` Earn Source itself is **deferred from v1** pending a state-derived earning decision that nails down `AccountBalanceSnapshot` cadence semantics. When activated, state-derived account events arrive via their own `loyalty.ingress.*` channel produced by the bank-side adapter.

### 2.4 [`term-deposit-banded-bonus.json`](../dsl/examples/term-deposit-banded-bonus.json) — banded fixed bonus
Earn Source `TERM_DEPOSIT_OPENED`. `FIRST` with the higher band first: `≥ 5000 → 5000 pts`; `1000–4999.99 → 1000 pts`. `perMemberPerRule: null` (every new deposit earns).

> `amount 7000` → row 1 → **5000**. `amount 2000` → row 1 fails, row 2 `between [1000,4999.99]` → **1000**. `amount 500` → no row matches → **0** (no entry written).

### 2.5 [`payment-exclusion.json`](../dsl/examples/payment-exclusion.json) — exclusion-then-earn (non-card rails)
Earn Source `FUND_TRANSFER`: the v1 catalogue routes `paymentType ∈ {FUND_TRANSFER, P2P_TRANSFER, QR_PAYMENT}` to this single Earn Source, and DSL rows discriminate via the canonical `paymentType` field preserved by the Bridge. Row 1 excludes `P2P_TRANSFER` with `FIXED 0` — defending against same-bank self-transfer farming, since rule `FIRST` semantics make the exclusion **stop** evaluation. Row 2 earns `1 pt / 2 USD` for USD-denominated transfers ≥ $1; row 3 earns `1 pt / 8,000 VND` for VND-denominated ones (Cambodia dual currency, both first-class).

> `{paymentType: "P2P_TRANSFER", currency: "USD", amount: 100}` → row 1 → **0**, stop. `{paymentType: "FUND_TRANSFER", currency: "USD", amount: 100}`, Gold → row 2 → `100/2 × 1 = 50` → ×1.5 = `75` → ≤ perEventMax 250 → **+75**. `{paymentType: "QR_PAYMENT", currency: "VND", amount: 200000}` (a KHQR merchant payment, not P2P-excluded) → row 3 → `200000/8000 × 1 = 25` → ×1.5 = `37.5` → FLOOR → **+37**.

### 2.6 [`campaign-bonus-dining.json`](../dsl/examples/campaign-bonus-dining.json) — stacking campaign bonus
A **campaign-linked** rule (the `earning_rule.campaign_id` column points at the campaign; activation is windowed). `COLLECT`, tier multiplier **off**, credits `redeemable` only.

> Runs **in addition to** 2.1's base rule. Dining `$40` during the campaign:
> base rule → +180 qualifying / +180 redeemable (entry under base `rule_id`);
> campaign rule → `3×40 = 120` redeemable-only (entry under campaign `rule_id`).
> Net: **qualifying +180, redeemable +300**, across two attributable ledger entries. Tier multiplier is off here to avoid double-multiplying the promo.

### 2.7 [`welcome-bonus-onetime.json`](../dsl/examples/welcome-bonus-onetime.json) — one-time award via a lifetime cap
`FIXED 1000` redeemable on the first card spend ≥ $10, `perMemberPerRule: 1`.

> First qualifying event → +1000 (cap_counter now 1). Every later event → cap remaining 0 → clamp to **0**, no entry. The lifetime cap, not bespoke "first-time" logic, models the one-time bonus — keeping the rule purely declarative.

---

## 3. Validation

The schema passes the Draft 2020-12 meta-schema check, all seven examples validate against it, and representative malformed rules are rejected:

| Malformed rule | Rejected by |
|---|---|
| `RATE` without `perAmount` | conditional `required` |
| unknown operator `{regex: …}` | `condition` `anyOf` (closed operator set) |
| extra top-level key (typo `tierMultipler`) | `additionalProperties: false` |
| `earn.type: "SCRIPT"` | `type` enum — **no code-execution escape hatch** |
| lowercase `earnSource` | `pattern` |
| empty `rows` | `minItems: 1` |
| negative `points` | `minimum: 0` |

Re-run: `python3 -c "import json,glob;from jsonschema import Draft202012Validator as V;s=json.load(open('docs/dsl/schema/earning-rule.schema.json'));V.check_schema(s);[V(s).validate(json.load(open(f))) for f in glob.glob('docs/dsl/examples/*.json')];print('ok')"`.

---

## 4. Notes & open items

- **N1 — `earnSource` / field vocabulary is not cross-validated by the schema.** The JSON Schema checks *shape*, not that `earnSource` exists in `earn_source` or that `when` fields exist in that source's event payload. The Rule Engine performs that semantic validation at save (it owns the registry). A future enhancement is a per-Earn-Source field catalogue the UI/validator can check against. (The v1 catalogue is narrowed to five active sources — `CARD_SPEND`, `BILL_PAYMENT`, `FUND_TRANSFER`, `TOPUP`, `TERM_DEPOSIT_OPENED` — plus the inactive fallback `PAYMENT_COMPLETED`; the canonical `when`-field vocabulary for each of these is now derivable from `loyalty.earn.translated.v1` payload + the producer-event schemas in [`external-consumed.yaml`](../asyncapi/external-consumed.yaml).)
- **N2 — Rounding granularity.** Rounding is applied per row before summation (so `COLLECT` sums already-rounded rows). Stated explicitly because "round then sum" vs "sum then round" can differ by a point; chosen per-row for attribution clarity.
- **N3 — Tier multiplier source.** Uses `tier.benefits.multiplier` (loyalty-core); a rule with `tierMultiplier:false` is the right default for promos to avoid compounding with the base rule.
- **N4 — Caps map to `cap_counter` windows.** `perMemberPerDay/Month/Rule` become `window_key`s; `perEventMax` is stateless. The nightly purge job clears expired day/month windows; `perMemberPerRule` rows persist.
- **N5 — Schema-evolution.** `dslVersion` is `const 1`; a v2 vocabulary (new operator/earn type) bumps it and ships with a new interpreter branch, consistent with the "extend the DSL + ship a new primitive" path.
