package com.intelliworks.journey

import kotlin.math.pow
import kotlin.random.Random

/**
 * The across-run learning loop (learning-loop-spec.md): outcomes in,
 * evidence out. Everything here is pure logic or a port — the runtime owns
 * storage, scheduling, and execution.
 */

/** Append-only outcome log. The runtime implements storage; we define the port. */
interface OutcomeStore {
    suspend fun append(outcome: JourneyOutcome)
    suspend fun read(sinceEpochMillis: Long): List<JourneyOutcome>
}

class InMemoryOutcomeStore : OutcomeStore {
    private val rows = mutableListOf<JourneyOutcome>()
    override suspend fun append(outcome: JourneyOutcome) {
        rows += outcome
    }
    override suspend fun read(sinceEpochMillis: Long): List<JourneyOutcome> =
        rows.filter { it.atEpochMillis >= sinceEpochMillis }.toList()
}

data class AggregationConfig(
    val minSamples: Int = 30,
    val recencyHalfLifeDays: Int = 45,
    val windowDays: Int = 90,
)

private const val MS_PER_DAY = 86_400_000L

/**
 * Pure aggregation: folds outcomes into per-(segment, productType, channel,
 * archetype) evidence rows. Idempotent — re-running over the same window
 * yields identical rows. Output contains counts and rates only, never PII.
 */
fun aggregateOutcomes(
    outcomes: List<JourneyOutcome>,
    config: AggregationConfig = AggregationConfig(),
    nowEpochMillis: Long,
): List<ArchetypePerformance> {
    val cutoff = nowEpochMillis - config.windowDays * MS_PER_DAY
    val halfLifeMs = config.recencyHalfLifeDays * MS_PER_DAY.toDouble()
    return outcomes
        .filter { it.atEpochMillis >= cutoff }
        .groupBy {
            listOf(it.segment, it.productType ?: "", it.channel ?: "", it.archetype)
        }
        .map { (key, rows) ->
            var wJourneys = 0.0
            var wConversions = 0.0
            var wRevenue = 0.0
            for (r in rows) {
                val w = 0.5.pow((nowEpochMillis - r.atEpochMillis).toDouble() / halfLifeMs)
                wJourneys += w
                if (r.converted) wConversions += w
                wRevenue += (r.revenue ?: 0.0) * w
            }
            val rate = if (wJourneys > 0) wConversions / wJourneys else 0.0
            ArchetypePerformance(
                archetype = key[3],
                segment = key[0],
                journeys = rows.size,
                conversionRate = rate,
                channel = key[2],
                productType = key[1],
                conversions = rows.count { it.converted },
                revenueTotal = wRevenue,
                revenuePerJourney = if (wJourneys > 0) wRevenue / wJourneys else 0.0,
                windowDays = config.windowDays,
                updatedEpochMillis = nowEpochMillis,
                insufficientEvidence = rows.size < config.minSamples,
            )
        }
}

data class BanditConfig(
    val epsilon: Double = 0.1,
    /** Multiplied into epsilon after each aggregation run. */
    val epsilonDecay: Double = 0.999,
)

/** Where a planning request goes: the LLM planner (with an archetype) or the rule engine. */
sealed interface ArbitrationSelection {
    data class LlmPlanner(val archetype: String) : ArbitrationSelection
    data object RuleEngine : ArbitrationSelection
}

/**
 * Contextual bandit over (archetype × channel) evidence. Cold start — no
 * sufficient evidence — behaves exactly like today's static threshold router.
 */
interface ArbitrationPolicy {
    fun select(
        candidates: List<ArchetypePerformance>,
        propensity: Double,
        propensityThreshold: Double,
        config: BanditConfig,
        random: Random = Random.Default,
    ): ArbitrationSelection
}

class EpsilonGreedyArbitration : ArbitrationPolicy {
    override fun select(
        candidates: List<ArchetypePerformance>,
        propensity: Double,
        propensityThreshold: Double,
        config: BanditConfig,
        random: Random,
    ): ArbitrationSelection {
        if (propensity < propensityThreshold) return ArbitrationSelection.RuleEngine
        val eligible = candidates.filter { !it.insufficientEvidence }
        if (eligible.isEmpty()) return ArbitrationSelection.LlmPlanner("default")
        if (random.nextDouble() < config.epsilon) {
            return ArbitrationSelection.LlmPlanner(eligible.random(random).archetype)
        }
        val best = eligible.maxWith(compareBy({ it.revenuePerJourney }, { it.conversionRate }))
        return ArbitrationSelection.LlmPlanner(best.archetype)
    }
}

/**
 * Step 7 wiring: turns [JourneyEnded] events into stored outcomes and fresh
 * evidence. The runtime calls [evidenceSink] to feed rows back into planning.
 */
class LearningSubscriber(
    private val bus: EventBus,
    private val store: OutcomeStore,
    private val config: AggregationConfig = AggregationConfig(),
    private val evidenceSink: suspend (List<ArchetypePerformance>) -> Unit = {},
) {
    suspend fun start(): EventBus.Subscription = bus.subscribe(JourneyEnded::class) { ended ->
        store.append(ended.outcome)
        val evidence = aggregateOutcomes(store.read(0), config, ended.atEpochMillis)
        evidenceSink(evidence)
    }
}
