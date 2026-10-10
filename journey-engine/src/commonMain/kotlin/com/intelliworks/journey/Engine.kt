package com.intelliworks.journey

/**
 * Everything the engine needs to plan one journey.
 *
 * @param prompt campaign goal in plain language, e.g. "win back dormant
 * subscribers with a discount ladder". Steers the LLM-generated copy.
 * @param nowEpochMillis reference time, passed in so planning stays
 * deterministic and testable.
 */
data class JourneyInput(
    val profile: PersonProfile,
    val behavior: MeasuredBehavior,
    val products: List<Product>,
    val prompt: String,
    val nowEpochMillis: Long,
)

interface JourneyEngine {
    suspend fun plan(input: JourneyInput): Journey
}

/**
 * Rule-based journey assembly with LLM-generated marketing copy.
 *
 * Shape of every planned journey:
 *   start -> decision(engaged?) -yes-> marketing -> wait(purchase) -> end
 *                                 -no--> abTest -> marketing x2 -> wait -> end
 */
class LlmJourneyEngine(private val llm: LlmClient) : JourneyEngine {

    override suspend fun plan(input: JourneyInput): Journey {
        val engaged = isEngaged(input)
        val picks = pickProducts(input)
        val hero = picks.firstOrNull()

        suspend fun copyFor(stepGoal: String): String = llm.complete(
            prompt = buildString {
                append("Campaign goal: ${input.prompt}. ")
                append("Write one short marketing message (max 140 characters) ")
                append("for ${input.profile.name ?: "the customer"}. ")
                if (hero != null) append("Feature the product '${hero.name}'. ")
                append("Purpose of this step: $stepGoal.")
            },
            system = "You write concise marketing copy. No hashtags, no emojis.",
        )

        return buildJourney {
            start("start")
            decision(
                id = "seg",
                question = "Is the customer engaged?",
                trueNodeId = "rec",
                falseNodeId = "ab",
            )
            marketing(
                id = "rec",
                channel = if (engaged) Channel.PUSH else Channel.EMAIL,
                content = copyFor("recommend a relevant product"),
                productIds = picks.map { it.id },
                nextNodeId = "wait",
            )
            waitOnEvent(
                id = "wait",
                eventType = "purchase_completed",
                timeoutHours = 72,
                onTimeoutNodeId = "end_lost",
                nextNodeId = "end_won",
            )
            abTest(
                id = "ab",
                name = "winback-discount",
                variantANodeId = "disc10",
                variantBNodeId = "disc20",
            )
            marketing(
                id = "disc10",
                channel = Channel.EMAIL,
                content = copyFor("offer a 10% win-back discount"),
                productIds = picks.map { it.id },
                nextNodeId = "wait2",
            )
            marketing(
                id = "disc20",
                channel = Channel.EMAIL,
                content = copyFor("offer a 20% win-back discount"),
                productIds = picks.map { it.id },
                nextNodeId = "wait2",
            )
            waitOnEvent(
                id = "wait2",
                eventType = "purchase_completed",
                timeoutHours = 168,
                onTimeoutNodeId = "end_lost",
                nextNodeId = "end_won",
            )
            end("end_won", outcome = "converted")
            end("end_lost", outcome = "lapsed")

            edge("start", "seg")
            edge("seg", "rec", "yes")
            edge("seg", "ab", "no")
            edge("rec", "wait")
            edge("wait", "end_won", "purchased")
            edge("wait", "end_lost", "timeout")
            edge("ab", "disc10", "A")
            edge("ab", "disc20", "B")
            edge("disc10", "wait2")
            edge("disc20", "wait2")
            edge("wait2", "end_won", "purchased")
            edge("wait2", "end_lost", "timeout")
        }
    }

    private fun isEngaged(input: JourneyInput): Boolean {
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        return input.behavior.events.any { it.atEpochMillis >= input.nowEpochMillis - thirtyDaysMillis }
    }

    /** Rank products by tag overlap with the profile; fall back to given order. */
    private fun pickProducts(input: JourneyInput): List<Product> {
        if (input.products.isEmpty()) return emptyList()
        val profileTags = input.profile.segments + input.profile.attributes.values
        return input.products
            .sortedByDescending { product -> product.tags.count { it in profileTags } }
            .take(3)
    }
}
