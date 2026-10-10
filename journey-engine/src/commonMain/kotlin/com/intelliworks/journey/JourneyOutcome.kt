package com.intelliworks.journey

import kotlinx.serialization.Serializable

/**
 * The executing service's return type for a journey. The external journey runtime
 * (not this module) produces these; an append store collects them and they are
 * aggregated into [ArchetypePerformance] for the planner's learning loop.
 */
@Serializable
data class JourneyOutcome(
    val journeyId: String,
    val archetype: String,
    val segment: String,
    val nodePath: List<String>,
    val converted: Boolean,
    val atEpochMillis: Long,
)
