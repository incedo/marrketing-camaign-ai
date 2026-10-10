package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun input(vararg events: BehaviorEvent) = JourneyInput(
        profile = PersonProfile(id = "p1", name = "Jan", segments = setOf("sports")),
        behavior = MeasuredBehavior(personId = "p1", events = events.toList()),
        products = listOf(
            Product("ball", "Football", ProductType.PHYSICAL, tags = setOf("sports")),
            Product("shoes", "Running shoes", ProductType.PHYSICAL, tags = setOf("sports")),
        ),
        prompt = "win back dormant customers",
        nowEpochMillis = now,
    )

    @Test
    fun engineBuildsValidJourney() = runTest {
        val llm = DummyLlmClient(defaultAnswer = "Hello!")
        val journey = LlmJourneyEngine(llm).plan(input())

        val problems = journey.validate()
        assertTrue(problems.isEmpty(), "expected valid journey, got: $problems")

        assertIs<StartNode>(journey.node("start"))
        assertIs<DecisionNode>(journey.node("seg"))
        assertIs<ABTestNode>(journey.node("ab"))
        assertIs<WaitOnEventNode>(journey.node("wait"))

        val rec = journey.node("rec") as MarketingNode
        assertEquals(listOf("ball", "shoes"), rec.productIds)
        assertEquals("Hello!", rec.content)
        assertTrue(llm.calls.isNotEmpty(), "engine should have asked the LLM for copy")
    }

    @Test
    fun engagedProfileUsesPushChannel() = runTest {
        val llm = DummyLlmClient()
        val journey = LlmJourneyEngine(llm).plan(
            input(BehaviorEvent("app_open", now - day))
        )
        val rec = journey.node("rec") as MarketingNode
        assertEquals(Channel.PUSH, rec.channel)
    }

    @Test
    fun dormantProfileUsesEmailChannel() = runTest {
        val llm = DummyLlmClient()
        val journey = LlmJourneyEngine(llm).plan(
            input(BehaviorEvent("app_open", now - 60 * day))
        )
        val rec = journey.node("rec") as MarketingNode
        assertEquals(Channel.EMAIL, rec.channel)
    }

    @Test
    fun dummyClientMatchesKeywords() = runTest {
        val llm = DummyLlmClient(mapOf("discount" to "SALE!"), defaultAnswer = "hi")
        assertEquals("SALE!", llm.complete("give me a discount code"))
        assertEquals("hi", llm.complete("something else"))
    }

    @Test
    fun journeyValidationFindsDanglingRefs() = runTest {
        val bad = buildJourney {
            start("start")
            marketing("m1", Channel.EMAIL, "hi", nextNodeId = "nope")
            end("end")
            edge("start", "m1")
            edge("m1", "nope")
        }
        val problems = bad.validate()
        assertTrue(
            problems.any { it.contains("nope") },
            "expected dangling-ref complaint, got $problems",
        )
    }
}
