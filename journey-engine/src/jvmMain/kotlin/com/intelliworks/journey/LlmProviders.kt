package com.intelliworks.journey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 300,
    val temperature: Double = 0.7,
)

@Serializable
private data class ChatChoice(val message: ChatMessage)

@Serializable
private data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice>,
    val usage: ChatUsage? = null,
)

/** Minimal HTTP result for the [HttpPost] transport seam. */
data class HttpResult(val statusCode: Int, val body: String)

/**
 * Transport seam behind the provider clients: posts a JSON body and returns
 * the raw result. Tests inject a fake; production uses [jdkHttpPost].
 */
fun interface HttpPost {
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

/** [HttpPost] backed by the JDK HTTP client. */
fun jdkHttpPost(): HttpPost {
    val http: HttpClient = HttpClient.newHttpClient()
    return HttpPost { url, headers, body ->
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = withContext(Dispatchers.IO) {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        HttpResult(response.statusCode(), response.body())
    }
}

/**
 * Shared implementation for every OpenAI-compatible chat-completions API:
 * OpenAI, Meta (api.llama.com/compat/v1), xAI Grok, Ollama and LM Studio.
 * Pass the key in; never hard-code it. Local providers may pass null.
 */
open class OpenAiCompatLlmClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val providerName: String,
    private val maxTokens: Int = 300,
    private val temperature: Double = 0.7,
    private val httpPost: HttpPost = jdkHttpPost(),
) : LlmClient {

    private val json: Json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(prompt: String, system: String?): String =
        completeWithUsage(prompt, system).first

    override suspend fun completeWithUsage(prompt: String, system: String?): Pair<String, LlmUsage?> {
        val messages = buildList {
            if (system != null) add(ChatMessage("system", system))
            add(ChatMessage("user", prompt))
        }
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model, messages, maxTokens, temperature),
        )
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (apiKey != null) put("Authorization", "Bearer $apiKey")
        }

        val response = httpPost.post("$baseUrl/chat/completions", headers, body)
        require(response.statusCode in 200..299) {
            "$providerName error ${response.statusCode}: ${response.body.take(300)}"
        }
        val parsed = json.decodeFromString(ChatResponse.serializer(), response.body)
        val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        val usage = parsed.usage?.let { LlmUsage(it.promptTokens, it.completionTokens) }
        return text to usage
    }
}

/**
 * Real OpenAI implementation (JVM only, uses the JDK HTTP client — no extra deps).
 * Pass the key in; never hard-code it.
 */
class OpenAiLlmClient(
    apiKey: String,
    model: String = "gpt-4o-mini",
    baseUrl: String = "https://api.openai.com/v1",
    httpPost: HttpPost = jdkHttpPost(),
) : OpenAiCompatLlmClient(baseUrl, apiKey, model, "openai", httpPost = httpPost)

/**
 * Meta's Llama API (https://api.llama.com/compat/v1) — OpenAI-compatible.
 * This is the first provider used for the journey-quality simulations.
 */
class MetaLlmClient(
    apiKey: String,
    model: String = "Llama-4-Maverick-17B-128E-Instruct-FP8",
    baseUrl: String = "https://api.llama.com/compat/v1",
    httpPost: HttpPost = jdkHttpPost(),
) : OpenAiCompatLlmClient(baseUrl, apiKey, model, "meta", httpPost = httpPost)

/** Local Ollama (default http://localhost:11434/v1), OpenAI-compatible. No key needed. */
class OllamaLlmClient(
    model: String = "llama3.1",
    baseUrl: String = "http://localhost:11434/v1",
    apiKey: String? = null,
    httpPost: HttpPost = jdkHttpPost(),
) : OpenAiCompatLlmClient(baseUrl, apiKey, model, "ollama", httpPost = httpPost)

/** Local LM Studio (default http://localhost:1234/v1), OpenAI-compatible. No key needed. */
class LmStudioLlmClient(
    model: String = "local-model",
    baseUrl: String = "http://localhost:1234/v1",
    apiKey: String? = null,
    httpPost: HttpPost = jdkHttpPost(),
) : OpenAiCompatLlmClient(baseUrl, apiKey, model, "lmstudio", httpPost = httpPost)

