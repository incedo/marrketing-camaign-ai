package com.intelliworks.journey

/**
 * Internal: the fully-shaped input the LLM prompt is built from.
 * The public contract is [PlanRequest]; this never crosses the module boundary.
 */
data class PlannerInput(
    val profile: PersonProfile,
    val behaviorSummary: BehaviorSummary,
    val recentEvents: List<BehaviorEvent>,
    val products: List<Product>,
    val campaign: Campaign,
    val segmentPerformance: List<ArchetypePerformance>,
    val language: String,
    val nowEpochMillis: Long,
)

internal val PII_ATTRIBUTE_KEYS = setOf(
    "email", "phone", "address", "fullname", "name", "dob", "birthdate", "bsn",
)

internal fun PersonProfile.pseudonymized(): PersonProfile = copy(
    name = null,
    attributes = attributes.filterKeys { it.lowercase() !in PII_ATTRIBUTE_KEYS },
)

internal fun PlanRequest.toPlannerInput(target: LlmTarget): PlannerInput {
    val profile = if (target == LlmTarget.CLOUD) profile.pseudonymized() else profile
    return PlannerInput(
        profile = profile,
        behaviorSummary = summarize(behavior, nowEpochMillis),
        recentEvents = behavior.events.sortedBy { it.atEpochMillis }.takeLast(20),
        products = products,
        campaign = campaign,
        segmentPerformance = segmentPerformance,
        language = profile.language ?: campaign.constraints.language,
        nowEpochMillis = nowEpochMillis,
    )
}

internal fun PlanRequest.toJourneyInput(): JourneyInput = JourneyInput(
    profile = profile,
    behavior = behavior,
    products = products,
    prompt = campaign.goal,
    nowEpochMillis = nowEpochMillis,
)
