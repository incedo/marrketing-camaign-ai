package com.intelliworks.journey

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/**
 * Local runner: plans one demo journey with a real LLM.
 *
 * Configuration comes from the environment (see .env.example at the repo root):
 *   LLM_PROVIDER  e.g. groq, cerebras, openrouter, gemini, mistral, nvidia, ollama, lmstudio
 *   LLM_API_KEY   required for cloud providers; not needed for ollama/lmstudio
 *   LLM_MODEL     optional model override
 *
 * The key is never part of the repository: keep it in a local `.env` file
 * (gitignored) or export it in your shell.
 *
 * Run via:  ./scripts/run-planner.sh
 * or:       ./gradlew :journey-engine:runPlanner
 */
fun main() = runBlocking {
    val config = LlmProviderConfig.fromEnv()
    val provider = config.provider.lowercase()
    val local = provider == "ollama" || provider == "lmstudio"
    if (!local && config.apiKey.isNullOrBlank()) {
        System.err.println(
            "LLM_API_KEY is not set. Copy .env.example to .env and fill in your key, " +
                "or export LLM_API_KEY in your shell. The key stays on your machine.",
        )
        exitProcess(1)
    }

    val target = if (local) LlmTarget.LOCAL else LlmTarget.CLOUD
    println("Planning with provider '${config.provider}' (target=$target)...")

    val now = System.currentTimeMillis()
    val day = 24 * 60 * 60 * 1000L
    val request = PlanRequest(
        profile = PersonProfile(
            id = "demo-001",
            attributes = mapOf("firstName" to "Lisa"),
            segments = setOf("fashion"),
            language = "nl",
        ),
        behavior = MeasuredBehavior(
            personId = "demo-001",
            events = listOf(
                BehaviorEvent("cart_abandoned", now - 2 * day, mapOf("cartValueCents" to "8995")),
                BehaviorEvent("page_view", now - 3 * day),
                BehaviorEvent("email_opened", now - 9 * day),
            ),
        ),
        products = listOf(
            Product("p-100", "Zomerjurk Bloem", ProductType.PHYSICAL, setOf("fashion", "zomer"), 8995),
        ),
        campaign = Campaign(
            goal = "win back the abandoned cart within 7 days",
            constraints = CampaignConstraints(
                allowedChannels = setOf(Channel.EMAIL, Channel.PUSH),
                maxDiscountPercent = 10,
            ),
        ),
        nowEpochMillis = now,
    )

    val planner = LlmFlowJourneyPlanner(llm = config.client(), target = target)
    val journey = planner.planFlow(request)

    val json = Json { prettyPrint = true }
    println(json.encodeToString(journey))

    val problems = journey.validate()
    if (problems.isEmpty()) {
        println("\nJourney is valid (${journey.nodes.size} nodes, ${journey.edges.size} edges).")
    } else {
        println("\nJourney has ${problems.size} validation problem(s):")
        problems.forEach { println(" - $it") }
    }
}
