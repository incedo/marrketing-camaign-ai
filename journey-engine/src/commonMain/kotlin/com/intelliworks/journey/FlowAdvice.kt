package com.intelliworks.journey

import com.intelliworks.journey.FlowNodeType.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * Flow-based, latency-tiered advice (event-oriented implementation plan,
 * steps 3–5). Resolves PERSONALIZE_CONTENT (LLM action node) and ML_DECISION
 * (goal arbiter) at runtime; the runtime reaches it through [AdviceSubscriber]
 * instead of calling it directly.
 *
 * Tiers mirror [TieredAdviceService]: REALTIME never calls an LLM inline
 * (warmup cache or rules), INTERACTIVE gets one attempt with a hard timeout,
 * BACKGROUND gets the full call and warms the cache.
 */
class FlowAdviceService(
    private val llm: LlmClient,
    private val target: LlmTarget,
    private val cache: FlowAdviceCache = InMemoryFlowAdviceCache(),
    private val cacheTtlMs: Long = 15 * 60 * 1000,
    private val interactiveTimeoutMs: Long = 1500,
) {
    suspend fun advise(request: AdviceRequested): AdviceResolved {
        val start = TimeSource.Monotonic.markNow()
        fun done(r: AdviceResolved) = r.copy(latencyMs = start.elapsedNow().inWholeMilliseconds)

        val node = request.journey.nodes[request.nodeId]
            ?: return done(
                AdviceResolved(
                    atEpochMillis = request.atEpochMillis,
                    runId = request.runId,
                    nodeId = request.nodeId,
                    reasoning = "Unknown node '${request.nodeId}'.",
                    servedBy = AdviceSource.RULES,
                ),
            )

        // Specific entry first, then the generic warmup entry.
        cache.get(flowAdviceKey(request, generic = false), request.atEpochMillis)?.let {
            return done(it.copy(servedBy = AdviceSource.CACHE))
        }
        cache.get(flowAdviceKey(request, generic = true), request.atEpochMillis)?.let {
            return done(it.copy(servedBy = AdviceSource.CACHE))
        }

        val resolved = when (request.urgency) {
            AdviceUrgency.REALTIME -> ruleResolve(request, node)
            AdviceUrgency.INTERACTIVE ->
                withTimeoutOrNull(interactiveTimeoutMs) { llmResolve(request, node) }
                    ?: ruleResolve(request, node)
            AdviceUrgency.BACKGROUND ->
                (llmResolve(request, node) ?: ruleResolve(request, node)).also {
                    cache.put(
                        flowAdviceKey(request, generic = false),
                        it.copy(latencyMs = 0),
                        cacheTtlMs,
                        request.atEpochMillis,
                    )
                }
        }
        return done(resolved)
    }

    /**
     * Precompute advice for a future decision point. Uses the generic cache
     * key (journey + node, no run-specific parts) so later REALTIME requests
     * hit it via the generic lookup in [advise].
     */
    suspend fun warmup(request: AdviceRequested): AdviceResolved {
        val node = request.journey.nodes[request.nodeId]
        val resolved = if (node == null) {
            ruleResolve(request, FlowNode(request.nodeId, EXIT_JOURNEY))
        } else {
            llmResolve(request, node) ?: ruleResolve(request, node)
        }
        cache.put(
            flowAdviceKey(request, generic = true),
            resolved.copy(latencyMs = 0),
            cacheTtlMs,
            request.atEpochMillis,
        )
        return resolved
    }

    // ------------------------------------------------------------------ rules

    private fun ruleResolve(request: AdviceRequested, node: FlowNode): AdviceResolved =
        when (node.type) {
            PERSONALIZE_CONTENT -> AdviceResolved(
                atEpochMillis = request.atEpochMillis,
                runId = request.runId,
                nodeId = request.nodeId,
                attributeName = node.params["outputAttribute"],
                attributeValue = node.params["fallbackValue"],
                reasoning = "Rule default: LLM unavailable on this tier; using fallbackValue.",
                servedBy = AdviceSource.RULES,
            )
            ML_DECISION -> goalArbitration(request, node)
            else -> {
                val next = effectiveNeighbors(request, node).firstOrNull()
                AdviceResolved(
                    atEpochMillis = request.atEpochMillis,
                    runId = request.runId,
                    nodeId = request.nodeId,
                    recommendedNodeId = next,
                    reasoning = "Rule default: first outgoing branch.",
                    servedBy = AdviceSource.RULES,
                )
            }
        }

    /**
     * Goal arbiter (llm-action-node-spec §4): steers this run toward its goal
     * from the run's own feedback. Deterministic — the LLM variant lives in
     * [llmBranch].
     */
    private fun goalArbitration(request: AdviceRequested, node: FlowNode): AdviceResolved {
        val neighbors = effectiveNeighbors(request, node)
        fun base(
            recommendedNodeId: String?,
            reasoning: String,
            goalAchieved: Boolean = false,
        ) = AdviceResolved(
            atEpochMillis = request.atEpochMillis,
            runId = request.runId,
            nodeId = request.nodeId,
            recommendedNodeId = recommendedNodeId,
            reasoning = reasoning,
            servedBy = AdviceSource.RULES,
            goalAchieved = goalAchieved,
        )

        val goal = request.goal
        if (goal != null && request.runEvents.any { it.event == "converted" }) {
            val end = neighbors.firstOrNull {
                request.journey.nodes[it]?.type == EXIT_JOURNEY
            }
            return base(end, "Goal '${goal.id}' achieved for this run; ending journey.", goalAchieved = true)
        }
        val sends = request.runEvents.filter { it.event == "sent" }
        val opens = request.runEvents.count { it.event == "opened" }
        if (sends.size >= 2 && opens == 0) {
            val lastChannel = sends.last().channel
            val switch = neighbors.firstOrNull {
                val n = request.journey.nodes[it]
                n != null && n.type in SEND_TYPES && sendChannel(n) != lastChannel
            }
            if (switch != null) {
                return base(
                    switch,
                    "No opens after ${sends.size} touches on ${lastChannel ?: "unknown channel"}; switching channel.",
                )
            }
        }
        return base(neighbors.firstOrNull(), "Rule default: first outgoing branch.")
    }

    private fun effectiveNeighbors(request: AdviceRequested, node: FlowNode): List<String> {
        val outgoing = request.journey.edges
            .filter { it.from == request.nodeId }
            .map { it.to }
            .filter { it in request.journey.nodes }
        return if (request.candidates.isEmpty()) outgoing
        else request.candidates.filter { it in request.journey.nodes }
    }

    // -------------------------------------------------------------------- LLM

    private suspend fun llmResolve(request: AdviceRequested, node: FlowNode): AdviceResolved? =
        when (node.type) {
            PERSONALIZE_CONTENT -> llmAction(request, node)
            ML_DECISION -> llmBranch(request, node) ?: goalArbitration(request, node).let {
                // LLM failed to pick: fall back to the deterministic arbiter,
                // but keep it marked LLM-attempted via reasoning.
                it.copy(reasoning = it.reasoning + " (LLM branch pick failed.)")
            }
            else -> llmBranch(request, node)
        }

    /** LLM action node: runs the prompt template, writes the output attribute. */
    private suspend fun llmAction(request: AdviceRequested, node: FlowNode): AdviceResolved? {
        val template = node.params["promptTemplate"] ?: return null
        val outputAttribute = node.params["outputAttribute"] ?: return null
        val safeAttributes = if (target == LlmTarget.CLOUD) {
            request.attributes.filterKeys { it.lowercase() !in PII_ATTRIBUTE_KEYS }
        } else request.attributes
        val rendered = PLACEHOLDER.replace(template) { m ->
            safeAttributes[m.groupValues[1]] ?: ""
        }
        val profile =
            if (target == LlmTarget.CLOUD) request.profile.pseudonymized() else request.profile
        val raw = try {
            llm.complete(
                prompt = "Write the value for journey attribute '$outputAttribute' " +
                    "from this template with customer data filled in:\n$rendered\n" +
                    "Customer: ${profile.name ?: "the customer"}; " +
                    "segments=${profile.segments.joinToString()}. " +
                    "Max 140 chars, no hashtags, no emojis.",
                system = "You are a journey copy engine. " +
                    "Output ONLY JSON: {\"value\":\"...\"}. No prose, no markdown.",
            )
        } catch (_: Exception) {
            return null
        }
        val value = try {
            Json.parseToJsonElement(cleanJson(raw))
                .jsonObject["value"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        } ?: return null
        return AdviceResolved(
            atEpochMillis = request.atEpochMillis,
            runId = request.runId,
            nodeId = request.nodeId,
            attributeName = outputAttribute,
            attributeValue = value,
            reasoning = "LLM generated '$outputAttribute' from prompt template.",
            servedBy = AdviceSource.LLM,
        )
    }

    /** Asks the LLM to pick among the node's outgoing neighbors. */
    private suspend fun llmBranch(request: AdviceRequested, node: FlowNode): AdviceResolved? {
        val neighbors = effectiveNeighbors(request, node)
        if (neighbors.isEmpty()) return null
        val raw = try {
            llm.complete(
                prompt = buildString {
                    appendLine("Journey at node '${request.nodeId}' (${node.type}).")
                    request.goal?.let { appendLine("Goal: ${it.metric} (id ${it.id}).") }
                    if (request.runEvents.isNotEmpty()) {
                        appendLine("Run so far: " + request.runEvents.joinToString {
                            "${it.event}@${it.nodeId}"
                        })
                    }
                    appendLine("Pick the best next node:")
                    neighbors.forEach { id ->
                        val n = request.journey.nodes[id]
                        appendLine("- $id: ${n?.type}${n?.params?.let { " $it" } ?: ""}")
                    }
                },
                system = "You are a journey decision engine. " +
                    "Output ONLY JSON: {\"recommendedId\":\"...\",\"reasoning\":\"...\"}. " +
                    "recommendedId must be one of the candidates. No prose, no markdown.",
            )
        } catch (_: Exception) {
            return null
        }
        return try {
            val o = Json.parseToJsonElement(cleanJson(raw)).jsonObject
            val id = o["recommendedId"]?.jsonPrimitive?.contentOrNull?.takeIf { it in neighbors }
                ?: return null
            AdviceResolved(
                atEpochMillis = request.atEpochMillis,
                runId = request.runId,
                nodeId = request.nodeId,
                recommendedNodeId = id,
                reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull ?: "LLM branch pick.",
                servedBy = AdviceSource.LLM,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")
        private val SEND_TYPES = setOf(SEND_EMAIL, SEND_PUSH, SEND_SMS, SEND_WHATSAPP, SEND_IN_APP)

        private fun cleanJson(raw: String): String =
            raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        private fun sendChannel(node: FlowNode): String? = when (node.type) {
            SEND_EMAIL -> "email"
            SEND_PUSH -> "push"
            SEND_SMS -> "sms"
            SEND_WHATSAPP -> "whatsapp"
            SEND_IN_APP -> "in_app"
            else -> node.params["channel"]
        }
    }
}

/** Cache for flow advice, keyed per request (specific) or per node (warmup). */
interface FlowAdviceCache {
    fun get(key: String, nowEpochMillis: Long): AdviceResolved?
    fun put(key: String, response: AdviceResolved, ttlMs: Long, nowEpochMillis: Long)
}

fun flowAdviceKey(request: AdviceRequested, generic: Boolean): String {
    val parts = if (generic) {
        listOf(request.journeyId, request.nodeId)
    } else {
        listOf(
            request.runId,
            request.journeyId,
            request.nodeId,
            request.attributes.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" },
        )
    }
    return parts.joinToString("|").hashCode().toString(16)
}

class InMemoryFlowAdviceCache : FlowAdviceCache {
    private data class Entry(val response: AdviceResolved, val expiresAt: Long)
    private val map = mutableMapOf<String, Entry>()

    override fun get(key: String, nowEpochMillis: Long): AdviceResolved? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= nowEpochMillis) {
            map.remove(key)
            return null
        }
        return entry.response
    }

    override fun put(key: String, response: AdviceResolved, ttlMs: Long, nowEpochMillis: Long) {
        map[key] = Entry(response, nowEpochMillis + ttlMs)
    }
}

/**
 * Step 3 wiring: listens for [AdviceRequested], resolves via [FlowAdviceService],
 * and publishes [AdviceResolved] — plus [AttributeWritten] / [GoalAchieved]
 * when the resolution produced them.
 */
class AdviceSubscriber(
    private val bus: EventBus,
    private val service: FlowAdviceService,
) {
    suspend fun start(): EventBus.Subscription = bus.subscribe(AdviceRequested::class) { req ->
        val resolved = service.advise(req)
        bus.publish(resolved)
        if (resolved.attributeName != null && resolved.attributeValue != null) {
            bus.publish(
                AttributeWritten(
                    atEpochMillis = req.atEpochMillis,
                    runId = req.runId,
                    attribute = resolved.attributeName,
                    value = resolved.attributeValue,
                ),
            )
        }
        val goal = req.goal
        if (resolved.goalAchieved && goal != null) {
            bus.publish(
                GoalAchieved(
                    atEpochMillis = req.atEpochMillis,
                    runId = req.runId,
                    goalId = goal.id,
                    metric = goal.metric,
                    value = goal.target ?: 1.0,
                ),
            )
        }
    }
}

/**
 * Step 6 wiring: subscribes to [JourneyPlanned] and precomputes advice for
 * every adaptive node, so later REALTIME requests hit warm cache entries.
 */
class WarmupSubscriber(
    private val bus: EventBus,
    private val service: FlowAdviceService,
) {
    suspend fun start(): EventBus.Subscription = bus.subscribe(JourneyPlanned::class) { planned ->
        for ((id, node) in planned.journey.nodes) {
            if (node.type != PERSONALIZE_CONTENT && node.type != ML_DECISION) continue
            service.warmup(
                AdviceRequested(
                    atEpochMillis = planned.atEpochMillis,
                    runId = "warmup",
                    journeyId = planned.journeyId,
                    nodeId = id,
                    journey = planned.journey,
                    profile = PersonProfile(id = "warmup", segments = setOf("warmup")),
                    behavior = MeasuredBehavior(personId = "warmup"),
                    channel = Channel.EMAIL,
                    urgency = AdviceUrgency.BACKGROUND,
                    goal = planned.goal,
                ),
            )
        }
    }
}
