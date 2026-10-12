# Gratis LLM-providers — registreren per provider

Alle onderstaande providers zijn OpenAI-compatible en werken via
`LLM_PROVIDER=<naam>` + `LLM_API_KEY=<key>` (zie `LlmProviderConfig.fromEnv`).
Gratis tiers wijzigen regelmatig — check de actuele limieten bij de provider.

## 1. OpenRouter — `LLM_PROVIDER=openrouter`

1. Account aanmaken op [openrouter.ai](https://openrouter.ai)
2. Key aanmaken op [openrouter.ai/keys](https://openrouter.ai/keys) (begint met `sk-or-…`)
3. Gebruik een model met `:free`-suffix, bijv. `meta-llama/llama-3.3-70b-instruct:free`
   (default in de client). Vrije modellen rouleren — actueel overzicht op
   [openrouter.ai/models](https://openrouter.ai/models).
4. Limiet: ~20/min, 50/dag gratis (1.000/dag na eenmalig $10 credit kopen).

## 2. Groq — `LLM_PROVIDER=groq`

1. Account aanmaken op [console.groq.com](https://console.groq.com)
2. Key aanmaken op [console.groq.com/keys](https://console.groq.com/keys)
   (begint met `gsk_…`). Geen creditcard nodig.
3. Limiet: ~30/min, ~1.000+/dag afhankelijk van model.

## 3. Cerebras — `LLM_PROVIDER=cerebras`

1. Account aanmaken op [cloud.cerebras.ai](https://cloud.cerebras.ai)
2. API-key aanmaken in het dashboard (begint met `csk-…`). Geen creditcard nodig.
3. Limiet: ~1M tokens/dag gratis.

## 4. Google AI Studio (Gemini) — `LLM_PROVIDER=gemini`

1. Inloggen met Google-account op [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
2. **Create API key** (nieuwe key aanmaken; oude "standard" keys werken sinds
   juni 2026 niet meer).
3. Let op: free-tier prompts mogen door Google gebruikt worden voor
   modeltraining. Voor journeys met klantdata liever een betaalde tier
   (of pseudonimiseren — de planner doet dat al voor cloud-providers).
4. Limiet: per-model gratis quota (per minuut en per dag).

## 5. Mistral — `LLM_PROVIDER=mistral`

1. Account aanmaken op [console.mistral.ai](https://console.mistral.ai)
2. Key aanmaken onder API keys. Zet de gratis **Experiment**-plan aan.
3. Limiet: ~2/min, ~1B tokens/maand op Experiment.

## 6. NVIDIA NIM — `LLM_PROVIDER=nvidia`

1. Account aanmaken op [build.nvidia.com](https://build.nvidia.com)
2. API-key genereren (geen creditcard, gratis credits verlopen niet).
3. Limiet: ~1.000 requests/dag per model.

## Lokaal (geen registratie nodig)

- **Ollama** (`LLM_PROVIDER=ollama`): installeer van ollama.com, `ollama pull llama3.1`.
- **LM Studio** (`LLM_PROVIDER=lmstudio`): installeer, laad een model, start de server.

## Gebruik

```bash
export LLM_PROVIDER=groq
export LLM_API_KEY=gsk_…
# optioneel: export LLM_MODEL=llama-3.3-70b-versatile
```

Keys nooit in code of chat zetten — via env of Secure Vault.
