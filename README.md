# kmp-journey-engine

Kotlin Multiplatform-module (server-side) die uit een **profiel**, **gemeten gedrag**,
**producten** en een **prompt** een marketing-journey opbouwt.

## Structuur

```
journey-engine/src/
  commonMain/   shared code (platform-onafhankelijk)
    Models.kt        PersonProfile, Product(+ProductType), BehaviorEvent, MeasuredBehavior
    Journey.kt       node-types: Start, End, WaitOnEvent, Decision, ABTest, Marketing
                     + Journey.validate()
    JourneyBuilder.kt  kleine DSL om journeys te assembleren
    LlmClient.kt     interface + DummyLlmClient (voor tests)
    Engine.kt        LlmJourneyEngine: bouwt de journey, LLM genereert de copy
    Time.kt          expect fun nowMillis()
  jvmMain/          JVM-specifiek (server)
    TimeJvm.kt       actual fun nowMillis()
    OpenAiLlmClient.kt  echte OpenAI-client via JDK HttpClient (alleen JVM)
  commonTest/
    EngineTest.kt    tests draaien op de dummy, geen API-key nodig
```

Targets: nu alleen `jvm()` (server-side). Later eenvoudig uit te breiden met
`android()`, `iosX64()` o.i.d. zonder `commonMain` aan te raken.

## Bouwen & testen

```bash
./gradlew :journey-engine:build   # compileert + draait tests
./gradlew :journey-engine:jvmTest # alleen tests
```

Vereist: JDK 17+ (getest met 21). Gradle-wrapper wordt bij eerste build aangemaakt;
of gebruik een lokale Gradle 8.10+.

## Gebruik

```kotlin
val engine: JourneyEngine = LlmJourneyEngine(
    OpenAiLlmClient(apiKey = System.getenv("OPENAI_API_KEY"))
    // of voor tests: DummyLlmClient(mapOf("discount" to "SALE!"))
)

val journey = engine.plan(
    JourneyInput(
        profile = PersonProfile(id = "u1", name = "Jan", segments = setOf("sports")),
        behavior = MeasuredBehavior("u1", listOf(BehaviorEvent("app_open", System.currentTimeMillis()))),
        products = listOf(Product("p1", "Football", ProductType.PHYSICAL, tags = setOf("sports"))),
        prompt = "win back dormant customers with a discount ladder",
        nowEpochMillis = System.currentTimeMillis(),
    )
)

check(journey.validate().isEmpty())
```

Elke geplande journey heeft dezelfde vorm:
`start → decision(engaged?) → marketing → wait(purchase) → end`, met voor
niet-geëngageerde profielen een `abTest` (10% vs 20% korting) ertussen.
De marketingteksten komen uit de LLM; alles daaromheen is deterministisch
en valideerbaar via `Journey.validate()`.
