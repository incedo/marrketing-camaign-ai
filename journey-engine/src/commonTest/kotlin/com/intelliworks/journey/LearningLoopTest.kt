package com.intelliworks.journey

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearningLoopTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun outcome(
        archetype: String,
        segment: String = "sports",
        converted: Boolean,
        daysAgo: Long,
        channel: String = "email",
        revenue: Double? = null,
    ) = JourneyOutcome(
        journeyId = "j1",
        archetype = archetype,
        segment = segment,
        nodePath = listOf("start", "send", "end"),
        converted = converted,
        atEpochMillis = now - daysAgo * day,
        channel = channel,
        revenue = revenue,
    )

    @Test
    fun aggregateGroupsAndFlagsThinEvidence() {
        val outcomes = (1..40).map { outcome("a1", converted = it % 2 == 0, daysAgo = 1) } +
            (1..10).map { outcome("a2", converted = true, daysAgo = 1) }

        val rows = aggregateOutcomes(outcomes, nowEpochMillis = now)

        assertEquals(2, rows.size)
        val a1 = rows.single { it.archetype == "a1" }
        assertEquals(40, a1.journeys)
        assertEquals(20, a1.conversions)
        assertEquals(false, a1.insufficientEvidence)
        val a2 = rows.single { it.archetype == "a2" }
        assertTrue(a2.insufficientEvidence, "10 < default minSamples 30")
    }

    @Test
    fun recencyWeightsRecentOutcomesMore() {
        val outcomes = listOf(
            outcome("a1", converted = false, daysAgo = 80),
            outcome("a1", converted = true, daysAgo = 1),
        )

        val rate = aggregateOutcomes(
            outcomes,
            AggregationConfig(minSamples = 2),
            nowEpochMillis = now,
        ).single().conversionRate

        assertTrue(rate > 0.5, "recent conversion should dominate, was $rate")
    }

    @Test
    fun aggregateIsIdempotent() {
        val outcomes = (1..50).map { outcome("a1", converted = it % 3 == 0, daysAgo = (it % 20).toLong()) }
        val first = aggregateOutcomes(outcomes, nowEpochMillis = now)
        val second = aggregateOutcomes(outcomes, nowEpochMillis = now)
        assertEquals(first, second)
    }

    @Test
    fun aggregateSplitsByChannel() {
        val outcomes = (1..35).map { outcome("a1", converted = true, daysAgo = 1, channel = "email") } +
            (1..35).map { outcome("a1", converted = false, daysAgo = 1, channel = "push") }

        val rows = aggregateOutcomes(outcomes, nowEpochMillis = now)

        assertEquals(2, rows.size)
        assertEquals(1.0, rows.single { it.channel == "email" }.conversionRate, 0.001)
        assertEquals(0.0, rows.single { it.channel == "push" }.conversionRate, 0.001)
    }

    private fun candidates() = listOf(
        ArchetypePerformance("a1", "sports", 100, 0.2, revenuePerJourney = 1.0),
        ArchetypePerformance("a2", "sports", 100, 0.5, revenuePerJourney = 5.0),
    )

    @Test
    fun banditColdStartBehavesLikeStaticThreshold() {
        val policy = EpsilonGreedyArbitration()
        val config = BanditConfig(epsilon = 0.0)

        assertEquals(
            ArbitrationSelection.LlmPlanner("default"),
            policy.select(emptyList(), 0.9, 0.7, config, Random(42)),
        )
        assertEquals(
            ArbitrationSelection.RuleEngine,
            policy.select(emptyList(), 0.3, 0.7, config, Random(42)),
        )
    }

    @Test
    fun banditExploitsBestEvidence() {
        val policy = EpsilonGreedyArbitration()
        val sel = policy.select(candidates(), 0.9, 0.7, BanditConfig(epsilon = 0.0), Random(42))
        assertEquals(ArbitrationSelection.LlmPlanner("a2"), sel)
    }

    @Test
    fun banditSkipsThinEvidence() {
        val policy = EpsilonGreedyArbitration()
        val thin = candidates().map { it.copy(insufficientEvidence = true) }
        val sel = policy.select(thin, 0.9, 0.7, BanditConfig(epsilon = 0.0), Random(42))
        assertEquals(ArbitrationSelection.LlmPlanner("default"), sel)
    }

    @Test
    fun banditExploresWithinEligible() {
        val policy = EpsilonGreedyArbitration()
        val sel = policy.select(candidates(), 0.9, 0.7, BanditConfig(epsilon = 1.0), Random(7))
        assertTrue(
            sel is ArbitrationSelection.LlmPlanner && sel.archetype in setOf("a1", "a2"),
            "epsilon=1 must explore, got $sel",
        )
    }

    @Test
    fun learningSubscriberStoresAndEmitsEvidence() = runTest {
        val bus = InMemoryEventBus()
        val store = InMemoryOutcomeStore()
        var evidence: List<ArchetypePerformance> = emptyList()
        LearningSubscriber(bus, store, AggregationConfig(minSamples = 1)) { evidence = it }.start()

        bus.publish(
            JourneyEnded(
                atEpochMillis = now,
                runId = "r1",
                outcome = outcome("a1", converted = true, daysAgo = 0),
            ),
        )

        assertEquals(1, store.read(0).size)
        assertEquals(1, evidence.size)
        assertEquals("a1", evidence.single().archetype)
        assertEquals(1, evidence.single().journeys)
    }
}