/** xAI Grok (https://api.x.ai/v1), OpenAI-compatible. */
class GrokLlmClient(
    apiKey: String,
    model: String = "grok-3-mini",
    baseUrl: String = "https://api.x.ai/v1",
    httpPost: HttpPost = jdkHttpPost(),
) : OpenAiCompatLlmClient(baseUrl, apiKey, model, "grok", httpPost = httpPost)

/**
 * Anthropic Claude (https://api.anthropic.com/v1/messages) — its own wire format.
 */
class AnthropicLlmClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-5",
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val maxTokens: Int = 300,
    private val httpPost: HttpPost = jdkHttpPost(),
) : LlmClient {

    @Serializable
    private data class Msg(val role: String, val content: String)

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<Msg>,
    )

    @Serializable
    private data class ContentBlock(val type: String, val text: String = "")

    @Serializable
    private data class MessagesUsage(
        @SerialName("input_tokens") val inputTokens: Int = 0,
        @SerialName("output_tokens") val outputTokens: Int = 0,
    )

    @Serializable
    private data class MessagesResponse(
        val content: List<ContentBlock>,
        val usage: MessagesUsage? = null,
    )

    private val json: Json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(prompt: String, system: String?): String =
        completeWithUsage(prompt, system).first

    override suspend fun completeWithUsage(prompt: String, system: String?): Pair<String, LlmUsage?> {
        val body = json.encodeToString(
            MessagesRequest.serializer(),
            MessagesRequest(model, maxTokens, system, listOf(Msg("user", prompt))),
        )
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json",
        )

        val response = httpPost.post("$baseUrl/messages", headers, body)
        require(response.statusCode in 200..299) {
            "claude error ${response.statusCode}: ${response.body.take(300)}"
        }
        val parsed = json.decodeFromString(MessagesResponse.serializer(), response.body)
        val text = parsed.content
            .filter { it.type == "text" }
            .joinToString("") { it.text }
            .trim()
        val usage = parsed.usage?.let { LlmUsage(it.inputTokens, it.outputTokens) }
        return text to usage
    }
}

private fun requireKey(apiKey: String?, provider: String): String =
    requireNotNull(apiKey) { "No API key provided for LLM provider '$provider'" }

/**
 * Provider factory: switching between Meta, Ollama, LM Studio, OpenAI, Claude
 * and Grok is a configuration choice ([LlmProviderConfig]), never a code change.
 */
fun llmClientFor(provider: String, apiKey: String? = null, model: String? = null): LlmClient =
    when (provider.lowercase()) {
        "meta" -> MetaLlmClient(requireKey(apiKey, "meta"), model ?: "Llama-4-Maverick-17B-128E-Instruct-FP8")
        "ollama" -> OllamaLlmClient(model ?: "llama3.1", apiKey = apiKey)
        "lmstudio" -> LmStudioLlmClient(model ?: "local-model", apiKey = apiKey)
        "openai" -> OpenAiLlmClient(requireKey(apiKey, "openai"), model ?: "gpt-4o-mini")
        "claude", "anthropic" -> AnthropicLlmClient(requireKey(apiKey, "claude"), model ?: "claude-sonnet-4-5")
        "grok", "xai" -> GrokLlmClient(requireKey(apiKey, "grok"), model ?: "grok-3-mini")
        else -> throw IllegalArgumentException(
            "Unknown LLM provider '$provider' (expected meta|ollama|lmstudio|openai|claude|grok)",
        )
    }

/** Configuration for [llmClientFor]; read from env (`LLM_PROVIDER`, `LLM_API_KEY`). */
data class LlmProviderConfig(
    val provider: String = "meta",
    val apiKey: String? = null,
    val model: String? = null,
) {
    fun client(): LlmClient = llmClientFor(provider, apiKey, model)

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): LlmProviderConfig =
            LlmProviderConfig(
                provider = env["LLM_PROVIDER"] ?: "meta",
                apiKey = env["LLM_API_KEY"],
                model = env["LLM_MODEL"],
            )
    }
}
