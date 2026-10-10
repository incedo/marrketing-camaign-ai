# Spec: LLM Journey Planner

**Status:** implemented 2026-10-10 (§3.4 subscriptions deferred) · **Date:** 2026-10-10 · **Author:** Mr I (with Kees)

## 1. Goal

Replace the fixed journey template in `LlmJourneyEngine` with an LLM that **predicts
the journey a customer needs** from their profile, measured behavior, products and
campaign goal — while keeping every produced journey structurally valid by construction.

Core principle: **the LLM proposes, the code disposes.** The LLM is the planner; the
existing `Journey.validate()` is the gatekeeper. Nothing invalid can ship.

## 2. Non-goals

- No new node types (the six stay: start, end, wait-on-event, decision, A/B test, marketing).
- No real-time re-planning mid-journey (v1 plans once, upfront).
- No model training / fine-tuning (v1 uses prompting + a feedback slot, see §9).

## 3. Input shaping — what changes and why

The current `JourneyInput` is close, but three shapings are needed before it is
LLM-ready. Rule of thumb: **code computes facts, the LLM interprets them.**

### 3.1 Behavior: raw events → summary + capped timeline

Dumping raw event lists into a prompt is expensive and noisy, and forces the LLM to
do arithmetic it is bad at. Instead, compute a `BehaviorSummary` in code:

```kotlin
data class BehaviorSummary(
    val daysSinceLastActive: Long?,      // null = never
    val daysSinceLastPurchase: Long?,    // null = never
    val eventCounts30d: Map<String, Int>,
    val eventCounts90d: Map<String, Int>,
    val purchaseCount90d: Int,
    val totalSpendCents90d: Long,
    val trend: Trend,                    // INCREASING | STABLE | DECREASING | UNKNOWN
    val isNewCustomer: Boolean,          // first event within 30d
)
```

Plus `recentEvents: List<BehaviorEvent>` capped at the **20 most recent**, oldest-first,
for texture ("opened 3 emails, clicked none" reads differently as a timeline than as counts).
The existing `MeasuredBehavior` helpers (`count`, `lastOf`, `hasEventSince`) are the
building blocks; `BehaviorSummary` is a pure function of `(MeasuredBehavior, now)`.

### 3.2 Campaign: bare prompt → goal + structured constraints

A plain-language goal alone lets the LLM invent channels, discounts and timelines you
never approved. Split it:

```kotlin
data class CampaignConstraints(
    val allowedChannels: Set<Channel>,   // e.g. [EMAIL, PUSH] — never SMS uninvited
    val maxDiscountPercent: Int? = null,  // hard ceiling, e.g. 20
    val locale: String = "nl-NL",
    val language: String = "nl",
    val forbiddenTopics: Set<String> = emptySet(),
    val maxNodes: Int = 12,
    val maxWaitHours: Long = 24 * 14,
)

data class Campaign(
    val goal: String,                    // plain language, as today
    val constraints: CampaignConstraints,
)
```

### 3.3 New slot: past performance (the "prediction" part)

What turns generation into prediction is evidence. Add an optional feed the planner
can be steered by; empty on day one, filled by the learning loop (§9):

```kotlin
data class ArchetypePerformance(
    val archetype: String,               // e.g. "winback-discount-ladder"
    val segment: String,                 // e.g. "dormant-90d"
    val journeys: Int,
    val conversionRate: Double,
)

data class PlannerInput(
    val profile: PersonProfile,          // as today; note §7 PII
    val behaviorSummary: BehaviorSummary,
    val recentEvents: List<BehaviorEvent>, // capped at 20
    val products: List<Product>,         // as today
    val campaign: Campaign,
    val segmentPerformance: List<ArchetypePerformance> = emptyList(),
    val nowEpochMillis: Long,            // as today, for determinism
)
```

**`PlannerInput` is internal.** The external journey runtime only ever sends raw domain
facts — it must not know about summaries, pseudonymization or prompts. The public
contract is:

```kotlin
/** Public contract — what the external journey runtime sends. */
@Serializable
data class PlanRequest(
    val profile: PersonProfile,
    val behavior: MeasuredBehavior,      // raw events; the planner summarizes internally
    val products: List<Product>,
    val campaign: Campaign,
    val segmentPerformance: List<ArchetypePerformance> = emptyList(),
    val nowEpochMillis: Long,
)

interface JourneyPlanner {
    suspend fun plan(request: PlanRequest): Journey
}
```

