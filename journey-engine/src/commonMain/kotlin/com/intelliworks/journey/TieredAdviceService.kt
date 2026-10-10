package com.intelliworks.journey

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * Latency-tiered advice. The trick for "as fast as requested":
 * - REALTIME: serve from precomputed cache; on miss, instant rules. The LLM is
 *   never on this path — it runs ahead of time via [warmup].
 * - INTERACTIVE: one LLM attempt with a hard timeout, rules on timeout/failure.
 * - BACKGROUND: full LLM advice, then cached for later realtime hits.
 */
class TieredAdviceService(
    private val llm: LlmClient,
    private val target: LlmTarget,
    private val cache: AdviceCache = InMemoryAdviceCache(),
    private val cacheTtlMs: Long = 15 * 60 * 1000,
    private val interactiveTimeoutMs: Long = 1500,
) : JourneyAdviceService {

    override suspend fun advise(request: AdviceRequest): AdviceResponse {
        val start = TimeSource.Monotonic.markNow()
        fun done(response: AdviceResponse) =
            response.copy(latencyMs = start.elapsedNow().inWholeMilliseconds)

        val key = adviceKey(request)
        cache.get(key, request.nowEpochMillis)?.let {
            return done(it.copy(servedBy = AdviceSource.CACHE))
        }

        return when (request.urgency) {
            AdviceUrgency.REALTIME ->
                done(ruleAdvice(request))
            AdviceUrgency.INTERACTIVE -> {
                val advice = withTimeoutOrNull(interactiveTimeoutMs) { llmAdvice(request) }
                done(advice ?: ruleAdvice(request))
            }
            AdviceUrgency.BACKGROUND -> {
                val advice = llmAdvice(request) ?: ruleAdvice(request)
                cache.put(key, advice.copy(latencyMs = 0), cacheTtlMs, request.nowEpochMillis)
                done(advice)
            }
        }
    }

    override suspend fun warmup(request: AdviceRequest): AdviceResponse {
        val advice = llmAdvice(request) ?: ruleAdvice(request)
        cache.put(
            adviceKey(request),
            advice.copy(latencyMs = 0),
            cacheTtlMs,
            request.nowEpochMillis,
        )
        return advice
    }

    private fun effectiveCandidates(request: AdviceRequest): List<String> {
        val ids = request.candidates.ifEmpty { request.journey.nodes.keys.toList() }
        return ids.filter { it in request.journey.nodes && it != request.currentNodeId }
    }

    /** Instant, deterministic fallback: best product-tag match among candidates. */
    private fun ruleAdvice(request: AdviceRequest): AdviceResponse {
        val candidates = effectiveCandidates(request)
        if (candidates.isEmpty()) {
            return AdviceResponse(request.currentNodeId, 0.0, "No candidates.", null, AdviceSource.RULES, 0)
        }
        val profileTags = request.profile.segments + request.profile.attributes.values
        val byId = request.products.associateBy { it.id }
        var bestId = candidates.first()
        var bestScore = -1.0
        for (id in candidates) {
            val node = request.journey.nodes[id]
            val score = if (node is MarketingNode) {
                node.productIds
                    .flatMap { byId[it]?.tags ?: emptySet() }
                    .count { it in profileTags }.toDouble()
            } else 0.0
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return AdviceResponse(
            recommendedNodeId = bestId,
            confidence = 0.5,
            reasoning = "Rule-based default: best product-tag match among candidates.",
            content = null,
            servedBy = AdviceSource.RULES,
            latencyMs = 0,
        )
    }

    private suspend fun llmAdvice(request: AdviceRequest): AdviceResponse? {
        val candidates = effectiveCandidates(request)
        if (candidates.isEmpty()) return null
        val profile =
            if (target == LlmTarget.CLOUD) request.profile.pseudonymized() else request.profile
        val system = "You are a real-time journey advisor. " +
            "Output ONLY JSON: {\"recommendedId\":\"...\",\"confidence\":0.0-1.0,\"reasoning\":\"...\",\"content\":\"...\"}. " +
            "recommendedId must be one of the candidates."
        val prompt = buildString {
            appendLine("Customer: ${profile.name ?: "the customer"}; segments=${profile.segments.joinToString()}")
            val s = summarize(request.behavior, request.nowEpochMillis)
            appendLine(
                "Last active: ${s.daysSinceLastActive?.let { "$it days ago" } ?: "never"}; " +
                    "purchases(90d)=${s.purchaseCount90d}; trend=${s.trend}",
            )
            appendLine("Journey '${request.journeyId}' at node '${request.currentNodeId}'. Visited: ${request.visitedNodeIds.joinToString()}")
            appendLine("Channel right now: ${request.channel} (urgency ${request.urgency})")
            appendLine("Candidates:")
            candidates.forEach { id -> appendLine("- $id: ${describe(request.journey.nodes[id])}") }
            appendLine("Which candidate is the best next step? If it is a marketing node, put short copy in 'content' (max 140 chars, no hashtags, no emojis).")
        }
        val raw = try {
            llm.complete(prompt = prompt, system = system)
        } catch (_: Exception) {
            return null
        }
        return parseAdvice(raw, candidates)
    }

    private fun describe(node: JourneyNode?): String = when (node) {
        is MarketingNode -> "marketing via ${node.channel}, products=${node.productIds}"
        is DecisionNode -> "decision: ${node.question}"
        is WaitOnEventNode -> "wait for ${node.eventType}"
        is ABTestNode -> "A/B test ${node.name}"
        is EndNode -> "end (${node.outcome})"
        is StartNode -> "start"
        null -> "unknown"
    }

    private fun parseAdvice(raw: String, candidates: List<String>): AdviceResponse? {
        return try {
            parseAdviceOrThrow(raw, candidates)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAdviceOrThrow(raw: String, candidates: List<String>): AdviceResponse? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val o = Json.parseToJsonElement(cleaned).jsonObject
        val id = o["recommendedId"]?.jsonPrimitive?.content ?: return null
        if (id !in candidates) return null
        return AdviceResponse(
            recommendedNodeId = id,
            confidence = o["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
            reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull ?: "",
            content = o["content"]?.jsonPrimitive?.contentOrNull,
            servedBy = AdviceSource.LLM,
            latencyMs = 0,
        )
    }
}
