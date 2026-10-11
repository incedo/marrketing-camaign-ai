# Example journeys

Three realistic journeys built from the 60 `FlowNodeType`s, exactly as the
runtime would receive them (serialized `FlowJourney` JSON):

- **winback.json** — win-back for dormant customers: consent + frequency guard,
  LLM-written subject line (`PERSONALIZE_CONTENT`), 72h wait, opened-check,
  then `ML_DECISION` arbitrates between exit and a channel-switched WhatsApp nudge.
- **welcome.json** — new-signup onboarding: newsletter consent, quiet hours,
  LLM welcome copy, wait for first app open, branch on onboarding completion.
- **abandoned-cart.json** — cart abandonment: best send time, frequency guard,
  coupon, LLM cart summary, WhatsApp reminder, 24h wait, purchase check.

The builders live in
`journey-engine/src/commonMain/kotlin/com/intelliworks/journey/SampleJourneys.kt`;
`SampleJourneysTest` asserts every sample validates clean and round-trips
through JSON. Regenerate with:

```bash
./gradlew :journey-engine:jvmTest   # or kotlinc directly, see README
```

The Docker image (`Dockerfile`, built by `.github/workflows/docker.yml`)
compiles these samples to a Kotlin/Native executable and prints the same JSON:

```bash
docker run ghcr.io/incedo/marrketing-camaign-ai/journey-engine:latest
```
