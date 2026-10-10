package com.intelliworks.journey

import kotlinx.serialization.Serializable

/**
 * Where the planning LLM runs. Decides the PII policy:
 * - CLOUD: pseudonymize by default, PII never leaves for the provider.
 * - LOCAL: full profile data may be used.
 */
enum class LlmTarget { CLOUD, LOCAL }

@Serializable
data class CampaignConstraints(
    val allowedChannels: Set<Channel> = setOf(Channel.EMAIL, Channel.PUSH),
    val maxDiscountPercent: Int? = null,
    val locale: String = "nl-NL",
    val language: String = "nl",
    val forbiddenTopics: Set<String> = emptySet(),
    val maxNodes: Int = 12,
    val maxWaitHours: Long = 24 * 14,
)

@Serializable
data class Campaign(
    val goal: String,
    val constraints: CampaignConstraints = CampaignConstraints(),
)

@Serializable
data class ArchetypePerformance(
    val archetype: String,
    val segment: String,
    val journeys: Int,
    val conversionRate: Double,
)

/**
 * Public contract: what the external journey runtime sends when it asks for a journey.
 * Raw domain facts only — all LLM shaping (summaries, pseudonymization, prompts)
 * is internal to the planner.
 */
@Serializable
data class PlanRequest(
    val profile: PersonProfile,
    val behavior: MeasuredBehavior,
    val products: List<Product>,
    val campaign: Campaign,
    val segmentPerformance: List<ArchetypePerformance> = emptyList(),
    val nowEpochMillis: Long,
)

interface JourneyPlanner {
    suspend fun plan(request: PlanRequest): Journey
}
