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
    /** Pseudonymized person reference — never raw PII (learning-loop-spec §2). */
    val personRef: String? = null,
    val productType: String? = null,
    val channel: String? = null,
    /** Null when unknown; nulls stay neutral in arbitration. */
    val revenue: Double? = null,
)
