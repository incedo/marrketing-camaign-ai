package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun validPlanJson() = """
        {"nodes":[
          {"type":"start","id":"start"},
          {"type":"marketing","id":"llm_m1","channel":"EMAIL","content":"Hello, 10% off just for you","productIds":[],"nextNodeId":"end_won"},
          {"type":"end","id":"end_won","outcome":"converted"}
        ],"edges":[{"from":"start","to":"llm_m1"}]}
    """.trimIndent()

    private fun invalidPlanJson() = """
        {"nodes":[
          {"type":"start","id":"start"},
          {"type":"marketing","id":"m1","channel":"EMAIL","content":"hi","productIds":[],"nextNodeId":"nope"}
        ],"edges":[{"from":"start","to":"m1"}]}
    """.trimIndent()

    private fun request(
        profile: PersonProfile = PersonProfile(id = "p1", name = "Jan", segments = setOf("sports")),
        behavior: MeasuredBehavior = MeasuredBehavior("p1"),
        products: List<Product> = listOf(Product("ball", "Football", ProductType.PHYSICAL)),
    ) = PlanRequest(
        profile = profile,
        behavior = behavior,
        products = products,
        campaign = Campaign("win back dormant customers"),
        nowEpochMillis = now,
    )

    @Test
    fun summarizeComputesRecencyAndCounts() {
        val behavior = MeasuredBehavior(
            "p1", listOf(
                BehaviorEvent("app_open", now - day),
                BehaviorEvent("app_open", now - 2 * day),
                BehaviorEvent("purchase_completed", now - 3 * day, mapOf("amountCents" to "2500")),
                BehaviorEvent("app_open", now - 100 * day),
            )
        )
        val s = summarize(behavior, now)
        assertEquals(1L, s.daysSinceLastActive)
        assertEquals(3L, s.daysSinceLastPurchase)
        assertEquals(2, s.eventCounts30d["app_open"])
        assertEquals(1, s.purchaseCount90d)
        assertEquals(2500L, s.totalSpendCents90d)
        assertEquals(Trend.INCREASING, s.trend)
    }

    @Test
    fun summarizeHandlesEmptyBehavior() {
        val s = summarize(MeasuredBehavior("p1"), now)
        assertEquals(null, s.daysSinceLastActive)
        assertEquals(Trend.UNKNOWN, s.trend)
        assertEquals(false, s.isNewCustomer)
        assertEquals(0.0, propensityScore(s))
    }

    @Test
    fun propensityScoresActiveBuyerHigher() {
        val active = summarize(
            MeasuredBehavior(
                "p1", listOf(
                    BehaviorEvent("purchase_completed", now - day, mapOf("amountCents" to "1000")),
                    BehaviorEvent("purchase_completed", now - 2 * day, mapOf("amountCents" to "1000")),
                    BehaviorEvent("purchase_completed", now - 3 * day, mapOf("amountCents" to "1000")),
                    BehaviorEvent("app_open", now - day),
                    BehaviorEvent("app_open", now - 2 * day),
                )
            ), now
        )
        val dormant = summarize(
            MeasuredBehavior("p1", listOf(BehaviorEvent("app_open", now - 200 * day))), now
        )
        assertTrue(propensityScore(active) > propensityScore(dormant))
        assertTrue(propensityScore(active) >= 0.7)
    }

    @Test
    fun plannerRetriesAfterInvalidPlan() = runTest {
        val llm = DummyLlmClient(
            answers = mapOf("FAILED validation" to validPlanJson()),
            defaultAnswer = invalidPlanJson(),
        )
        val journey = LlmJourneyPlanner(llm, LlmTarget.LOCAL).plan(request())

        assertTrue(journey.validate().isEmpty(), "expected valid journey")
        assertEquals(2, llm.calls.size)
        assertTrue(journey.nodes.containsKey("llm_m1"))
    }

    @Test
    fun plannerFallsBackAfterRepeatedFailures() = runTest {
        val llm = DummyLlmClient(defaultAnswer = invalidPlanJson())
        val journey = LlmJourneyPlanner(llm, LlmTarget.LOCAL, maxAttempts = 2).plan(request())

        // Fallback is the rule-based engine: recognizable by its win-back A/B test.
        val ab = journey.node("ab") as ABTestNode
        assertEquals("winback-discount", ab.name)
        assertTrue(journey.validate().isEmpty())
    }

    @Test
    fun plannerPseudonymizesForCloudTarget() = runTest {
        val profile = PersonProfile(
            id = "p1",
            name = "Kees Janssen",
            attributes = mapOf("email" to "kees@example.com", "city" to "Amsterdam"),
        )
        val cloudLlm = DummyLlmClient(defaultAnswer = validPlanJson())
        LlmJourneyPlanner(cloudLlm, LlmTarget.CLOUD).plan(request(profile = profile))
        for ((_, prompt) in cloudLlm.calls) {
            assertTrue(!prompt.contains("Kees"), "cloud prompt leaked name: $prompt")
            assertTrue(!prompt.contains("kees@example.com"), "cloud prompt leaked email")
        }

        val localLlm = DummyLlmClient(defaultAnswer = validPlanJson())
        LlmJourneyPlanner(localLlm, LlmTarget.LOCAL).plan(request(profile = profile))
        assertTrue(localLlm.calls.any { (_, prompt) -> prompt.contains("Kees Janssen") })
    }

    @Test
    fun routerSendsHighPropensityToPlanner() = runTest {
        val behavior = MeasuredBehavior(
            "p1",
            (1..12).map { BehaviorEvent("app_open", now - it * day) } +
                listOf(
                    BehaviorEvent("purchase_completed", now - day, mapOf("amountCents" to "1000")),
                    BehaviorEvent("purchase_completed", now - 2 * day, mapOf("amountCents" to "1000")),
                    BehaviorEvent("purchase_completed", now - 3 * day, mapOf("amountCents" to "1000")),
                )
        )
        val llm = DummyLlmClient(defaultAnswer = validPlanJson())
        val journey = JourneyRouter(llm, LlmTarget.LOCAL).plan(request(behavior = behavior))
        assertTrue(journey.nodes.containsKey("llm_m1"), "expected LLM-planned journey")
    }

    @Test
    fun routerSendsLowPropensityToRules() = runTest {
        val llm = DummyLlmClient()
        val journey = JourneyRouter(llm, LlmTarget.LOCAL).plan(request())
        val ab = journey.node("ab") as ABTestNode
        assertEquals("winback-discount", ab.name)
    }

    @Test
    fun journeySerializesRoundTrip() = runTest {
        val journey = buildJourney {
            start("start")
            decision("seg", "engaged?", "m1", "end_lost")
            marketing("m1", Channel.PUSH, "Hi!", nextNodeId = "end_won")
            end("end_won", "converted")
            end("end_lost", "lapsed")
            edge("start", "seg")
        }
        val text = Json.encodeToString(Journey.serializer(), journey)
        val back = Json.decodeFromString(Journey.serializer(), text)
        assertEquals(journey, back)
        assertIs<DecisionNode>(back.node("seg"))
    }

    @Test
    fun planRequestSerializesRoundTrip() {
        val req = request()
        val text = Json.encodeToString(PlanRequest.serializer(), req)
        val back = Json.decodeFromString(PlanRequest.serializer(), text)
        assertEquals(req, back)
    }
}
