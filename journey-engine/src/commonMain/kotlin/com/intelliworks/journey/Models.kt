package com.intelliworks.journey

import kotlinx.serialization.Serializable

@Serializable
data class PersonProfile(
    val id: String,
    val name: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val segments: Set<String> = emptySet(),
    /** Preferred copy language, e.g. "nl" or "en". Falls back to campaign default. */
    val language: String? = null,
)

@Serializable
enum class ProductType {
    PHYSICAL, DIGITAL, SUBSCRIPTION, SERVICE
}

@Serializable
data class Product(
    val id: String,
    val name: String,
    val type: ProductType,
    val tags: Set<String> = emptySet(),
    val priceCents: Long? = null,
)

@Serializable
data class BehaviorEvent(
    val type: String,
    val atEpochMillis: Long,
    val properties: Map<String, String> = emptyMap(),
)

@Serializable
data class MeasuredBehavior(
    val personId: String,
    val events: List<BehaviorEvent> = emptyList(),
) {
    fun count(type: String): Int = events.count { it.type == type }

    fun lastOf(type: String): BehaviorEvent? =
        events.filter { it.type == type }.maxByOrNull { it.atEpochMillis }

    fun hasEventSince(type: String, sinceEpochMillis: Long): Boolean =
        events.any { it.type == type && it.atEpochMillis >= sinceEpochMillis }
}
