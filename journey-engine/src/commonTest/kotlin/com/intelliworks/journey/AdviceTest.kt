package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdviceTest {

    private val now = 1_700_000_000_000L

    private fun adviceJson(id: String) =
        """{"recommendedId":"$id","confidence":0.9,"reasoning":"best fit","content":null}"""

    private suspend fun fixtureJourney() = buildJourney {
        start("start")
        marketing("m1", Channel.EMAIL, "Sports deal", productIds = listOf("ball"), nextNodeId = "end_won")
        marketing("m2", Channel.EMAIL, "Fashion deal", productIds = listOf("shoes"), nextNodeId = "end_won")
        end("end_won", "converted")
        edge("start", "m1")
        edge("start", "m2")
    }

    private val products = listOf(
        Product("ball", "Football", ProductType.PHYSICAL, tags = setOf("sports")),
        Product("shoes", "Sneakers", ProductType.PHYSICAL, tags = setOf("fashion")),
    )

    private suspend fun request(
        urgency: AdviceUrgency,
        profile: PersonProfile = PersonProfile(id = "p1", name = "Jan", segments = setOf("fashion")),
    ) = AdviceRequest(
        personId = "p1",
        profile = profile,
        journeyId = "j1",
        journey = fixtureJourney(),
        currentNodeId = "start",
        behavior = MeasuredBehavior("p1"),
        products = products,
        channel = Channel.IN_APP,
        urgency = urgency,
        candidates = listOf("m1", "m2"),
        nowEpochMillis = now,
    )

    @Test
    fun realtimeServesFromCacheWithoutCallingLlm() = runTest {
        val llm = DummyLlmClient(defaultAnswer = adviceJson("m2"))
        val service = TieredAdviceService(llm, LlmTarget.LOCAL)

        val warmed = service.warmup(request(AdviceUrgency.BACKGROUND))
        assertEquals("m2", warmed.recommendedNodeId)
        assertEquals(1, llm.calls.size)

        val fast = service.advise(request(AdviceUrgency.REALTIME))
        assertEquals(AdviceSource.CACHE, fast.servedBy)
        assertEquals("m2", fast.recommendedNodeId)
        assertEquals(1, llm.calls.size, "realtime must not call the LLM")
        assertTrue(fast.latencyMs >= 0)
    }

    @Test
    fun realtimeCacheMissUsesRules() = runTest {
        val llm = DummyLlmClient(defaultAnswer = adviceJson("m1"))
        val service = TieredAdviceService(llm, LlmTarget.LOCAL)

        val response = service.advise(request(AdviceUrgency.REALTIME))
        assertEquals(AdviceSource.RULES, response.servedBy)
        // Profile likes fashion -> shoes (m2) wins on tag overlap.
        assertEquals("m2", response.recommendedNodeId)
        assertTrue(llm.calls.isEmpty(), "realtime must never call the LLM inline")
    }

    @Test
    fun backgroundUsesLlm() = runTest {
        val llm = DummyLlmClient(defaultAnswer = adviceJson("m1"))
        val service = TieredAdviceService(llm, LlmTarget.LOCAL)

        val response = service.advise(request(AdviceUrgency.BACKGROUND))
        assertEquals(AdviceSource.LLM, response.servedBy)
        assertEquals("m1", response.recommendedNodeId)
        assertEquals(0.9, response.confidence)
    }

    @Test
    fun invalidLlmFallsBackToRules() = runTest {
        val llm = DummyLlmClient(defaultAnswer = "nonsense, no json here")
        val service = TieredAdviceService(llm, LlmTarget.LOCAL)

        val response = service.advise(request(AdviceUrgency.BACKGROUND))
        assertEquals(AdviceSource.RULES, response.servedBy)
        assertTrue(response.recommendedNodeId in listOf("m1", "m2"))
    }

    @Test
    fun unknownCandidateFallsBackToRules() = runTest {
        val llm = DummyLlmClient(defaultAnswer = adviceJson("zzz"))
        val service = TieredAdviceService(llm, LlmTarget.LOCAL)

        val response = service.advise(request(AdviceUrgency.BACKGROUND))
        assertEquals(AdviceSource.RULES, response.servedBy)
        assertTrue(response.recommendedNodeId in listOf("m1", "m2"))
    }

    @Test
    fun interactiveUsesLlmWhenFast() = runTest {
        val llm = DummyLlmClient(defaultAnswer = adviceJson("m2"))
        val service = TieredAdviceService(llm, LlmTarget.LOCAL)

        val response = service.advise(request(AdviceUrgency.INTERACTIVE))
        assertEquals(AdviceSource.LLM, response.servedBy)
        assertEquals("m2", response.recommendedNodeId)
    }

    @Test
    fun cloudPseudonymizesAdvicePrompt() = runTest {
        val profile = PersonProfile(
            id = "p1",
            name = "Kees Janssen",
            attributes = mapOf("email" to "kees@example.com"),
            segments = setOf("fashion"),
        )
        val llm = DummyLlmClient(defaultAnswer = adviceJson("m1"))
        val service = TieredAdviceService(llm, LlmTarget.CLOUD)

        service.advise(request(AdviceUrgency.BACKGROUND, profile = profile))
        for ((_, prompt) in llm.calls) {
            assertTrue(!prompt.contains("Kees"), "cloud prompt leaked name")
            assertTrue(!prompt.contains("kees@example.com"), "cloud prompt leaked email")
        }
    }
}
