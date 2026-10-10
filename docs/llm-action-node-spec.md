# LLM action node & goal-driven adaptation — feature spec

Status: spec (not implemented). Companion to `learning-loop-spec.md`
(which learns *across* runs; this spec adapts *within* a run).
Date: 2026-10-10.

## 1. Goal

Journeys that don't just execute a pre-drawn graph, but adapt mid-run:

1. **LLM action node** — a step that calls an LLM during execution; its output
   becomes a named journey attribute (Customer.io "LLM Actions" pattern).
   Example: generate a subject line from the customer's last browse event, then
   branch on its sentiment score.
2. **Goal-driven adaptation** — the journey carries a goal (metric + target);
   decision nodes read engine feedback from *this run so far* and steer toward
   the goal (e.g. no open after 2 touches → switch channel).

This is what finally joins the planner and the advice API: the planner places
adaptive nodes, the advice API resolves them at runtime.

## 2. No new node types

The 60 runtime `FlowNodeType`s stay the vocabulary. The feature defines
*semantics* for two existing types:

| Runtime type | Role in this feature |
|---|---|
| `PERSONALIZE_CONTENT` | LLM action node: runs a prompt, writes `outputAttribute` |
| `ML_DECISION` | Goal arbiter: picks a branch from run feedback + goal progress |

If the runtime's real `FlowModel.kt` turns out to have a dedicated AI type among
the four without behavior yet, these semantics move there — the contract below
is written to be portable.

## 3. Node contract (params)

`PERSONALIZE_CONTENT` as LLM action:

```kotlin
// params
promptTemplate: String   // required; {{attribute}} placeholders only —
                         // no raw PII interpolation on CLOUD (§6)
outputAttribute: String  // required; where the result lands, e.g. "subject_line"
modelTier: String        // FAST | DEEP, default FAST
timeoutMs: Int           // required; must fit the journey's latency tier (§5)
fallbackValue: String    // required; used on timeout/failure — the journey
                         // never stalls on an LLM call
```

`ML_DECISION` as goal arbiter:

```kotlin
// params
goalRef: String          // references JourneyGoal.id (§4)
inputs: List<String>     // run-feedback signals to consider, e.g.
                         // ["opens_last_2", "clicks", "days_since_entry"]
```

Validation (extends the spec registry):
- `promptTemplate` present and non-blank; every `{{x}}` must reference a declared
  attribute (journey input, earlier node output, or profile field allowed by PII policy).
- `outputAttribute` must be written before any node reads it (attribute liveness check).
- `timeoutMs` ≤ tier budget (REALTIME ≤ 300ms → effectively forces warmup/FAST;
  INTERACTIVE ≤ 1500ms; BACKGROUND unbounded).
- `fallbackValue` required whenever the node sits on a REALTIME path.

## 4. Goal-driven journeys

A journey may carry one goal:

```kotlin
data class JourneyGoal(
    val id: String,
    val metric: String,       // e.g. "purchase", "reactivation", "signup"
    val target: Double,       // e.g. 0.08 conversion rate
    val windowDays: Int,      // run-level window, e.g. 14
)
```

Planner behavior for goal-driven campaigns (`campaign.goal != null`):
- Insert `ML_DECISION` nodes at natural adaptation points (after every 2nd touch,
  before channel switches) — not everywhere; each one costs latency.
- Insert `PERSONALIZE_CONTENT` where evidence (§9/learning-loop-spec) shows
  content variance matters (subject lines, hero copy).
- The prompt's arbitration context (§5 of learning-loop-spec) now also lists
  which archetypes historically hit similar goals.

"Do nothing" stays valid: if the goal is already met mid-run, `ML_DECISION`
may route straight to `END` with reason `goal_achieved`.

## 5. Runtime resolution via the advice API

At execution, both node types resolve through the advice service:

- `POST /v1/advice` gains optional `runEvents: List<RunEvent>` (capped at 20,
  newest first) and `goal: JourneyGoal?`. A `RunEvent` is a lightweight
  per-run fact: `nodeId`, `event` (`sent`/`opened`/`clicked`/`converted`/…),
  `atEpochMillis`.
- Tier mapping:
  - **REALTIME** — never call an LLM inline. Serve from warmup cache; on miss,
    use `fallbackValue` (PERSONALIZE_CONTENT) or static rules (ML_DECISION).
  - **INTERACTIVE** — one LLM attempt, hard 1500ms timeout, else fallback.
  - **BACKGROUND** — full LLM call, then cache the result for later runs.
- `warmup()` precomputes likely LLM-action outputs per segment so website
  journeys get LLM quality at cache speed.
- Responses already carry `reasoning`; for these nodes it must name the goal
  and the run signals considered (auditability).

## 6. PII policy (same as planner)

- `LlmTarget.CLOUD`: `promptTemplate` may only interpolate pseudonymized or
  non-PII attributes; the advice request carries no names, emails, or raw
  identifiers. Violation → validation error at plan time, rejection at serve time.
- `LlmTarget.LOCAL`: full attributes allowed.

## 7. Boundary: module vs runtime

| Ours (this module) | Runtime's (external) |
|---|---|
| Node param contracts + validation rules (§3) | Executes nodes, holds journey attributes |
| Goal-driven planning (node placement) | Streams `runEvents` per journey run |
| Advice API: scoring with `runEvents` + `goal` | Calls the API at node execution |
| `warmup()` precompute logic | Cache storage, LLM credentials |

We never see raw customer data beyond what the existing PII policy allows;
run events arrive pseudonymized like outcomes.

## 8. Relation to the learning loop

Two different feedback speeds, one architecture:

- **Learning loop** (learning-loop-spec.md): *across* runs, nightly/weekly —
  which archetypes work per segment.
- **This spec**: *within* a run, milliseconds-to-seconds — how this run adapts
  toward its goal.

The within-run signal (`runEvents`) is the raw material the across-run loop
later aggregates into `JourneyOutcome`s. No duplication: outcomes are the
run's final summary; run events are its live pulse.

## 9. Rollout phases

1. **Contract:** node param specs + validation rules (§3); planner places the
   nodes with `fallbackValue`s. Runtime treats them as today's types.
2. **Advice resolution:** `runEvents` + `goal` on the advice API; tier mapping
   per §5; `warmup()` covers LLM actions.
3. **Goal arbitration:** `ML_DECISION` scores branches by goal progress;
   `goal_achieved` early-exit.

## 10. Acceptance criteria

- A journey with `PERSONALIZE_CONTENT` on a REALTIME path and no reachable
  warmup entry still completes — via `fallbackValue`, never an exception.
- Validator rejects `{{undeclared}}` placeholders and missing `fallbackValue`
  on REALTIME paths.
- With recorded `runEvents` showing 2 unopened touches, `ML_DECISION` switches
  channel (seeded deterministic test).
- Goal already met → journey routes to `END` with `goal_achieved`, no further sends.
- Advice responses for these nodes name goal + signals in `reasoning`.
- PII-sniff test on advice requests under `CLOUD`: no raw identifiers.