`JourneyRouter` implements this interface: summarize behavior → score propensity → pick
engine → build the internal `PlannerInput` → run the §4 loop. Planner config
(`LlmClient`, `LlmTarget`, propensity threshold) is constructor-level, never per-request.

> **Serialization gap:** `Journey`, the `JourneyNode` subtypes and `JourneyEdge` are
> currently not `@Serializable`, but the planned journey must cross a process boundary
> to the external runtime (JSON over HTTP/queue). Make them serializable with
> kotlinx polymorphic serialization on the sealed `JourneyNode` interface.

### 3.4 Product is abstract — subscriptions are first-class

> **Status (2026-10-10): deferred.** Kees is not sure about this modeling yet — build
> the working planner first, revisit after. The notes below are a proposal, not decided.

"Product" is an abstract term: it covers physical goods, digital goods, services
**and subscriptions** (media, fitness, …). The `ProductType` enum already abstracts
this, but the shape is too thin for subscription journeys. Extend:

```kotlin
enum class BillingPeriod { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

data class SubscriptionDetails(
    val billingPeriod: BillingPeriod,
    val trialDays: Int? = null,
    val pricePerPeriodCents: Long,
)

// on Product:
val subscription: SubscriptionDetails? = null, // set when type == SUBSCRIPTION
```

Type-aware planning: the planner's system prompt must know the product type, because
subscription journeys use different archetypes (trial→paid conversion, renewal
reminders, failed-payment recovery, churn win-back) than one-off purchase journeys.
`ArchetypePerformance` is therefore keyed by (segment, product type), not just segment.

## 4. The planner loop (runtime)

```
1. Build prompt:
     system = node catalog + JSON schema + hard constraints (§3.2, §6)
     user   = serialized PlannerInput (compact, no raw dumps beyond §3.1)
2. LLM proposes JSON { nodes[], edges[] }            (temperature 0)
3. Parse JSON → build via JourneyBuilder → Journey
4. problems = journey.validate() + copy checks (§6)
5. If problems non-empty and attempts < 3:
     → feed problems back verbatim ("Your plan failed validation: ... Fix and resend full JSON.")
     → goto 2
6. If still invalid after 3 attempts:
     → fall back to rule-based LlmJourneyEngine, log the failure with inputs hash
7. Return the valid Journey
```

The retry-with-errors step is the loop Kees asked about: **yes, we do the loop** —
propose → validate → repair, bounded at 3 attempts, with a deterministic fallback so
planning never hard-fails.

## 5. Prompt contract (system prompt sketch)

- Role: "You are a journey planner for lifecycle marketing."
- Node catalog: the six node types with required fields and semantics.
- Output: **only** a JSON object `{ "nodes": [...], "edges": [...] }`, no prose.
- Hard rules: start node id `start`; every referenced id exists; A/B splits 1–99;
  wait timeouts ≤ `maxWaitHours`; channels ⊆ `allowedChannels`; discounts ≤
  `maxDiscountPercent`; marketing copy ≤ 140 chars, no hashtags/emojis, in `language`.
- Guidance: prefer fewer, higher-impact steps; every journey must end in a purchase
  wait or an explicit end; use `segmentPerformance` when present to pick archetypes
  with the best evidence.

## 6. Output contract & code-side checks

JSON schema (type-discriminated on `type`):

```json
{
  "nodes": [
    { "type": "start", "id": "start" },
    { "type": "decision", "id": "seg", "question": "...", "trueNodeId": "a", "falseNodeId": "b" },
    { "type": "marketing", "id": "m1", "channel": "EMAIL", "content": "...", "productIds": ["p1"], "nextNodeId": "w1" },
    { "type": "waitOnEvent", "id": "w1", "eventType": "purchase_completed", "timeoutHours": 72, "onTimeoutNodeId": "end_lost", "nextNodeId": "end_won" },
    { "type": "abTest", "id": "ab", "name": "...", "variantANodeId": "m1", "variantBNodeId": "m2", "splitPercentA": 50 },
    { "type": "end", "id": "end_won", "outcome": "converted" }
  ],
  "edges": [{ "from": "start", "to": "seg" }]
}
```

Code checks beyond `validate()`: copy length ≤ 140, channel ∈ allowed, discount
mentions in copy ≤ ceiling (regex on `%`/`percent`/`korting`), node count ≤ maxNodes.
Any failure → counted as a validation problem → retry loop (§4.5).

