# LLM-providerabstractie & simulatie-spec

## 1. Doel

Beantwoorden of de journey-planner met een **echte LLM** goede journeys maakt.
De 58 unit-tests bewijzen de machine (propose→validate→retry, tiers, learning
loop), niet het denken. Simulaties met een echte API meten prompt-kwaliteit:
hoe vaak is het eerste voorstel valide, hoe vaak is een retry nodig, hoe vaak
valt de planner terug op regels.

Startpunt: **Meta API met gratis credits**. Daarna vergelijkbaar met andere
providers.

## 2. Provider-abstractie

De module heeft al een port: `LlmClient` (`commonMain`):

```kotlin
interface LlmClient {
    suspend fun complete(prompt: String, system: String? = null): String
}
```

Niets in planner, advice of learning loop mag ooit een concrete provider
kennen. Uitbreidingen:

### 2.1 Usage-info

```kotlin
data class LlmUsage(val promptTokens: Int, val completionTokens: Int)

interface LlmClient {
    suspend fun complete(prompt: String, system: String? = null): String
    /** Optioneel; default null voor providers zonder usage-data. */
    suspend fun completeWithUsage(prompt: String, system: String? = null): Pair<String, LlmUsage?> =
        complete(prompt, system) to null
}
```

Usage voedt kostenmeting per simulatierun (zie §4) en later het
token/kosten-budget per latency tier uit de roadmap.

### 2.2 Providers

| Naam | Client | Basis-URL / opmerking |
|---|---|---|
| `meta` | `MetaLlmClient` | Meta API (Llama-modellen); **eerste implementatie**, gratis credits |
| `ollama` | `OllamaLlmClient` | Lokaal, `http://localhost:11434`, OpenAI-compatibele `/api/chat` |
| `lmstudio` | `LmStudioLlmClient` | Lokaal, OpenAI-compatibel endpoint |
| `openai` | `OpenAiLlmClient` | Bestaat al (`jvmMain`, JDK HttpClient) |
| `claude` | `AnthropicLlmClient` | Anthropic Messages API |
| `grok` | `GrokLlmClient` | xAI API (OpenAI-compatibel) |
| `openrouter` | `OpenRouterLlmClient` | `:free`-modellen, OpenAI-compatibel — zie `docs/free-llm-providers.md` |
| `groq` | `GroqLlmClient` | Royale gratis tier, OpenAI-compatibel — zie `docs/free-llm-providers.md` |
| `cerebras` | `CerebrasLlmClient` | ~1M tokens/dag gratis — zie `docs/free-llm-providers.md` |
| `gemini` | `GeminiLlmClient` | Google AI Studio, OpenAI-compatibel endpoint — zie `docs/free-llm-providers.md` |
| `mistral` | `MistralLlmClient` | Gratis Experiment-tier — zie `docs/free-llm-providers.md` |
| `nvidia` | `NvidiaLlmClient` | NVIDIA NIM, ~1000 req/dag/model gratis — zie `docs/free-llm-providers.md` |

Eén factory, geen vertakkingen in de engine:

```kotlin
fun llmClientFor(provider: String, apiKey: String?): LlmClient
```

Selectie via configuratie (`LLM_PROVIDER`, `LLM_API_KEY`), nooit via
code-wijziging. Keys komen uit Secure Vault / env — nooit in chat, code of repo.

### 2.3 Blijvende regels

- **PII-beleid ongewijzigd**: cloud-providers (`meta`, `openai`, `claude`,
  `grok`) krijgen gepseudonimiseerde data (bestaat al in de planner);
  lokale providers (`ollama`, `lmstudio`) mogen volledige data zien.
- **Scope-grens**: providers zijn adapters aan de rand; de module blijft
  planning + advies, geen executie.

## 3. Simulatie-harness (dev-tooling, geen runtime)

Doel: prompt-kwaliteit meetbaar maken, per provider vergelijkbaar.

### 3.1 Profielenset

~20 synthetische profielen met spreiding over:
- segment (dormant, new, high-value, churn-risk)
- gedrag (geen opens, veel clicks, alleen WhatsApp, …)
- taal (`nl` / `en`, via `PersonProfile.language`)
- propensity (laag / rond 0.7 / hoog — raakt de router)

Plus 3–4 campagnes (win-back, welcome, abandoned cart — zie `examples/`).

### 3.2 Run

Voor elke combinatie (profiel × campagne × provider):
1. `planFlow(PlanRequest)` met de echte LLM-client.
2. Valideer met `FlowJourney.validate()`; tel attempts tot acceptatie.
3. Log: latency, usage (tokens), fallback geraakt ja/nee.

### 3.3 Metrieken (per provider)

- % journeys **valide bij eerste poging**
- % valide na retry (en gemiddeld aantal attempts)
- % teruggevallen op rule-based
- % met governance-nodes (`CONSENT_CHECK`, `FREQUENCY_CAP`, …)
- p50/p95 latency; tokens en geschatte kosten per journey
- **Afkeur-voorbeelden**: de 5 slechtste outputs met validator-reden
  (leert meer dan gemiddelden)

### 3.4 Rapport

Markdown per run: tabel met metrieken per provider + de afkeur-voorbeelden.
Bewaar onder `docs/simulations/<datum>-<provider>.md` — zo wordt
prompt-iteratie vergelijkbaar over tijd.

## 4. Stappenplan

1. `LlmClient` uitbreiden met `completeWithUsage` (backwards compatible).
2. `MetaLlmClient` bouwen (jvmMain, JDK HttpClient, zoals `OpenAiLlmClient`).
3. Harness + profielenset (jvmMain, alleen dev-gebruik).
4. Eerste run met Meta-credits → rapport `docs/simulations/`.
5. Prompt bijsturen op de afkeur-voorbeelden; herhalen tot first-try-validatie > 90%.
6. Vergelijkingsruns: Ollama / LM Studio lokaal (gratis), daarna OpenAI/Claude/Grok.

## 5. Buiten scope

- Geen nieuwe node-types; de 60 blijven het vocabulaire.
- Geen runtime-executie in de harness — alleen plannen en valideren.
- Geen keys of profiel-data in de repo.
