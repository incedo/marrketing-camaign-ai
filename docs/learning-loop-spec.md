# Learning loop spec — from outcomes to prediction

Status: spec (not implemented). Builds on `llm-journey-planner-spec.md` §9 and §11.
Date: 2026-10-10.

## 1. Goal

Turn the planner from *generating* journeys into *predicting* which journey works:
every executed journey reports an outcome, outcomes aggregate into per-segment
performance evidence, and that evidence steers both the router (LLM vs rules) and
the planner prompt (which archetypes to prefer). Cold start must stay safe:
with no evidence, behavior is exactly today's (static threshold + rule fallback).

Out of scope: model training / fine-tuning. This spec is prompting + statistics only.

## 2. Outcome logging

The executing service already returns `JourneyOutcome` (§11.2). The module adds an
append-only store interface; the runtime hosts the implementation.

```kotlin
data class JourneyOutcome(
    val journeyId: String,
    val personRef: String,      // pseudonymized id, never raw PII
    val archetype: String,      // planner archetype label, e.g. "winback-email-3step"
    val segment: String,
    val productType: String,
    val nodePath: List<String>, // node ids actually visited, in order
    val channel: String,        // channel of the converting/final touch
    val converted: Boolean,
    val revenue: Double?,       // null when unknown; used for value arbitration (§5)
    val atEpochMillis: Long,
)

interface OutcomeStore {
    suspend fun append(outcome: JourneyOutcome)
    suspend fun read(sinceEpochMillis: Long): List<JourneyOutcome>
}
```

PII rule: `personRef` is the runtime's pseudonym. The module never receives names,
emails, or other identifiers in outcomes. Aggregation in §3 must not be reversible
to individuals (minimum cohort sizes, §3.3).

## 3. Aggregation → ArchetypePerformance

A scheduled job (nightly/weekly, hosted by the runtime) folds outcomes into evidence:

```kotlin
data class ArchetypePerformance(
    val segment: String,
    val productType: String,
    val channel: String,        // NEW vs §9: advice also decides channel
    val archetype: String,
    val journeys: Long,         // n
    val conversions: Long,
    val revenueTotal: Double,
    val conversionRate: Double, // conversions / journeys
    val revenuePerJourney: Double,
    val windowDays: Int,        // aggregation window, e.g. 90
    val updatedEpochMillis: Long,
)
```

Keyed by (segment, productType, channel, archetype). Fed back as
`PlanRequest.segmentPerformance` (existing slot).

Rules:
- **Minimum samples:** rows with `journeys < 30` are flagged `insufficientEvidence`
  and excluded from exploitation (§4) — they may still inform the prompt as
  "early signal", clearly labeled.
- **Recency weighting:** exponential decay (half-life 45 days default, configurable)
  so old evidence fades.
- **No PII:** aggregation output contains counts and rates only.

## 4. Bandit arbitrage (replaces the static 0.7 threshold)

`JourneyRouter` currently routes by `propensityScore >= 0.7`. With evidence, routing
becomes a contextual bandit over (planner archetype × channel):

- **Exploit:** pick the archetype+channel with the best `revenuePerJourney`
  (fallback: `conversionRate`) for the person's (segment, productType).
- **Explore:** with probability `epsilon` (default 0.1, configurable, decaying as
  evidence grows), pick a random eligible archetype to keep learning.
- **Cold start:** no sufficient evidence → today's behavior (propensity threshold,
  default 0.7). The threshold itself becomes a config, not a constant.

```kotlin
data class BanditConfig(
    val epsilon: Double = 0.1,
    val epsilonDecay: Double = 0.999, // per aggregation run
    val minSamples: Long = 30,
    val recencyHalfLifeDays: Int = 45,
)

interface ArbitrationPolicy {
    fun select(
        request: PlanRequest,
        candidates: List<ArchetypePerformance>,
        propensity: Double,
        config: BanditConfig,
        random: Random = Random.Default,
    ): Selection // LLM_PLANNER(archetype) | RULES
}
```

Deterministic when a seed is supplied (tests). The policy is pure logic in
`commonMain`; randomness is injected.

## 5. Pega-style arbitration: value and levers

Propensity alone decides *likelihood*; arbitration adds *worth* and *business rules*:

```
score = propensity × value × leverMultiplier
```

- **propensity** (0..1): existing `propensityScore(BehaviorSummary)`, optionally
  nudged by archetype evidence (`conversionRate` of the archetype for the segment).
- **value** (≥0): expected margin per conversion. Source: `Product.marginEstimate`
  (new optional field; null → 1.0 neutral) × archetype `revenuePerJourney` when
  evidence exists. Never invents money: nulls stay neutral.
- **leverMultiplier** (0..1): product of `CampaignConstraints` business levers —
  discount ceiling, channel cost weights, quiet hours, frequency caps. A lever of
  0 vetoes (e.g. consent missing → 0, journey not planned).

The planner prompt gains a compact "arbitration context" block: top-3 archetypes by
score with their evidence rows, so the LLM prefers what works instead of guessing.

## 6. Governance enforced in validation (not just in the prompt)

The 60 `FlowNodeType`s already include `CONSENT_CHECK`, `SUPPRESSION_CHECK`,
`FREQUENCY_CAP`, `QUIET_HOURS`, `LEGAL_EXCLUSION`. The validator is extended:

1. Any journey containing a send node (`SEND_EMAIL`, `SEND_PUSH`, `SEND_SMS`,
   `SEND_WHATSAPP`) MUST have a `CONSENT_CHECK` on every path reaching the send.
   Missing → validation error → retry loop (§8 of main spec).
2. `FREQUENCY_CAP` required when `campaign.constraints.maxTouchpointsPerWeek` is set.
3. "Do nothing" is a valid plan: the planner may return an empty journey
   (`nodes = [START_TRIGGER → END]`) with reason `suppressed_by_governance`
   instead of forcing a send. The fallback path must also respect this.

This turns governance from prompt instructions into structural guarantees —
the sales argument under EU AI Act pressure.

## 7. Advice-service tie-in

`TieredAdviceService.advise()` gains an optional `evidence: List<ArchetypePerformance>`
input. `ML_DECISION` / `NEXT_BEST_ACTION` / `NEXT_BEST_CHANNEL` nodes then rank
candidates by the §5 score instead of static rules. `servedBy` reporting is
extended with `EVIDENCE` vs `RULES` so callers can see what decided.

## 8. Rollout phases

1. **Log:** `OutcomeStore` interface + runtime append implementation; no behavior change.
2. **Aggregate:** nightly job → `ArchetypePerformance`; planner prompt shows evidence
   as labeled context; router unchanged.
3. **Bandit:** `ArbitrationPolicy` replaces the static threshold; epsilon-greedy live.
4. **Arbitrate:** value + levers in the score; governance validation enforced (§6).

Each phase ships independently and is safe to run without the next.

## 9. Acceptance criteria

- Cold start (empty evidence): behavior identical to today — same journeys, same
  routing. Golden tests pin this.
- With fabricated evidence: bandit exploits the best archetype ≥ (1-epsilon) of
  seeded runs; exploration covers all arms over many runs (statistical test).
- Validator rejects a journey that sends without `CONSENT_CHECK`; accepts the
  same journey with the check on the path.
- No PII in `OutcomeStore` contents (test with a PII-sniffing assertion on
  serialized outcomes).
- Aggregation is idempotent: re-running over the same window yields identical rows.