## 7. Guardrails

- **PII:** never send PII to a cloud LLM. The planner takes `LlmTarget` (`CLOUD | LOCAL`):
  on `CLOUD`, pseudonymize by default — the name becomes "the customer" and PII-bearing
  attributes are dropped before prompt building. On `LOCAL`, full profile data may be used.
- **Cost/latency:** the router (§11.1) sends high-propensity customers to the LLM
  planner for real-time journey building; everyone else gets the rule-based engine.
  Revisit the threshold once costs are measured.
- **Determinism:** temperature 0, fixed `nowEpochMillis` in tests, golden tests (§8).

## 8. Testing

- `DummyLlmClient` drives the loop: test invalid→valid retry (first answer broken,
  second fixed), exhaust-then-fallback, and copy-constraint violations.
- Golden tests: 2–3 recorded real LLM responses checked into `commonTest/resources`;
  assert the parsed journey validates and matches expected shape.
- Property-ish test: every `PlannerInput` in a fixture set either yields a valid
  journey or the fallback — never an exception, never an invalid journey.

## 9. Learning loop (v2, feed the prediction)

1. Log outcomes per planned journey: `journeyId, archetype, segment, nodePath, converted`.
2. Aggregate nightly/weekly into `ArchetypePerformance` per segment.
3. Feed back as `PlannerInput.segmentPerformance`.
4. The planner starts preferring archetypes with evidence — this is the point where
   it genuinely *predicts* instead of *generates*.

Needs a store for outcomes — out of scope for v1, but the `PlannerInput` slot is
shaped for it now so nothing has to be re-cut later.

## 10. Implementation plan

**New files (commonMain):**
- `PlannerInput.kt` — `PlannerInput`, `Campaign`, `CampaignConstraints`,
  `ArchetypePerformance`, `LlmTarget`
- `BehaviorSummary.kt` — `BehaviorSummary`, `Trend`, `summarize(MeasuredBehavior, now)`,
  `propensityScore(BehaviorSummary): Double`
- `JourneyOutcome.kt` — `JourneyOutcome`, the executing service's return type (§11.2)
- `LlmJourneyPlanner.kt` — prompt building, JSON parsing, retry loop, fallback wiring;
  applies pseudonymization when `target == CLOUD`
- `JourneyRouter.kt` — implements `JourneyPlanner` (takes `PlanRequest`); picks planner
  vs rule-based engine by propensity threshold (§11.1)

**Changed:**
- `LlmJourneyEngine` stays untouched as the fallback.
- `PersonProfile` gains `language: String?` (per-profile copy language, §11.4).
- `Product` gains `subscription: SubscriptionDetails?` (+ `BillingPeriod` enum) for
  first-class subscriptions (§3.4); `ArchetypePerformance` keyed by (segment, product type).
- `Journey`, `JourneyNode` subtypes, `JourneyEdge`: add `@Serializable` (polymorphic) —
  the planned journey crosses a process boundary to the external runtime.

**Tests (commonTest):** planner loop tests + golden tests (§8).

## 11. Decisions (Kees, 2026-10-10)

1. **Router:** customers with a high propensity to buy get the LLM planner with
   real-time journey building; everyone else gets the rule-based engine (no
   real-time journey building needed for them). Propensity is scored **in code**
   from `BehaviorSummary` (recency, frequency, spend) as a 0..1 score; the planner
   runs when score ≥ threshold (default 0.7, configurable).
2. **Outcome store:** the journey outcome is the return value of the executing service.
   Define `JourneyOutcome(journeyId, archetype, segment, nodePath, converted, atEpochMillis)`
   as that return type; an append store collects the returns and the §9 aggregation
   reads from it. No separate analytics pipeline.
3. **PII:** the PII policy decides cloud vs local LLM. New `LlmTarget` = `CLOUD | LOCAL`
   on the planner config. On `CLOUD`, pseudonymize by default (name → "the customer",
   PII-bearing attributes dropped before prompt building) — PII never goes to a cloud
   provider. On `LOCAL`, full profile data is fine.
4. **Copy language:** per-profile, since many customers are English-speaking. Add
   `language: String?` to `PersonProfile`; effective language =
   `profile.language ?: campaign.constraints.language` (default `"nl"`).
