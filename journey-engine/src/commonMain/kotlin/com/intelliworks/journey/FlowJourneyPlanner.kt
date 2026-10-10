package com.intelliworks.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plans directly in the runtime's 60 [FlowNodeType]s.
 *
 * The full vocabulary lives in the model, but the prompt only carries the
 * relevant subset for this campaign (controls + decides + waits + measure basics,
 * communicate types per allowed channel, AI/personalize helpers). The proposal is
 * validated against [FlowNodeSpec]s with the usual retry loop; failures fall back
 * to a minimal rule-based flow.
 */
interface FlowJourneyPlanner {
    suspend fun planFlow(request: PlanRequest): FlowJourney
}

class LlmFlowJourneyPlanner(
    private val llm: LlmClient,
    private val target: LlmTarget,
    private val maxAttempts: Int = 3,
    private val specs: Map<FlowNodeType, FlowNodeSpec> = flowNodeSpecs(),
    /** When set, every planned journey is published as a [JourneyPlanned] event. */
    private val eventBus: EventBus? = null,
    private val journeyIdOf: (PlanRequest) -> String =
        { r -> "j-${r.profile.id}-${r.nowEpochMillis}" },
) : FlowJourneyPlanner {

    override suspend fun planFlow(request: PlanRequest): FlowJourney {
        val input = request.toPlannerInput(target)
        val types = relevantTypes(request.campaign.constraints)
        var problems: List<String> = emptyList()
        repeat(maxAttempts) { attempt ->
            val raw = llm.complete(
                prompt = buildUserPrompt(input, types, problems.takeIf { attempt > 0 }),
                system = buildSystemPrompt(types, request.campaign.constraints),
            )
            val journey = parseFlow(raw)
            val found = if (journey != null) journey.validate(specs)
            else listOf("Response was not a JSON object with nodes/edges")
            if (found.isEmpty()) {
                publish(request, journey!!, "llm plan accepted on attempt ${attempt + 1}")
                return journey
            }
            problems = found
        }
        val fallback = ruleBasedFallback(request)
        publish(request, fallback, "rule-based fallback after $maxAttempts failed attempts")
        return fallback
    }

    private suspend fun publish(request: PlanRequest, journey: FlowJourney, reasoning: String) {
        val bus = eventBus ?: return
        bus.publish(
            JourneyPlanned(
                atEpochMillis = request.nowEpochMillis,
                journeyId = journeyIdOf(request),
                journey = journey,
                goal = request.campaign.goal.takeIf { it.isNotBlank() }?.let {
                    JourneyGoal(id = "campaign-goal", metric = it)
                },
                planReasoning = reasoning,
            ),
        )
    }

    private fun relevantTypes(c: CampaignConstraints): List<FlowNodeType> {
        val set = linkedSetOf(
            FlowNodeType.START_TRIGGER, FlowNodeType.EXIT_JOURNEY,
            FlowNodeType.IF_ELSE, FlowNodeType.AB_SPLIT,
            FlowNodeType.WAIT_UNTIL_EVENT, FlowNodeType.WAIT,
            FlowNodeType.TRACK_EVENT, FlowNodeType.CONVERSION_CHECK,
        )
        if (Channel.EMAIL in c.allowedChannels) set += FlowNodeType.SEND_EMAIL
        if (Channel.PUSH in c.allowedChannels) set += FlowNodeType.SEND_PUSH
        if (Channel.SMS in c.allowedChannels) set += FlowNodeType.SEND_SMS
        if (Channel.WHATSAPP in c.allowedChannels) set += FlowNodeType.SEND_WHATSAPP
        if (Channel.IN_APP in c.allowedChannels) set += FlowNodeType.SEND_IN_APP
        // AI-backed decisions; the runtime resolves these via the advice API.
        set += listOf(
            FlowNodeType.ML_DECISION, FlowNodeType.NEXT_BEST_ACTION,
            FlowNodeType.NEXT_BEST_CHANNEL, FlowNodeType.PERSONALIZE_CONTENT,
        )
        return set.toList()
    }

    private fun buildSystemPrompt(types: List<FlowNodeType>, c: CampaignConstraints): String =
        buildString {
            appendLine("You are a journey planner for lifecycle marketing.")
            appendLine("Output ONLY a JSON object: {\"nodes\": [...], \"edges\": [...]}. No prose, no markdown.")
            appendLine("Node: {\"id\": \"...\", \"type\": \"TYPE\", \"params\": {...}}. Edge: {\"from\": \"...\", \"to\": \"...\", \"slot\": \"...\"}.")
            appendLine("Use ONLY these node types:")
            for (type in types) {
                val spec = specs[type] ?: continue
                append("- $type: ${spec.description}")
                if (spec.requiredParams.isNotEmpty()) append(" Required params: ${spec.requiredParams.joinToString()}.")
                if (spec.slots.isNotEmpty()) append(" Slots: ${spec.slots.joinToString()}.")
                appendLine()
            }
            appendLine("Hard rules:")
            appendLine("- exactly one START_TRIGGER node with id \"start\"; every node reachable from it")
            appendLine("- every referenced node id and every slot must exist per the list above")
            appendLine("- at most ${c.maxNodes} nodes")
            c.maxDiscountPercent?.let { appendLine("- never offer a discount above $it%") }
            if (c.forbiddenTopics.isNotEmpty()) {
                appendLine("- never mention these topics: ${c.forbiddenTopics.joinToString()}")
            }
            appendLine("- prefer fewer, higher-impact steps; end every path in EXIT_JOURNEY")
        }

    private fun buildUserPrompt(
        input: PlannerInput,
        types: List<FlowNodeType>,
        previousProblems: List<String>?,
    ): String = buildString {
        appendLine("Plan a marketing journey as JSON using only these types: ${types.joinToString()}.")
        appendLine()
        appendLine("Campaign goal: ${input.campaign.goal}")
        appendLine("Copy language: ${input.language}")
        val p = input.profile
        appendLine("Customer: ${p.name ?: "the customer"} (id ${p.id}); segments=${p.segments.joinToString()}")
        val s = input.behaviorSummary
        appendLine(
            "Behavior: last active ${s.daysSinceLastActive?.let { "$it days ago" } ?: "never"}; " +
                "purchases(90d)=${s.purchaseCount90d}; trend=${s.trend}",
        )
        appendLine("Products: ${input.products.joinToString { "${it.id}=${it.name}[${it.type}]" }}")
        if (input.segmentPerformance.isNotEmpty()) {
            appendLine("Past performance — prefer archetypes with evidence:")
            input.segmentPerformance.forEach { perf ->
                appendLine("- ${perf.archetype} / ${perf.segment}: ${(perf.conversionRate * 100).toInt()}% converted")
            }
        }
        if (previousProblems != null) {
            appendLine()
            appendLine("Your previous plan FAILED validation. Fix these problems and resend the FULL corrected JSON:")
            previousProblems.forEach { appendLine("- $it") }
        }
    }

    private fun parseFlow(raw: String): FlowJourney? {
        return try {
            parseFlowOrThrow(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlowOrThrow(raw: String): FlowJourney? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val root = Json.parseToJsonElement(cleaned).jsonObject
        val nodes = linkedMapOf<String, FlowNode>()
        for (el in root["nodes"]?.jsonArray ?: return null) {
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return null
            if (id in nodes) return null
            val type = try {
                FlowNodeType.valueOf(o["type"]?.jsonPrimitive?.content ?: return null)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val params = o["params"]?.jsonObject?.entries?.associate { (k, v) ->
                k to v.jsonPrimitive.content
            } ?: emptyMap()
            nodes[id] = FlowNode(id, type, params)
        }
        val edges = (root["edges"]?.jsonArray ?: emptyList()).map { el ->
            val o = el.jsonObject
            FlowEdge(
                o["from"]?.jsonPrimitive?.content ?: return null,
                o["to"]?.jsonPrimitive?.content ?: return null,
                o["slot"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return FlowJourney(nodes, edges)
    }

    private fun ruleBasedFallback(request: PlanRequest): FlowJourney {
        val sendType = when (request.campaign.constraints.allowedChannels.firstOrNull()) {
            Channel.PUSH -> FlowNodeType.SEND_PUSH
            Channel.SMS -> FlowNodeType.SEND_SMS
            Channel.WHATSAPP -> FlowNodeType.SEND_WHATSAPP
            Channel.IN_APP -> FlowNodeType.SEND_IN_APP
            else -> FlowNodeType.SEND_EMAIL
        }
        return FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "send" to FlowNode("send", sendType, mapOf("content" to "[fallback copy]")),
                "wait" to FlowNode(
                    "wait", FlowNodeType.WAIT_UNTIL_EVENT,
                    mapOf("eventType" to "purchase_completed", "timeoutHours" to "72"),
                ),
                "end_won" to FlowNode("end_won", FlowNodeType.EXIT_JOURNEY, mapOf("reason" to "converted")),
                "end_lost" to FlowNode("end_lost", FlowNodeType.EXIT_JOURNEY, mapOf("reason" to "lapsed")),
            ),
            edges = listOf(
                FlowEdge("start", "send", "next"),
                FlowEdge("send", "wait", "next"),
                FlowEdge("wait", "end_won", "event"),
                FlowEdge("wait", "end_lost", "timeout"),
            ),
        )
    }
}
