package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowAdviceTest {

    private val now = 1_700_000_000_000L

    private fun personalizeJourney() = FlowJourney(
        nodes = mapOf(
            "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
            "gen" to FlowNode(
                "gen", FlowNodeType.PERSONALIZE_CONTENT, mapOf(
                    "promptTemplate" to "Subject for {{segment}} deals",
                    "outputAttribute" to "subject_line",
                    "timeoutMs" to "1000",
                    "fallbackValue" to "Deals for you",
                ),
            ),
            "send" to FlowNode("send", FlowNodeType.SEND_EMAIL, mapOf("content" to "Hello")),
            "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
        ),
        edges = listOf(
            FlowEdge("start", "gen", "next"),
            FlowEdge("gen", "send", "next"),
            FlowEdge("send", "end", "next"),
        ),
    )

    private fun arbiterJourney() = FlowJourney(
        nodes = mapOf(
            "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
            "arb" to FlowNode("arb", FlowNodeType.ML_DECISION, mapOf("goalRef" to "g1")),
            "send_email2" to FlowNode("send_email2", FlowNodeType.SEND_EMAIL, mapOf("content" to "x")),
            "send_wa" to FlowNode("send_wa", FlowNodeType.SEND_WHATSAPP, mapOf("content" to "x")),
            "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
        ),
        edges = listOf(
            FlowEdge("start", "arb", "next"),
            FlowEdge("arb", "send_email2", "next"),
            FlowEdge("arb", "send_wa", "next"),
            FlowEdge("arb", "end", "next"),
        ),
    )

    private fun request(
        journey: FlowJourney,
        nodeId: String,
        urgency: AdviceUrgency,
        runEvents: List<RunEvent> = emptyList(),
        goal: JourneyGoal? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = AdviceRequested(
        atEpochMillis = now,
        runId = "run1",
        journeyId = "j1",
        nodeId = nodeId,
        journey = journey,
        profile = PersonProfile(id = "p1", name = "Jan", segments = setOf("sports")),
        behavior = MeasuredBehavior("p1"),
        channel = Channel.EMAIL,
        urgency = urgency,
        runEvents = runEvents,
        goal = goal,
        attributes = attributes,
    )

    private fun sent(nodeId: String, channel: String, at: Long) =
        RunEvent(nodeId, "sent", channel, at)

    @Test
    fun realtimeUsesFallbackWithoutCallingLlm() = runTest {
        val llm = DummyLlmClient(defaultAnswer = """{"value":"LLM subject"}""")
        val service = FlowAdviceService(llm, LlmTarget.LOCAL)

        val res = service.advise(request(personalizeJourney(), "gen", AdviceUrgency.REALTIME))

        assertEquals("Deals for you", res.attributeValue)
        assertEquals("subject_line", res.attributeName)
        assertEquals(AdviceSource.RULES, res.servedBy)
        assertEquals(0, llm.calls.size, "realtime must not call the LLM")
    }

    @Test
    fun warmupThenRealtimeHitsCache() = runTest {
        val llm = DummyLlmClient(defaultAnswer = """{"value":"LLM subject"}""")
        val service = FlowAdviceService(llm, LlmTarget.LOCAL)

        service.warmup(request(personalizeJourney(), "gen", AdviceUrgency.BACKGROUND))
        assertEquals(1, llm.calls.size)

        val res = service.advise(request(personalizeJourney(), "gen", AdviceUrgency.REALTIME))
        assertEquals(AdviceSource.CACHE, res.servedBy)
        assertEquals("LLM subject", res.attributeValue)
        assertEquals(1, llm.calls.size, "realtime must be served from cache")
    }

    @Test
    fun interactiveUsesLlmForActionNode() = runTest {
        val llm = DummyLlmClient(defaultAnswer = """{"value":"Fresh deals"}""")
        val service = FlowAdviceService(llm, LlmTarget.LOCAL)

        val res = service.advise(request(personalizeJourney(), "gen", AdviceUrgency.INTERACTIVE))

        assertEquals(AdviceSource.LLM, res.servedBy)
        assertEquals("Fresh deals", res.attributeValue)
    }

    @Test
    fun cloudFiltersPiiAttributesFromPrompt() = runTest {
        val llm = DummyLlmClient(defaultAnswer = """{"value":"x"}""")
        val service = FlowAdviceService(llm, LlmTarget.CLOUD)

        service.advise(
            request(
                personalizeJourney(), "gen", AdviceUrgency.INTERACTIVE,
                attributes = mapOf("email" to "jan@example.com", "segment" to "sports"),
            ),
        )

        val prompt = llm.calls.single().second
        assertFalse(prompt.contains("jan@example.com"), "PII must not reach a cloud LLM")
        assertTrue(prompt.contains("sports"))
    }

    @Test
    fun goalArbiterSwitchesChannelAfterUnopenedTouches() = runTest {
        val service = FlowAdviceService(DummyLlmClient(), LlmTarget.LOCAL)
        val events = listOf(
            sent("send1", "email", now - 3000),
            sent("send1", "email", now - 2000),
        )

        val res = service.advise(
            request(arbiterJourney(), "arb", AdviceUrgency.REALTIME,
                runEvents = events, goal = JourneyGoal("g1", "purchase")),
        )

        assertEquals("send_wa", res.recommendedNodeId)
        assertEquals(AdviceSource.RULES, res.servedBy)
        assertTrue(res.reasoning.contains("switching channel"))
    }

    @Test
    fun goalAchievedEndsJourney() = runTest {
        val service = FlowAdviceService(DummyLlmClient(), LlmTarget.LOCAL)
        val events = listOf(
            sent("send1", "email", now - 3000),
            RunEvent("send1", "converted", "email", now - 1000),
        )

        val res = service.advise(
            request(arbiterJourney(), "arb", AdviceUrgency.REALTIME,
                runEvents = events, goal = JourneyGoal("g1", "purchase")),
        )

        assertTrue(res.goalAchieved)
        assertEquals("end", res.recommendedNodeId)
    }

    @Test
    fun unknownNodeFallsBackToRules() = runTest {
        val service = FlowAdviceService(DummyLlmClient(), LlmTarget.LOCAL)
        val res = service.advise(request(personalizeJourney(), "nope", AdviceUrgency.REALTIME))
        assertEquals(AdviceSource.RULES, res.servedBy)
    }

    @Test
    fun subscriberPublishesResolvedAndAttributeWritten() = runTest {
        val bus = InMemoryEventBus()
        val service = FlowAdviceService(
            DummyLlmClient(defaultAnswer = """{"value":"Hi there"}"""),
            LlmTarget.LOCAL,
        )
        AdviceSubscriber(bus, service).start()

        bus.publish(request(personalizeJourney(), "gen", AdviceUrgency.INTERACTIVE))

        val resolved = bus.published.filterIsInstance<AdviceResolved>().single()
        assertEquals("Hi there", resolved.attributeValue)
        val written = bus.published.filterIsInstance<AttributeWritten>().single()
        assertEquals("subject_line", written.attribute)
        assertEquals("Hi there", written.value)
        assertEquals("run1", written.runId)
    }

    @Test
    fun subscriberPublishesGoalAchieved() = runTest {
        val bus = InMemoryEventBus()
        val service = FlowAdviceService(DummyLlmClient(), LlmTarget.LOCAL)
        AdviceSubscriber(bus, service).start()

        bus.publish(
            request(
                arbiterJourney(), "arb", AdviceUrgency.REALTIME,
                runEvents = listOf(RunEvent("send1", "converted", "email", now - 1000)),
                goal = JourneyGoal("g1", "purchase"),
            ),
        )

        val achieved = bus.published.filterIsInstance<GoalAchieved>().single()
        assertEquals("g1", achieved.goalId)
        assertEquals("run1", achieved.runId)
    }

    @Test
    fun warmupSubscriberWarmsCacheForAdaptiveNodes() = runTest {
        val bus = InMemoryEventBus()
        val service = FlowAdviceService(
            DummyLlmClient(defaultAnswer = """{"value":"Warm subject"}"""),
            LlmTarget.LOCAL,
        )
        WarmupSubscriber(bus, service).start()

        bus.publish(
            JourneyPlanned(
                atEpochMillis = now,
                journeyId = "j1",
                journey = personalizeJourney(),
                goal = JourneyGoal("g1", "purchase"),
            ),
        )

        val res = service.advise(request(personalizeJourney(), "gen", AdviceUrgency.REALTIME))
        assertEquals(AdviceSource.CACHE, res.servedBy)
        assertEquals("Warm subject", res.attributeValue)
    }
}
