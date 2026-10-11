# marrketing-camaign-ai · journey-engine

AI-native marketing-journey decisioning — **built AI-first from the ground up**, not bolted onto a legacy canvas.

This module **plans journeys and gives live advice**. It never executes them: the runtime
owns execution, the module owns the thinking. Planning, advice and learning talk to each
other — and to the runtime — only through a small event vocabulary. Loose coupling by design.

```
┌──────────────┐   JourneyPlanned    ┌──────────────┐   AdviceRequested   ┌──────────────┐
│   PLANNER    │ ──────────────────▶ │   RUNTIME    │ ──────────────────▶ │    ADVICE    │
│ LlmFlow-     │   (with maker:      │  (yours)     │   (what now?)       │ FlowAdvice-  │
│ Journey-     │    AI/RULES/HUMAN/  │              │ ◀────────────────── │ Service      │
│ Planner      │    IMPORT)          │              │   AdviceResolved    │              │
└──────────────┘                     └──────┬───────┘                     └──────────────┘
       ▲ propose→validate→retry             │ RunEventOccurred / JourneyEnded
       │ rule-based fallback                ▼
┌──────────────┐                     ┌──────────────┐
│ LEARNING LOOP│ ◀── outcomes ────── │              │
│ aggregate →  │                     └──────────────┘
│ bandit arbitrage
└──────────────┘
```

## What it does

**Plan** — `LlmFlowJourneyPlanner.planFlow(PlanRequest): FlowJourney`
The LLM proposes a journey from a subset of 60 flow node types; a validator checks it
against a per-type spec registry, and the loop retries until the journey is valid —
or falls back to a deterministic rule-based engine. Every planned journey is published
as a `JourneyPlanned` event carrying a required `maker` (AI_PLANNER / RULE_ENGINE /
HUMAN_EDITOR / IMPORT), so the runtime accepts journeys from any maker.

**Advise** — `FlowAdviceService.advise(AdviceRequested): AdviceResolved`
Mid-journey, the runtime asks "what now?" and gets an answer in one of three latency tiers:
- **REALTIME** — cache or rules only, never an inline LLM call
- **INTERACTIVE** — one LLM attempt with a hard 1500 ms timeout, then rules
- **BACKGROUND** — the full LLM call, which warms the cache

`warmup()` lets the LLM think ahead asynchronously, so website visitors get LLM-quality
answers at cache speed.

**Learn** — `LearningLoop` / `LearningSubscriber`
Run outcomes flow back via events; `aggregateOutcomes` (recency decay, minimum samples)
feeds `EpsilonGreedyArbitration`, which replaces the static propensity threshold with
always-on learning. Cold start behaves like the old 0.7 rule — no regression, only upside.

## The 60 flow node types

One enum, 11 categories: `JOURNEY_CONTROL`, `COMMUNICATE`, `DECIDE`, `AI`, `ACCESS`,
`WAIT_TIMING`, `PERSONALIZE`, `GOVERN`, `INTEGRATE`, `UPDATE_DATA`, `MEASURE`.

Genuinely data-driven in this module:
- **`ML_DECISION`** — goal arbiter: reads run events + journey goal, picks a branch
  (channel-switch after 2 unopened touches, early exit on `goal_achieved`)
- **`PERSONALIZE_CONTENT`** — LLM action node: writes journey attributes
  (`{{attribute}}` placeholders filled from the run, PII-filtered on cloud);
  the validator proves every attribute was written before use

Governance is first-class: `CONSENT_CHECK`, `SUPPRESSION_CHECK`, `FREQUENCY_CAP`,
`QUIET_HOURS`, `BEST_SEND_TIME`, `CONTROL_GROUP` are in the planner's prompt subset.
Decision/condition strings (`IF_ELSE`, `AUDIENCE_CHECK`, …) are validated for required
params but interpreted by the runtime — a shared condition language is on the roadmap.

## The event vocabulary (8)

| Group | Events |
|---|---|
| Planning | `JourneyPlanned` (with maker + goal), `NodeEntered` (runtime-produced) |
| Live advice | `AdviceRequested`, `AdviceResolved`, `AttributeWritten` |
| Learning | `RunEventOccurred`, `GoalAchieved`, `JourneyEnded` |

Events are `kotlinx.serialization`-serializable; the module defines the vocabulary and the
ports (`EventBus`, `OutcomeStore`, `LlmClient`), the runtime supplies the adapters.

## Guardrails

- **PII policy** — cloud LLMs see pseudonymized data by default; local LLMs may see full data
- **Router** — `BehaviorSummary` + propensity score: high propensity → real-time LLM planner,
  others → rule-based engine
- **Copy language** — per profile (`PersonProfile.language`, fallback `nl`)
- **Scope** — this module plans and advises. It does not execute, store runtime state,
  or schedule anything. That's the runtime's job.

## Build & test

```bash
./gradlew :journey-engine:jvmTest   # 56 tests, no API key needed (DummyLlmClient)
```

Requires JDK 17+ (tested with 21). For a real LLM, plug in `OpenAiLlmClient(apiKey = …)`
in `jvmMain` — it talks to OpenAI over the JDK HttpClient.

## Docs

- `docs/llm-journey-planner-spec.md` — planner contract, router, PII policy, HTTP contract (§12)
- `docs/event-oriented-implementation-plan.md` — the 7-step event architecture
- `docs/learning-loop-spec.md`, `docs/llm-action-node-spec.md`
- `docs/ai-decisioning-market-research.md`, `docs/cep-ai-decisioning-research.md` — 20+12 vendors
- `docs/features-roadmap.md` — what's next, prioritized by differentiator strength

`examples/` holds three realistic journeys as serialized JSON (win-back, welcome,
abandoned cart) — the same output the Docker image prints.

## Status

Working code, 56/56 tests green. Planner differentiator (propose→validate→retry) and the
latency-tiered advice API are implemented; no vendor we surveyed validates AI-generated
journeys automatically, and none sells latency-tiered decisioning as an embeddable service.
