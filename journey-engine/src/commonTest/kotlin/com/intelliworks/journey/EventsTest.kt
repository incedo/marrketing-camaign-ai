package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsTest {

    private val now = 1_700_000_000_000L
    private val json = Json

    private fun flowJson() = """
        {
          "nodes": [
            {"id": "start", "type": "START_TRIGGER", "params": {}},
            {"id": "send", "type": "SEND_EMAIL", "params": {"subject": "Hi", "content": "Hello"}},
            {"id": "end", "type": "EXIT_JOURNEY", "params": {}}
          ],
          "edges": [
            {"from": "start", "to": "send", "slot": "next"},
            {"from": "send", "to": "end", "slot": "next"}
          ]
        }
    """.trimIndent()

    private fun planRequest() = PlanRequest(
        profile = PersonProfile(id = "p1", name = "Jan", segments = setOf("sports")),
        behavior = MeasuredBehavior("p1"),
        products = listOf(Product("ball", "Football", ProductType.PHYSICAL)),
        campaign = Campaign("win back dormant customers"),
        nowEpochMillis = now,
    )

    @Test
    fun eventsSerializeRoundTrip() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "send" to FlowNode("send", FlowNodeType.SEND_EMAIL, mapOf("content" to "Hello")),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "send", "next"),
                FlowEdge("send", "end", "next"),
            ),
        )
        val events: List<JourneyEvent> = listOf(
            JourneyPlanned(atEpochMillis = now, journeyId = "j1", journey = journey,
                maker = JourneyMaker("kmp-journey-engine", JourneyMakerKind.AI_PLANNER),
                goal = JourneyGoal("g1", "purchase", 0.08)),
            NodeEntered(atEpochMillis = now, runId = "r1", nodeId = "send"),
            AdviceRequested(atEpochMillis = now, runId = "r1", journeyId = "j1", nodeId = "send",
                journey = journey, profile = PersonProfile(id = "p1"),
                behavior = MeasuredBehavior("p1"), channel = Channel.EMAIL,
                urgency = AdviceUrgency.REALTIME),
            AdviceResolved(atEpochMillis = now, runId = "r1", nodeId = "send",
                recommendedNodeId = "end", reasoning = "ok", servedBy = AdviceSource.RULES),
            AttributeWritten(atEpochMillis = now, runId = "r1", attribute = "subject", value = "Hi"),
            RunEventOccurred(atEpochMillis = now, runId = "r1",
                runEvent = RunEvent("send", "opened", "email", now)),
            GoalAchieved(atEpochMillis = now, runId = "r1", goalId = "g1", metric = "purchase", value = 1.0),
            JourneyEnded(atEpochMillis = now, runId = "r1",
                outcome = JourneyOutcome("j1", "a1", "sports", listOf("start"), true, now)),
        )
        for (event in events) {
            val text = json.encodeToString(JourneyEvent.serializer(), event)
            val back = json.decodeFromString(JourneyEvent.serializer(), text)
            assertEquals(event, back, "round trip failed for ${event::class.simpleName}")
        }
    }

    @Test
    fun busDispatchesInOrderAndSupportsCancel() = runTest {
        val bus = InMemoryEventBus()
        val seen = mutableListOf<String>()
        val sub = bus.subscribe(NodeEntered::class) { seen += it.nodeId }
        bus.subscribe(NodeEntered::class) { seen += "second:${it.nodeId}" }
        bus.publish(NodeEntered(atEpochMillis = now, runId = "r1", nodeId = "n1"))
        // Other event types do not trigger NodeEntered handlers.
        bus.publish(AttributeWritten(atEpochMillis = now, runId = "r1", attribute = "a", value = "v"))
        assertEquals(listOf("n1", "second:n1"), seen)
        sub.cancel()
        bus.publish(NodeEntered(atEpochMillis = now, runId = "r1", nodeId = "n2"))
        assertEquals(listOf("n1", "second:n1", "second:n2"), seen)
        assertEquals(3, bus.published.size)
    }

    @Test
    fun plannerPublishesJourneyPlanned() = runTest {
        val bus = InMemoryEventBus()
        val planner = LlmFlowJourneyPlanner(
            DummyLlmClient(defaultAnswer = flowJson()),
            LlmTarget.LOCAL,
            eventBus = bus,
        )
        val journey = planner.planFlow(planRequest())
        assertTrue(journey.validate().isEmpty())

        val planned = bus.published.filterIsInstance<JourneyPlanned>().single()
        assertEquals(journey, planned.journey)
        assertEquals("win back dormant customers", planned.goal?.metric)
        assertEquals("kmp-journey-engine", planned.maker.id)
        assertEquals(JourneyMakerKind.AI_PLANNER, planned.maker.kind)
        assertTrue(planned.planReasoning.isNotBlank())
    }

    @Test
    fun plannerWithoutBusStillPlans() = runTest {
        val planner = LlmFlowJourneyPlanner(DummyLlmClient(defaultAnswer = flowJson()), LlmTarget.LOCAL)
        val journey = planner.planFlow(planRequest())
        assertTrue(journey.validate().isEmpty())
    }
}
