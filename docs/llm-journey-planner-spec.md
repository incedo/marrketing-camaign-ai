# Spec: LLM Journey Planner

**Status:** draft · **Date:** 2026-10-10 · **Author:** Mr I (with Kees)

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

- **PII:** `profile.name` is sent to the LLM for copy personalization. Add a
  `pseudonymize: Boolean` flag on `PlannerInput`; when true, replace the name with
  "the customer" (copy still works, just less personal).
- **Cost/latency:** LLM planning is opt-in per customer, not the default path.
  Suggested router (v1): rule-based engine for everyone; `LlmJourneyPlanner` for
  high-value or ambiguous customers (e.g. `totalSpendCents90d` above threshold OR
  conflicting signals). Revisit once costs are measured.
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
  `ArchetypePerformance`
- `BehaviorSummary.kt` — `BehaviorSummary`, `Trend`, `summarize(MeasuredBehavior, now)`
- `LlmJourneyPlanner.kt` — prompt building, JSON parsing, retry loop, fallback wiring

**Changed:**
- `LlmJourneyEngine` stays untouched as the fallback.

**Tests (commonTest):** planner loop tests + golden tests (§8).

## 11. Open questions

1. Router rules for "who gets LLM planning" — spend threshold? manual flag?
2. Where do journey outcomes get logged (needs a store for §9)?
3. PII policy: is sending names to the LLM provider acceptable, or pseudonymize by default?
4. Copy language default: `nl` per constraints, or per-profile?
