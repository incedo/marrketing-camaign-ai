package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies every provider client against a fake [HttpPost] transport: request
 * shape (URL, auth headers, JSON body) and response parsing (text + usage).
 * No real API keys, servers or network calls.
 */
class LlmProvidersTest {

    private class FakeTransport(
        private val responseJson: String,
        private val statusCode: Int = 200,
    ) : HttpPost {
        data class Seen(val url: String, val headers: Map<String, String>, val body: String)

        val seen = mutableListOf<Seen>()

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            seen += Seen(url, headers, body)
            return HttpResult(statusCode, responseJson)
        }
    }

    private fun openAiResponse(text: String, promptTokens: Int = 0, completionTokens: Int = 0) =
        """{"choices":[{"message":{"role":"assistant","content":"  $text  "}}],
            "usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens}}"""

    @Test
    fun metaClientUsesCompatEndpointAndParsesUsage() = runTest {
        val fake = FakeTransport(openAiResponse("hello meta", 10, 5))
        val client = MetaLlmClient(apiKey = "test-key", httpPost = fake)

        val (text, usage) = client.completeWithUsage("hi", "sys")

        assertEquals("hello meta", text)
        assertEquals(LlmUsage(10, 5), usage)

        val req = fake.seen.single()
        assertEquals("https://api.llama.com/compat/v1/chat/completions", req.url)
        assertEquals("Bearer test-key", req.headers["Authorization"])
        assertTrue(req.body.contains("Llama-4-Maverick-17B-128E-Instruct-FP8"), req.body)
        assertTrue(req.body.contains("\"role\":\"system\""), req.body)
    }

    @Test
    fun openAiClientKeepsWorking() = runTest {
        val fake = FakeTransport(openAiResponse("hi"))
        val client = OpenAiLlmClient(apiKey = "k", httpPost = fake)

        assertEquals("hi", client.complete("hello"))
        assertEquals("https://api.openai.com/v1/chat/completions", fake.seen.single().url)
    }

    @Test
    fun ollamaClientSendsNoAuthHeaderByDefault() = runTest {
        val fake = FakeTransport(openAiResponse("local"))
        val client = OllamaLlmClient(model = "llama3.1", httpPost = fake)

        assertEquals("local", client.complete("hi"))
        val req = fake.seen.single()
        assertNull(req.headers["Authorization"])
        assertTrue(req.body.contains("llama3.1"), req.body)
    }

    @Test
    fun claudeClientUsesMessagesApi() = runTest {
        val fake = FakeTransport(
            """{"content":[{"type":"text","text":"hello claude"}],
                "usage":{"input_tokens":7,"output_tokens":3}}""",
        )
        val client = AnthropicLlmClient(apiKey = "sk-ant", httpPost = fake)

        val (text, usage) = client.completeWithUsage("hi", "be nice")

        assertEquals("hello claude", text)
        assertEquals(LlmUsage(7, 3), usage)

        val req = fake.seen.single()
        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertEquals("sk-ant", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertTrue(req.body.contains("\"system\":\"be nice\""), req.body)
        assertTrue(req.body.contains("claude-sonnet-4-5"), req.body)
    }

    @Test
    fun grokClientTargetsXai() = runTest {
        val fake = FakeTransport(openAiResponse("grok says hi"))
        val client = GrokLlmClient(apiKey = "xai-key", httpPost = fake)

        assertEquals("grok says hi", client.complete("hi"))
        val req = fake.seen.single()
        assertEquals("https://api.x.ai/v1/chat/completions", req.url)
        assertEquals("Bearer xai-key", req.headers["Authorization"])
    }

    @Test
    fun freeProvidersHitTheirOwnEndpoints() = runTest {
        data class Case(val make: (HttpPost) -> LlmClient, val expectedUrl: String, val name: String)

        val cases = listOf(
            Case({ OpenRouterLlmClient("k", httpPost = it) }, "https://openrouter.ai/api/v1/chat/completions", "openrouter"),
            Case({ GroqLlmClient("k", httpPost = it) }, "https://api.groq.com/openai/v1/chat/completions", "groq"),
            Case({ CerebrasLlmClient("k", httpPost = it) }, "https://api.cerebras.ai/v1/chat/completions", "cerebras"),
            Case({ GeminiLlmClient("k", httpPost = it) }, "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini"),
            Case({ MistralLlmClient("k", httpPost = it) }, "https://api.mistral.ai/v1/chat/completions", "mistral"),
            Case({ NvidiaLlmClient("k", httpPost = it) }, "https://integrate.api.nvidia.com/v1/chat/completions", "nvidia"),
        )
        for ((make, expectedUrl, name) in cases) {
            val probe = FakeTransport(openAiResponse("ok"))
            val client = make(probe)
            assertEquals("ok", client.complete("hi"), name)
            val req = probe.seen.single()
            assertEquals(expectedUrl, req.url, name)
            assertEquals("Bearer k", req.headers["Authorization"], name)
        }
    }

    @Test
    fun errorStatusThrows() = runTest {
        val fake = FakeTransport("""{"error":"nope"}""", statusCode = 401)
        val client = MetaLlmClient(apiKey = "bad", httpPost = fake)

        val ex = assertFailsWith<IllegalArgumentException> { client.complete("hi") }
        assertTrue(ex.message!!.contains("meta error 401"), ex.message)
    }

    @Test
    fun factoryReturnsRightClients() {
        assertIs<MetaLlmClient>(llmClientFor("meta", apiKey = "k"))
        assertIs<OllamaLlmClient>(llmClientFor("ollama"))
        assertIs<LmStudioLlmClient>(llmClientFor("lmstudio"))
        assertIs<OpenAiLlmClient>(llmClientFor("openai", apiKey = "k"))
        assertIs<AnthropicLlmClient>(llmClientFor("claude", apiKey = "k"))
        assertIs<AnthropicLlmClient>(llmClientFor("anthropic", apiKey = "k"))
        assertIs<GrokLlmClient>(llmClientFor("grok", apiKey = "k"))
        assertIs<OpenRouterLlmClient>(llmClientFor("openrouter", apiKey = "k"))
        assertIs<GroqLlmClient>(llmClientFor("groq", apiKey = "k"))
        assertIs<CerebrasLlmClient>(llmClientFor("cerebras", apiKey = "k"))
        assertIs<GeminiLlmClient>(llmClientFor("gemini", apiKey = "k"))
        assertIs<MistralLlmClient>(llmClientFor("mistral", apiKey = "k"))
        assertIs<NvidiaLlmClient>(llmClientFor("nvidia", apiKey = "k"))
    }

    @Test
    fun factoryRejectsUnknownProvider() {
        assertFailsWith<IllegalArgumentException> { llmClientFor("watson", apiKey = "k") }
    }

    @Test
    fun factoryRequiresKeyForCloudProviders() {
        assertFailsWith<IllegalArgumentException> { llmClientFor("meta") }
        assertFailsWith<IllegalArgumentException> { llmClientFor("claude") }
        // local providers are fine without a key
        llmClientFor("ollama")
        llmClientFor("lmstudio")
    }

    @Test
    fun configFromEnv() {
        val config = LlmProviderConfig.fromEnv(
            mapOf("LLM_PROVIDER" to "grok", "LLM_API_KEY" to "k", "LLM_MODEL" to "grok-4"),
        )
        assertEquals("grok", config.provider)
        assertIs<GrokLlmClient>(config.client())
        assertEquals("meta", LlmProviderConfig.fromEnv(emptyMap()).provider)
    }
}
