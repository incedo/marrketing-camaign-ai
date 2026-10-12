package com.intelliworks.journey

import kotlinx.serialization.Serializable

/** Token usage of one LLM call, when the provider reports it. */
@Serializable
data class LlmUsage(val promptTokens: Int, val completionTokens: Int)

/** Minimal LLM abstraction. The engine only depends on this. */
interface LlmClient {
    suspend fun complete(prompt: String, system: String? = null): String

    /**
     * Same as [complete], plus token usage when the provider reports it.
     * Defaults to [complete] with unknown usage; providers override this.
     */
    suspend fun completeWithUsage(prompt: String, system: String? = null): Pair<String, LlmUsage?> =
        complete(prompt, system) to null
}

/**
 * Deterministic stand-in for tests: answers from [answers] by keyword,
 * [defaultAnswer] otherwise. Records every call for assertions.
 */
class DummyLlmClient(
    private val answers: Map<String, String> = emptyMap(),
    private val defaultAnswer: String = "[dummy copy]",
) : LlmClient {
    val calls = mutableListOf<Pair<String?, String>>()

    override suspend fun complete(prompt: String, system: String?): String {
        calls += system to prompt
        return answers.entries
            .firstOrNull { (keyword, _) -> prompt.contains(keyword, ignoreCase = true) }
            ?.value
            ?: defaultAnswer
    }
}
