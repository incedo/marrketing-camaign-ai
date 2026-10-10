package com.intelliworks.journey

import kotlinx.serialization.Serializable

/**
 * How fast the answer is needed. Decides the strategy, not just the timeout:
 * - REALTIME: website / in-app. Milliseconds. Cache or rules only — never an
 *   inline LLM call.
 * - INTERACTIVE: push / chat. ~2s budget. One fast LLM attempt with a timeout,
 *   rules on timeout.
 * - BACKGROUND: email / batch. Take the time needed; full LLM advice.
 */
@Serializable
enum class AdviceUrgency { REALTIME, INTERACTIVE, BACKGROUND }

@Serializable
enum class AdviceSource { CACHE, RULES, LLM }

@Serializable
data class AdviceRequest(
    val personId: String,
    val profile: PersonProfile,
    val journeyId: String,
    val journey: Journey,
    val currentNodeId: String,
    val visitedNodeIds: List<String> = emptyList(),
    val behavior: MeasuredBehavior,
    val products: List<Product> = emptyList(),
    /** Where the person is RIGHT NOW — drives the urgency. */
    val channel: Channel,
    val urgency: AdviceUrgency,
    /** Restrict the choice; empty = choose among journey nodes. */
    val candidates: List<String> = emptyList(),
    val nowEpochMillis: Long,
)

@Serializable
data class AdviceResponse(
    val recommendedNodeId: String,
    val confidence: Double,
    val reasoning: String,
    val content: String? = null,
    val servedBy: AdviceSource,
    val latencyMs: Long,
)

/**
 * Runtime advice: the executor asks "what is the next step for this person,
 * right now?" at decision points. Synchronous, as fast as [AdviceRequest.urgency]
 * demands.
 */
interface JourneyAdviceService {
    suspend fun advise(request: AdviceRequest): AdviceResponse

    /**
     * Precompute advice for a future decision point and warm the cache.
     * The runtime calls this (async) on journey entry and on relevant events,
     * so REALTIME requests later hit warm cache entries.
     */
    suspend fun warmup(request: AdviceRequest): AdviceResponse
}

interface AdviceCache {
    fun get(key: String, nowEpochMillis: Long): AdviceResponse?
    fun put(key: String, response: AdviceResponse, ttlMs: Long, nowEpochMillis: Long)
}

/** Cache key: person + journey + position + path so far. */
fun adviceKey(request: AdviceRequest): String =
    listOf(
        request.personId,
        request.journeyId,
        request.currentNodeId,
        request.visitedNodeIds.joinToString(","),
    ).joinToString("|").hashCode().toString(16)

class InMemoryAdviceCache : AdviceCache {
    private data class Entry(val response: AdviceResponse, val expiresAt: Long)
    private val map = mutableMapOf<String, Entry>()

    override fun get(key: String, nowEpochMillis: Long): AdviceResponse? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= nowEpochMillis) {
            map.remove(key)
            return null
        }
        return entry.response
    }

    override fun put(key: String, response: AdviceResponse, ttlMs: Long, nowEpochMillis: Long) {
        map[key] = Entry(response, nowEpochMillis + ttlMs)
    }
}
