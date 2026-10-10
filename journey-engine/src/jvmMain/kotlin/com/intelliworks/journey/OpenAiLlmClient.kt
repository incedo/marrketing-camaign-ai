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
private data class ChatResponse(val choices: List<ChatChoice>)

/**
 * Real OpenAI implementation (JVM only, uses the JDK HTTP client — no extra deps).
 * Pass the key in; never hard-code it.
 */
class OpenAiLlmClient(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
    private val baseUrl: String = "https://api.openai.com/v1",
) : LlmClient {

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json: Json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(prompt: String, system: String?): String {
        val messages = buildList {
            if (system != null) add(ChatMessage("system", system))
            add(ChatMessage("user", prompt))
        }
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = withContext(Dispatchers.IO) {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        }
        require(response.statusCode() in 200..299) {
            "OpenAI error ${response.statusCode()}: ${response.body().take(300)}"
        }
        val parsed = json.decodeFromString(ChatResponse.serializer(), response.body())
        return parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }
}
