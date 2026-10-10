package com.intelliworks.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * LLM-driven journey planner.
 *
 * The LLM proposes a journey as strict JSON; the proposal is parsed and checked
 * ([Journey.validate] plus copy/channel/budget checks). Failures are fed back to the
 * LLM for another attempt, up to [maxAttempts]. If no valid plan emerges, planning
 * falls back to the rule-based [LlmJourneyEngine] — planning never hard-fails and
 * never ships an invalid journey.
 */
class LlmJourneyPlanner(
    private val llm: LlmClient,
    private val target: LlmTarget,
    private val maxAttempts: Int = 3,
    fallback: JourneyPlanner? = null,
) : JourneyPlanner {

    private val ruleBased = LlmJourneyEngine(llm)
    private val fallbackPlanner: JourneyPlanner = fallback ?: ruleBased.asPlanner()

    override suspend fun plan(request: PlanRequest): Journey {
        val input = request.toPlannerInput(target)
        val system = buildSystemPrompt(input.campaign.constraints)
        var problems: List<String> = emptyList()
        repeat(maxAttempts) { attempt ->
            val userPrompt = buildUserPrompt(input, problems.takeIf { attempt > 0 })
            val raw = llm.complete(prompt = userPrompt, system = system)
            val journey = parsePlan(raw)
            val found = if (journey != null) checkPlan(journey, input)
            else listOf("Response was not a JSON object with nodes/edges")
            if (found.isEmpty()) return journey!!
            problems = found
        }
        return fallbackPlanner.plan(request)
    }

    private fun buildSystemPrompt(c: CampaignConstraints): String = buildString {
        appendLine("You are a journey planner for lifecycle marketing.")
        appendLine("Output ONLY a JSON object: {\"nodes\": [...], \"edges\": [...]}. No prose, no markdown.")
        appendLine("Node types:")
        appendLine("- {\"type\":\"start\",\"id\":\"start\"} — exactly one, id must be \"start\"")
        appendLine("- {\"type\":\"end\",\"id\",\"outcome\"}")
        appendLine("- {\"type\":\"decision\",\"id\",\"question\",\"trueNodeId\",\"falseNodeId\"}")
        appendLine("- {\"type\":\"marketing\",\"id\",\"channel\",\"content\",\"productIds\",\"nextNodeId\"}")
        appendLine("- {\"type\":\"waitOnEvent\",\"id\",\"eventType\",\"timeoutHours\",\"onTimeoutNodeId\",\"nextNodeId\"}")
        appendLine("- {\"type\":\"abTest\",\"id\",\"name\",\"variantANodeId\",\"variantBNodeId\",\"splitPercentA\"}")
        appendLine("Hard rules:")
        appendLine("- every referenced node id must exist; every node must be reachable from start")
        appendLine("- abTest splitPercentA must be between 1 and 99")
        appendLine("- waitOnEvent timeoutHours must not exceed ${c.maxWaitHours}")
        appendLine("- marketing channel must be one of: ${c.allowedChannels.joinToString()}")
        c.maxDiscountPercent?.let { appendLine("- never offer a discount above $it%") }
        appendLine("- marketing content: max 140 characters, no hashtags, no emojis")
        if (c.forbiddenTopics.isNotEmpty()) {
            appendLine("- never mention these topics: ${c.forbiddenTopics.joinToString()}")
        }
        appendLine("- at most ${c.maxNodes} nodes")
        appendLine("- prefer fewer, higher-impact steps")
        appendLine("- every path must end in a purchase wait or an explicit end node")
    }

    private fun buildUserPrompt(input: PlannerInput, previousProblems: List<String>?): String =
        buildString {
            appendLine("Plan a marketing journey as JSON.")
            appendLine()
            appendLine("Campaign goal: ${input.campaign.goal}")
            appendLine("Copy language: ${input.language} (locale ${input.campaign.constraints.locale})")
            val p = input.profile
            appendLine("Customer: ${p.name ?: "the customer"} (id ${p.id})")
            if (p.segments.isNotEmpty()) appendLine("Segments: ${p.segments.joinToString()}")
            if (p.attributes.isNotEmpty()) {
                appendLine("Attributes: ${p.attributes.entries.joinToString { "${it.key}=${it.value}" }}")
            }
            val s = input.behaviorSummary
            appendLine(
                "Behavior: last active " +
                    (s.daysSinceLastActive?.let { "$it days ago" } ?: "never") +
                    "; purchases(90d)=${s.purchaseCount90d}" +
                    "; spend(90d)=${s.totalSpendCents90d}c" +
                    "; trend=${s.trend}" +
                    (if (s.isNewCustomer) "; NEW customer" else ""),
            )
            if (s.eventCounts30d.isNotEmpty()) {
                appendLine("Events(30d): ${s.eventCounts30d.entries.joinToString { "${it.key}=${it.value}" }}")
            }
            if (input.recentEvents.isNotEmpty()) {
                appendLine("Recent events (oldest first):")
                input.recentEvents.forEach { e ->
                    append("— ${e.type} @ ${e.atEpochMillis}")
                    if (e.properties.isNotEmpty()) append(" ${e.properties}")
                    appendLine()
                }
            }
            appendLine("Products:")
            input.products.forEach { prod ->
                append("— ${prod.id}: ${prod.name} [${prod.type}]")
                prod.priceCents?.let { append(" ${it}c") }
                if (prod.tags.isNotEmpty()) append(" tags=${prod.tags.joinToString()}")
                appendLine()
            }
            if (input.segmentPerformance.isNotEmpty()) {
                appendLine("Past performance — prefer archetypes with evidence:")
                input.segmentPerformance.forEach { perf ->
                    val pct = (perf.conversionRate * 100).toInt()
                    appendLine("— ${perf.archetype} / ${perf.segment}: ${perf.journeys} journeys, $pct% converted")
                }
            }
            if (previousProblems != null) {
                appendLine()
                appendLine("Your previous plan FAILED validation. Fix these problems and resend the FULL corrected JSON:")
                previousProblems.forEach { appendLine("— $it") }
            }
        }

    private val json = Json { ignoreUnknownKeys = true }

    private fun parsePlan(raw: String): Journey? {
        return try {
            parsePlanOrThrow(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePlanOrThrow(raw: String): Journey? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val root = json.parseToJsonElement(cleaned).jsonObject
        val builder = JourneyBuilder()
        for (el in root["nodes"]?.jsonArray ?: return null) {
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return null
            when (o["type"]?.jsonPrimitive?.content) {
                "start" -> builder.start(id)
                "end" -> builder.end(id, o["outcome"]?.jsonPrimitive?.contentOrNull ?: "completed")
                "decision" -> builder.decision(
                    id,
                    o["question"]?.jsonPrimitive?.content ?: return null,
                    o["trueNodeId"]?.jsonPrimitive?.content ?: return null,
                    o["falseNodeId"]?.jsonPrimitive?.content ?: return null,
                )
                "marketing" -> builder.marketing(
                    id,
                    Channel.valueOf(o["channel"]?.jsonPrimitive?.content ?: return null),
                    o["content"]?.jsonPrimitive?.content ?: return null,
                    o["productIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    o["nextNodeId"]?.jsonPrimitive?.content ?: return null,
                )
                "waitOnEvent" -> builder.waitOnEvent(
                    id,
                    o["eventType"]?.jsonPrimitive?.content ?: return null,
                    o["timeoutHours"]?.jsonPrimitive?.longOrNull,
                    o["onTimeoutNodeId"]?.jsonPrimitive?.content ?: return null,
                    o["nextNodeId"]?.jsonPrimitive?.content ?: return null,
                )
                "abTest" -> builder.abTest(
                    id,
                    o["name"]?.jsonPrimitive?.content ?: return null,
                    o["variantANodeId"]?.jsonPrimitive?.content ?: return null,
                    o["variantBNodeId"]?.jsonPrimitive?.content ?: return null,
                    o["splitPercentA"]?.jsonPrimitive?.intOrNull ?: 50,
                )
                else -> return null
            }
        }
        for (el in root["edges"]?.jsonArray ?: emptyList()) {
            val o = el.jsonObject
            builder.edge(
                o["from"]?.jsonPrimitive?.content ?: return null,
                o["to"]?.jsonPrimitive?.content ?: return null,
                o["label"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return builder.build()
    }

    private val discountRegex = Regex("""(\d+)\s*%""")

    private fun checkPlan(journey: Journey, input: PlannerInput): List<String> {
        val problems = journey.validate().toMutableList()
        val c = input.campaign.constraints
        if (journey.nodes.size > c.maxNodes) {
            problems += "Too many nodes: ${journey.nodes.size} > ${c.maxNodes}"
        }
        for (node in journey.nodes.values) {
            when (node) {
                is MarketingNode -> {
                    if (node.content.length > 140) {
                        problems += "Marketing node '${node.id}' copy exceeds 140 characters"
                    }
                    if (node.channel !in c.allowedChannels) {
                        problems += "Marketing node '${node.id}' uses disallowed channel ${node.channel}"
                    }
                    if ('#' in node.content) {
                        problems += "Marketing node '${node.id}' copy contains a hashtag"
                    }
                    c.maxDiscountPercent?.let { max ->
                        val offered = discountRegex.findAll(node.content)
                            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                        if (offered.any { it > max }) {
                            problems += "Marketing node '${node.id}' offers a discount above $max%"
                        }
                    }
                    val unknown = node.productIds.filter { pid ->
                        input.products.none { it.id == pid }
                    }
                    if (unknown.isNotEmpty()) {
                        problems += "Marketing node '${node.id}' references unknown products $unknown"
                    }
                }
                is WaitOnEventNode -> {
                    if (node.timeoutHours != null && node.timeoutHours > c.maxWaitHours) {
                        problems += "Wait node '${node.id}' timeout exceeds ${c.maxWaitHours}h"
                    }
                }
                else -> Unit
            }
        }
        return problems
    }
}

/** Adapts the rule-based engine to the planner contract (used as fallback). */
fun JourneyEngine.asPlanner(): JourneyPlanner = object : JourneyPlanner {
    override suspend fun plan(request: PlanRequest): Journey =
        this@asPlanner.plan(request.toJourneyInput())
}
