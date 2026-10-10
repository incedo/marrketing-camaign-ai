# Event-oriented implementation plan — LLM action node & goal-driven adaptation

Status: build plan (not implemented). Implements `llm-action-node-spec.md`
step by step, and `learning-loop-spec.md` phases 1–2 where they overlap.
Date: 2026-10-10.

## 1. Principles

- **Events, not calls.** Planner, advice service, and learning loop never call
  each other directly. They publish and subscribe to events. The runtime is just
  another subscriber/publisher.
- **Module = pure logic + ports.** Everything in `commonMain` is deterministic
  and testable without I/O: event data classes, handlers as pure functions,
  port interfaces (`EventBus`, `OutcomeStore`). The runtime supplies adapters.
- **Additive evolution.** Events are versioned data; fields are only added,
  never renamed or removed. Old subscribers keep working.
- **Each step ships alone.** Every step below is independently testable and
  deployable; later steps never re-cut earlier ones.

## 2. Event vocabulary

| Event | Producer | Consumers | Payload (sketch) |
|---|---|---|---|
| `JourneyPlanned` | planner | runtime, warmup | `journey: FlowJourney, goal: JourneyGoal?, planReasoning` |
| `NodeEntered` | runtime | advice, audit | `runId, nodeId, attributes: Map<String,String>` |
| `AdviceRequested` | runtime | advice | `runId, nodeId, runEvents (≤20), goal?, tier` |
| `AdviceResolved` | advice | runtime | `runId, nodeId, recommendation (nodeId | attributeValue), reasoning, servedBy` |
| `AttributeWritten` | advice | runtime | `runId, attribute, value` (LLM action output) |
| `RunEventOccurred` | runtime | advice, learning | `runId, nodeId, event (sent/opened/clicked/converted/…), atEpochMillis` |
| `GoalAchieved` | advice | runtime, learning | `runId, goalId, metric, value` |
| `JourneyEnded` | runtime | learning | `runId, outcome: JourneyOutcome` |

All events carry `eventId` and `atEpochMillis`. Payloads are `@Serializable`
data classes in `commonMain` — the wire format is the contract.

```kotlin
interface EventBus {
    suspend fun publish(event: JourneyEvent)
    fun <T : JourneyEvent> subscribe(type: KClass<T>, handler: suspend (T) -> Unit): Subscription
}
```

`JourneyEvent` is a sealed interface; each event above is a subtype.
The runtime's bus implementation (in-memory, Kafka, …) is its own choice.

## 3. Build steps

### Step 1 — Event vocabulary + bus port
- **Build (module):** `events/JourneyEvents.kt` — all 8 event data classes,
  sealed `JourneyEvent`; `EventBus` port interface; an in-memory `EventBus`
  test double in `commonTest`.
- **Runtime contract:** none yet (no subscribers).
- **Tests:** serialization round-trip for every event; subscribe/publish ordering
  on the test double.
- **Done:** `JourneyPlanned` can be published and received in a unit test.

### Step 2 — Planner publishes, validator stays pure
- **Build:** `LlmFlowJourneyPlanner` publishes `JourneyPlanned` after a successful
  plan (inject `EventBus?`; null = no publishing, keeps existing tests green).
  Node param contracts from `llm-action-node-spec.md` §3 land in the spec
  registry (`PERSONALIZE_CONTENT`, `ML_DECISION` params + attribute liveness check).
- **Runtime contract:** may subscribe to `JourneyPlanned` to persist/schedule.
- **Tests:** planner test asserts the published event matches the returned journey;
  validator tests for `{{undeclared}}`, missing `fallbackValue`, attribute liveness.
- **Done:** planning a goal-driven journey emits a valid, validated event.

### Step 3 — Advice over events
- **Build:** an `AdviceSubscriber` that listens for `AdviceRequested` and publishes
  `AdviceResolved`. The existing `TieredAdviceService.advise()` becomes the
  handler body — no logic rewrite, just an event wrapper. `NodeEntered` updates
  the warmup cache keys.
- **Runtime contract:** at node execution, publish `AdviceRequested` (with tier)
  and await the matching `AdviceResolved` (correlation by `runId`+`nodeId`);
  on timeout use local fallback — the runtime never blocks on us.
- **Tests:** end-to-end through the in-memory bus: request → tier-appropriate
  resolution → response; timeout path returns fallback.
- **Done:** advice works without any direct call into the module.

### Step 4 — LLM action node (PERSONALIZE_CONTENT)
- **Build:** advice handler for `PERSONALIZE_CONTENT`: builds the prompt from
  `promptTemplate` + attributes (PII policy enforced), calls the LLM per tier
  (§5 of feature spec), publishes `AttributeWritten` + `AdviceResolved`.
- **Runtime contract:** applies `AttributeWritten` to the run's attribute map;
  subsequent `{{attribute}}` references resolve.
- **Tests:** prompt rendering with declared attributes; PII-sniff on CLOUD;
  fallback path when the LLM times out on a REALTIME tier.
- **Done:** a journey can generate a subject line mid-run and branch on it.

### Step 5 — Goal arbiter (ML_DECISION) + run feedback
- **Build:** advice handler for `ML_DECISION`: scores branches from `runEvents`
  + `JourneyGoal` (feature spec §4–5); publishes `GoalAchieved` on early exit.
  Runtime publishes `RunEventOccurred` per touch.
- **Runtime contract:** stream `RunEventOccurred`; honor `GoalAchieved` by
  routing to `END`.
- **Tests:** seeded: 2 unopened touches → channel switch; goal met → `END`
  with `goal_achieved`; reasoning names goal + signals.
- **Done:** within-run adaptation works event by event.

### Step 6 — Warmup as subscriber
- **Build:** `warmup()` becomes a `JourneyPlanned` subscriber: precomputes
  likely `PERSONALIZE_CONTENT` outputs and `ML_DECISION` resolutions per
  segment into the cache the REALTIME tier reads.
- **Runtime contract:** none new.
- **Tests:** publish `JourneyPlanned` → cache populated → REALTIME `AdviceRequested`
  served from cache (`servedBy=CACHE`).
- **Done:** LLM quality at cache speed, still fully decoupled.

### Step 7 — Learning loop plugs in (no changes to steps 1–6)
- **Build:** `aggregateOutcomes()` (learning-loop-spec §3) as a pure function;
  a subscriber that turns `JourneyEnded` into `OutcomeStore.append`
  and feeds rows back as `PlanRequest.segmentPerformance`.
- **Runtime contract:** publish `JourneyEnded`; schedule the aggregation call.
- **Tests:** idempotent aggregation; cold-start parity (golden tests unchanged).
- **Done:** the across-run loop learns without touching the within-run path.

## 4. Coupling rules (enforced in review)

1. `commonMain` never imports runtime code; the runtime never imports module
   internals — only events, ports, and data contracts.
2. No synchronous request/response between module components; if two parts need
   each other, that's a new event, not a new parameter.
3. Timeouts and fallbacks live on the consumer side (runtime), never assumed
   by the producer.
4. Every new event gets: producer, consumers, payload sketch (§2 table), and a
   serialization test — no exceptions.

## 5. Traceability

- `llm-action-node-spec.md` §§3–6 → steps 2–5
- `llm-action-node-spec.md` §5 tiers/warmup → steps 3, 6
- `learning-loop-spec.md` §§2–4 → step 7
- Acceptance criteria of both specs become the per-step **Done** gates above.
