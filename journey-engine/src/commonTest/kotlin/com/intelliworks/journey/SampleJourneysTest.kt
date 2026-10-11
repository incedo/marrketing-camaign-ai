package com.intelliworks.journey

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleJourneysTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun allSamplesValidateClean() {
        for ((name, journey) in SampleJourneys.all()) {
            val problems = journey.validate(knownAttributes = SampleJourneys.knownAttributes)
            assertTrue(problems.isEmpty(), "$name has problems: $problems")
        }
    }

    @Test
    fun allSamplesSerializeRoundTrip() {
        for ((name, journey) in SampleJourneys.all()) {
            val text = json.encodeToString(journey)
            assertTrue(text.contains("\"type\""), "$name JSON looks empty")
            val back = json.decodeFromString<FlowJourney>(text)
            assertEquals(journey, back, "$name did not round-trip")
        }
    }
}
