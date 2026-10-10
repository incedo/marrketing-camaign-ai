package com.intelliworks.journey

/** Minimal LLM abstraction. The engine only depends on this. */
interface LlmClient {
    suspend fun complete(prompt: String, system: String? = null): String
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
