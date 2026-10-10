package com.intelliworks.journey

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Generates a unique-enough event id without platform APIs. */
fun newEventId(): String =
    Random.nextLong().toString(16) + Random.nextInt().toString(16)

/**
 * The event vocabulary of the journey engine (event-oriented implementation
 * plan, §2). Events are the only coupling between planner, advice service,
 * learning loop, and the external runtime: components publish and subscribe,
 * never call each other directly.
 *
 * All events are [Serializable] data classes — the wire format is the contract.
 * Evolution rule: fields are only added, never renamed or removed.
 */
@Serializable
sealed interface JourneyEvent {
    val eventId: String
    val atEpochMillis: Long
}

/** A goal a journey run steers toward (llm-action-node-spec §4). */
@Serializable
data class JourneyGoal(
    val id: String,
    val metric: String,
    /** Null = "improve", no hard number. */
    val target: Double? = null,
    val windowDays: Int = 30,
)

/** One lightweight per-run fact streamed by the runtime. */
@Serializable
data class RunEvent(
    val nodeId: String,
    /** sent, opened, clicked, converted, bounced, ... */
    val event: String,
    val channel: String? = null,
    val atEpochMillis: Long,
)

/** Who made a journey: the planner, the rule engine, Kees' editor, an import... */
@Serializable
enum class JourneyMakerKind { AI_PLANNER, RULE_ENGINE, HUMAN_EDITOR, IMPORT }

@Serializable
data class JourneyMaker(
    /** e.g. "kmp-journey-engine", "flow-editor". */
    val id: String,
    val kind: JourneyMakerKind,
    val version: String? = null,
)

@Serializable
data class JourneyPlanned(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val journeyId: String,
    val journey: FlowJourney,
    /** Provenance: who made this journey. The runtime accepts any maker. */
    val maker: JourneyMaker,
    val goal: JourneyGoal? = null,
    val planReasoning: String = "",
) : JourneyEvent

@Serializable
data class NodeEntered(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val nodeId: String,
    val attributes: Map<String, String> = emptyMap(),
) : JourneyEvent

@Serializable
data class AdviceRequested(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val journeyId: String,
    val nodeId: String,
    val journey: FlowJourney,
    val profile: PersonProfile,
    val behavior: MeasuredBehavior,
    val products: List<Product> = emptyList(),
    val channel: Channel,
    val urgency: AdviceUrgency,
    /** Restrict the choice; empty = choose among the node's outgoing neighbors. */
    val candidates: List<String> = emptyList(),
    /** This run's pulse so far, newest last; capped by the producer. */
    val runEvents: List<RunEvent> = emptyList(),
    val goal: JourneyGoal? = null,
    val attributes: Map<String, String> = emptyMap(),
) : JourneyEvent

@Serializable
data class AdviceResolved(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val nodeId: String,
    /** For ML_DECISION and friends: the chosen next node. */
    val recommendedNodeId: String? = null,
    /** For PERSONALIZE_CONTENT: the attribute written. */
    val attributeName: String? = null,
    val attributeValue: String? = null,
    val reasoning: String,
    val servedBy: AdviceSource,
    val goalAchieved: Boolean = false,
    val latencyMs: Long = 0,
) : JourneyEvent

@Serializable
data class AttributeWritten(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val attribute: String,
    val value: String,
) : JourneyEvent

@Serializable
data class RunEventOccurred(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val runEvent: RunEvent,
) : JourneyEvent

@Serializable
data class GoalAchieved(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val goalId: String,
    val metric: String,
    val value: Double,
) : JourneyEvent

@Serializable
data class JourneyEnded(
    override val eventId: String = newEventId(),
    override val atEpochMillis: Long,
    val runId: String,
    val outcome: JourneyOutcome,
) : JourneyEvent
