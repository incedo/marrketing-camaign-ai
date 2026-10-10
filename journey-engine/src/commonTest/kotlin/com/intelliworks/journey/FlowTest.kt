package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowTest {

    private val now = 1_700_000_000_000L

    private fun validFlowJson() = """
        {"nodes":[
          {"id":"start","type":"START_TRIGGER","params":{}},
          {"id":"send","type":"SEND_EMAIL","params":{"subject":"Hi"}},
          {"id":"end_won","type":"EXIT_JOURNEY","params":{}}
        ],"edges":[
          {"from":"start","to":"send","slot":"next"},
          {"from":"send","to":"end_won","slot":"next"}
        ]}
    """.trimIndent()

    private fun request() = PlanRequest(
        profile = PersonProfile(id = "p1", name = "Jan"),
        behavior = MeasuredBehavior("p1"),
        products = listOf(Product("ball", "Football", ProductType.PHYSICAL)),
        campaign = Campaign("win back dormant customers"),
        nowEpochMillis = now,
    )

    @Test
    fun nodeTypesTotal60WithSpecs() {
        assertEquals(60, FlowNodeType.entries.size)
        val specs = flowNodeSpecs()
        assertEquals(60, specs.size)
        for (type in FlowNodeType.entries) {
            assertTrue(specs.containsKey(type), "missing spec for $type")
        }
        assertEquals(FlowCategory.COMMUNICATE, FlowNodeType.SEND_WHATSAPP.category())
        assertEquals(FlowCategory.DECIDE, FlowNodeType.ML_DECISION.category())
        assertEquals(FlowCategory.MEASURE, FlowNodeType.ATTRIBUTION.category())
    }

    @Test
    fun validateFindsMissingParam() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "wait" to FlowNode("wait", FlowNodeType.WAIT_UNTIL_EVENT),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "wait", "next"),
                FlowEdge("wait", "end", "event"),
            ),
        )
        val problems = journey.validate()
        assertTrue(problems.any { it.contains("eventType") }, "expected missing-param complaint, got $problems")
    }

    @Test
    fun validateFindsBadSlot() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "dec" to FlowNode("dec", FlowNodeType.IF_ELSE, mapOf("condition" to "x")),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "dec", "next"),
                FlowEdge("dec", "end", "maybe"),
            ),
        )
        val problems = journey.validate()
        assertTrue(problems.any { it.contains("maybe") }, "expected bad-slot complaint, got $problems")
    }

    @Test
    fun validateFindsUnreachableAndDangling() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "orphan" to FlowNode("orphan", FlowNodeType.SEND_EMAIL),
            ),
            edges = listOf(FlowEdge("start", "ghost", "next")),
        )
        val problems = journey.validate()
        assertTrue(problems.any { it.contains("ghost") })
        assertTrue(problems.any { it.contains("orphan") && it.contains("unreachable") })
    }

    @Test
    fun plannerRetriesAndSucceeds() = runTest {
        val invalid = """{"nodes":[{"id":"start","type":"START_TRIGGER","params":{}},{"id":"x","type":"NOPE","params":{}}],"edges":[]}"""
        val llm = DummyLlmClient(
            answers = mapOf("FAILED validation" to validFlowJson()),
            defaultAnswer = invalid,
        )
        val journey = LlmFlowJourneyPlanner(llm, LlmTarget.LOCAL).planFlow(request())
        assertTrue(journey.validate().isEmpty(), "expected valid flow")
        assertEquals(2, llm.calls.size)
        assertEquals(FlowNodeType.SEND_EMAIL, journey.node("send").type)
    }

    @Test
    fun plannerFallsBack() = runTest {
        val llm = DummyLlmClient(defaultAnswer = "not json")
        val journey = LlmFlowJourneyPlanner(llm, LlmTarget.LOCAL, maxAttempts = 2).planFlow(request())
        assertTrue(journey.validate().isEmpty())
        assertEquals(FlowNodeType.SEND_EMAIL, journey.node("send").type)
        assertEquals(FlowNodeType.WAIT_UNTIL_EVENT, journey.node("wait").type)
    }

    @Test
    fun flowJourneySerializesRoundTrip() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "if" to FlowNode("if", FlowNodeType.IF_ELSE, mapOf("condition" to "engaged")),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "if", "next"),
                FlowEdge("if", "end", "true"),
            ),
        )
        val text = Json.encodeToString(FlowJourney.serializer(), journey)
        val back = Json.decodeFromString(FlowJourney.serializer(), text)
        assertEquals(journey, back)
    }

    private fun personalizeJourney(template: String, outputAttribute: String = "subject_line") =
        FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "gen" to FlowNode(
                    "gen", FlowNodeType.PERSONALIZE_CONTENT, mapOf(
                        "promptTemplate" to template,
                        "outputAttribute" to outputAttribute,
                        "timeoutMs" to "1000",
                        "fallbackValue" to "Hi",
                    ),
                ),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "gen", "next"),
                FlowEdge("gen", "end", "next"),
            ),
        )

    @Test
    fun personalizeContentUnknownPlaceholderFails() {
        val problems = personalizeJourney("Hello {{unknown_attr}}").validate()
        assertTrue(problems.any { it.contains("unknown_attr") }, problems.joinToString())
    }

    @Test
    fun personalizeContentKnownAttributePasses() {
        val problems = personalizeJourney(
            "Hello {{segment}}",
            outputAttribute = "subject_line",
        ).validate(knownAttributes = setOf("segment"))
        assertTrue(problems.isEmpty(), problems.joinToString())
    }

    @Test
    fun personalizeContentChainedAttributesValidate() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "gen1" to FlowNode(
                    "gen1", FlowNodeType.PERSONALIZE_CONTENT, mapOf(
                        "promptTemplate" to "Deal: {{segment}}",
                        "outputAttribute" to "subject_line",
                        "timeoutMs" to "1000",
                        "fallbackValue" to "Hi",
                    ),
                ),
                "gen2" to FlowNode(
                    "gen2", FlowNodeType.PERSONALIZE_CONTENT, mapOf(
                        "promptTemplate" to "Preheader for {{subject_line}}",
                        "outputAttribute" to "preheader",
                        "timeoutMs" to "1000",
                        "fallbackValue" to "Hi",
                    ),
                ),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "gen1", "next"),
                FlowEdge("gen1", "gen2", "next"),
                FlowEdge("gen2", "end", "next"),
            ),
        )
        val problems = journey.validate(knownAttributes = setOf("segment"))
        assertTrue(problems.isEmpty(), problems.joinToString())
    }

    @Test
    fun duplicateOutputAttributeFails() {
        val journey = FlowJourney(
            nodes = mapOf(
                "start" to FlowNode("start", FlowNodeType.START_TRIGGER),
                "gen1" to FlowNode(
                    "gen1", FlowNodeType.PERSONALIZE_CONTENT, mapOf(
                        "promptTemplate" to "A",
                        "outputAttribute" to "subject_line",
                        "timeoutMs" to "1000",
                        "fallbackValue" to "Hi",
                    ),
                ),
                "gen2" to FlowNode(
                    "gen2", FlowNodeType.PERSONALIZE_CONTENT, mapOf(
                        "promptTemplate" to "B",
                        "outputAttribute" to "subject_line",
                        "timeoutMs" to "1000",
                        "fallbackValue" to "Hi",
                    ),
                ),
                "end" to FlowNode("end", FlowNodeType.EXIT_JOURNEY),
            ),
            edges = listOf(
                FlowEdge("start", "gen1", "next"),
                FlowEdge("gen1", "gen2", "next"),
                FlowEdge("gen2", "end", "next"),
            ),
        )
        val problems = journey.validate()
        assertTrue(problems.any { it.contains("subject_line") && it.contains("both") }, problems.joinToString())
    }
}
