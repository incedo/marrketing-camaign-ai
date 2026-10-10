package com.intelliworks.journey

import kotlinx.serialization.Serializable

@Serializable
enum class Trend { INCREASING, STABLE, DECREASING, UNKNOWN }

/**
 * Deterministic, code-computed facts about behavior. The LLM interprets these;
 * it never does the arithmetic itself.
 */
@Serializable
data class BehaviorSummary(
    val daysSinceLastActive: Long?,
    val daysSinceLastPurchase: Long?,
    val eventCounts30d: Map<String, Int>,
    val eventCounts90d: Map<String, Int>,
    val purchaseCount90d: Int,
    val totalSpendCents90d: Long,
    val trend: Trend,
    val isNewCustomer: Boolean,
)

private const val DAY_MS = 24L * 60 * 60 * 1000

fun summarize(behavior: MeasuredBehavior, nowEpochMillis: Long): BehaviorSummary {
    val events = behavior.events
    val lastActive = events.maxOfOrNull { it.atEpochMillis }
    val purchases = events.filter { it.type == "purchase_completed" }
    val lastPurchase = purchases.maxOfOrNull { it.atEpochMillis }

    fun counts(windowDays: Long): Map<String, Int> =
        events.filter { it.atEpochMillis >= nowEpochMillis - windowDays * DAY_MS }
            .groupingBy { it.type }
            .eachCount()

    val recent45 = events.count { it.atEpochMillis >= nowEpochMillis - 45 * DAY_MS }
    val prev45 = events.count {
        it.atEpochMillis in (nowEpochMillis - 90 * DAY_MS) until (nowEpochMillis - 45 * DAY_MS)
    }
    val trend = when {
        recent45 + prev45 == 0 -> Trend.UNKNOWN
        recent45 > prev45 * 1.5 -> Trend.INCREASING
        prev45 > recent45 * 1.5 -> Trend.DECREASING
        else -> Trend.STABLE
    }

    val firstEvent = events.minOfOrNull { it.atEpochMillis }
    return BehaviorSummary(
        daysSinceLastActive = lastActive?.let { (nowEpochMillis - it) / DAY_MS },
        daysSinceLastPurchase = lastPurchase?.let { (nowEpochMillis - it) / DAY_MS },
        eventCounts30d = counts(30),
        eventCounts90d = counts(90),
        purchaseCount90d = purchases.count { it.atEpochMillis >= nowEpochMillis - 90 * DAY_MS },
        totalSpendCents90d = purchases
            .filter { it.atEpochMillis >= nowEpochMillis - 90 * DAY_MS }
            .sumOf { it.properties["amountCents"]?.toLongOrNull() ?: 0L },
        trend = trend,
        isNewCustomer = firstEvent != null && firstEvent >= nowEpochMillis - 30 * DAY_MS,
    )
}

/**
 * Propensity-to-buy score in 0..1 from recency (40%), purchase frequency (30%),
 * engagement (20%) and trend (10%). Deterministic; the router thresholds on it.
 */
fun propensityScore(summary: BehaviorSummary): Double {
    var score = 0.0
    val recencyDays = summary.daysSinceLastActive
    score += when {
        recencyDays == null -> 0.0
        recencyDays == 0L -> 0.40
        recencyDays <= 7L -> 0.32
        recencyDays <= 30L -> 0.20
        recencyDays <= 90L -> 0.08
        else -> 0.02
    }
    score += when {
        summary.purchaseCount90d >= 3 -> 0.30
        summary.purchaseCount90d == 2 -> 0.20
        summary.purchaseCount90d == 1 -> 0.12
        else -> 0.0
    }
    score += when (val active = summary.eventCounts30d.values.sum()) {
        in 10..Int.MAX_VALUE -> 0.20
        in 4..9 -> 0.12
        in 1..3 -> 0.06
        else -> 0.0
    }
    score += when (summary.trend) {
        Trend.INCREASING -> 0.10
        Trend.STABLE -> 0.05
        else -> 0.0
    }
    return score.coerceIn(0.0, 1.0)
}
