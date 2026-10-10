# Feature-roadmap — uit het AI-decisioning-onderzoek (2026-10-10)

> Werkdocument op basis van het marktonderzoek (20 vendors) en de CEP-verdieping (12 vendors).
> Niet geprioriteerd op "leuk", maar op: **wat versterkt onze differentiatoren** — de
> propose-validate-retry loop en de latency-tiered advice API — en **wat verkoopt governance**,
> omdat elke vendor zijn AI-lancering paart aan validatie, consent en human sign-off.

Stand van de code (2026-10-10): propose→validate→retry loop voor de LLM-planner (commit `6583e20`),
latency tiers REALTIME/INTERACTIVE/BACKGROUND in `TieredAdviceService` (commit `b8caa7d`),
60 FlowNodeTypes als primaire planning-types (commit `066550f`), per-profile language en
cloud/local PII-pseudonimisering.

---

## Prioriteit 1 — Human-in-the-loop review mode met audit trail

**Patroon uit de markt:** Braze "Agentic Standards" — een expliciete approve-stap vóór livegang.
Overal hetzelfde patroon: draft → menselijke review → live. Niemand doet dit automatisch goed;
wij hebben als enige een automatische validate-retry loop. Een review-mode erbovenop maakt
dat verschil zichtbaar en verkoopbaar.

**Wat het wordt:** planner genereert → automatische validatie draait → marketer ziet het
voorstel met de validatie-resultaten (wat is gefixt in retry, wat bleef flagged) → approve/reject
met reden → alles gelogd in een audit trail (wie, wat, wanneer, op basis van welke
validatie-uitkomst).

**Waarom eerst:** kleinste afstand tot bestaande code (bouwt direct op de validate-retry loop),
grootste governance-waarde, en het is letterlijk wat elke enterprise-inkoper als eerste vraagt.

## Prioriteit 2 — Journey simulatie / dry-run tegen profielen

**Patroon uit de markt:** Adobe — "wat zou deze journey doen voor 1.000 klanten" vóór livegang.

**Wat het wordt:** een simulatie-modus die een gegenereerde journey droog draait tegen een set
(PersonProfile, BehaviorSummary)-paren en rapporteert: verwachte pad-verdeling, waar profielen
afhaken, welke nodes nooit bereikt worden, geschatte kosten per tier. Past direct op de
bestaande validator: simulatie = validatie × N profielen + een rapport.

**Waarom tweede:** paart natuurlijk met prioriteit 1 (review & simulate als governance-bundel),
en het is het sterkste demo-materiaal: een prospect ziet zijn eigen profielen door een
AI-gegenereerde journey lopen vóór er iets live gaat.

## Prioriteit 3 — Token/kosten-budget per latency tier

**Patroon uit de markt:** Pega — "no per-token pricing" als verkoopargument; de angst voor
onverwachte LLM-kosten bij realtime decisioning is reëel.

**Wat het wordt:** per tier (REALTIME/INTERACTIVE/BACKGROUND) een configureerbaar budget:
max tokens of max kosten per beslissing/periode, met degrade-gedrag als het budget op is
(terugvallen op cache/rules i.p.v. falen). Bouwt direct op `TieredAdviceService`.

**Waarom derde:** klein, operationeel, en het haalt het belangrijkste commerciële bezwaar weg
tegen realtime LLM-advies: "wat als het onverwacht duur wordt".

## Prioriteit 4 — Timing en frequentie als beslisdimensies in de advice API

**Patroon uit de markt:** Braze experimenteert over variant × timing × frequentie als één
beslissing; Iterable's Send Time / Frequency Decisioning.

**Wat het wordt:** `advise()` geeft nu de volgende node terug; uitbreiden naar
(node, timing, frequency-cap) — de API adviseert ook *wanneer* en *hoe vaak*.
Contract-wijziging (spec §12 HTTP contract), dus versieerbaar uitrollen.

**Waarom vierde:** raakt het publieke API-contract; eerst de governance-bundel (1+2) en de
kosten-controle (3) neerzetten, dan pas het contract uitbreiden.

## Prioriteit 5 — Conversationele planner-iteratie

**Patroon uit de markt:** Pega Blueprint, Bloomreach Loomi — "maak de wait korter",
"voeg een WhatsApp-variant toe" als dialoog in plaats van one-shot genereren.

**Wat het wordt:** de planner als sessie: een gegenereerde journey kan met natuurlijke-taal
instructies worden bijgesteld, waarbij elke iteratie opnieuw door de validate-retry loop gaat.
Vereist dialoog-state en intent-mapping bovenop de huidige one-shot planner.

**Waarom laatste:** grootste UX-lift en meeste nieuwe state; pas zinvol als de
governance-laag (1+2) eronder zit — anders itereer je blind op ongevalideerde journeys.

---

## Bewust niet op de lijst

- **Nog een executor bouwen:** de fit is complement (advice API) / partiële concurrent (planner);
  niet concurreren als wéér een marketing cloud, maar als open decisioning-laag die vóór elke
  executor kan hangen.
- **Per-contact pricing:** het open alternatief is data-in-eigen-VPC zonder per-contact pricing;
  geen usage-metering als feature inbouwen.
