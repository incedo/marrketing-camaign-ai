package com.intelliworks.journey

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Channel { EMAIL, PUSH, SMS, IN_APP }

/** A single step in a marketing journey. Serialized polymorphically for the external runtime. */
@Serializable
sealed interface JourneyNode {
    val id: String
}

@Serializable
@SerialName("start")
data class StartNode(override val id: String = "start") : JourneyNode

@Serializable
@SerialName("end")
data class EndNode(
    override val id: String,
    val outcome: String = "completed",
) : JourneyNode

/** Pause the journey until [eventType] happens; go to [onTimeoutNodeId] after [timeoutHours]. */
@Serializable
@SerialName("waitOnEvent")
data class WaitOnEventNode(
    override val id: String,
    val eventType: String,
    val timeoutHours: Long? = null,
    val onTimeoutNodeId: String,
    val nextNodeId: String,
) : JourneyNode

/** Branch on a yes/no question evaluated at runtime. */
@Serializable
@SerialName("decision")
data class DecisionNode(
    override val id: String,
    val question: String,
    val trueNodeId: String,
    val falseNodeId: String,
) : JourneyNode

/** Split traffic  [splitPercentA] / (100 - [splitPercentA]) over two variants. */
@Serializable
@SerialName("abTest")
data class ABTestNode(
    override val id: String,
    val name: String,
    val variantANodeId: String,
    val variantBNodeId: String,
    val splitPercentA: Int = 50,
) : JourneyNode

/** Send one marketing message, then continue at [nextNodeId]. */
@Serializable
@SerialName("marketing")
data class MarketingNode(
    override val id: String,
    val channel: Channel,
    val content: String,
    val productIds: List<String> = emptyList(),
    val nextNodeId: String,
) : JourneyNode

@Serializable
data class JourneyEdge(val from: String, val to: String, val label: String? = null)

@Serializable
data class Journey(
    val nodes: Map<String, JourneyNode>,
    val edges: List<JourneyEdge>,
    val startNodeId: String = "start",
) {
    fun node(id: String): JourneyNode = nodes.getValue(id)

    /** Returns a list of problems; empty means the journey is valid. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val start = nodes[startNodeId]
        if (start == null) problems += "Start node '$startNodeId' not found"
        else if (start !is StartNode) problems += "Node '$startNodeId' is not a StartNode"

        val outgoing = mutableMapOf<String, MutableList<String>>()
        fun link(from: String, to: String) {
            outgoing.getOrPut(from) { mutableListOf() } += to
        }

        for (node in nodes.values) {
            when (node) {
                is StartNode, is EndNode -> Unit
                is WaitOnEventNode -> {
                    link(node.id, node.nextNodeId)
                    link(node.id, node.onTimeoutNodeId)
                }
                is DecisionNode -> {
                    link(node.id, node.trueNodeId)
                    link(node.id, node.falseNodeId)
                }
                is ABTestNode -> {
                    if (node.splitPercentA !in 1..99) {
                        problems += "ABTest '${node.id}' split must be within 1..99"
                    }
                    link(node.id, node.variantANodeId)
                    link(node.id, node.variantBNodeId)
                }
                is MarketingNode -> link(node.id, node.nextNodeId)
            }
        }

        for ((from, tos) in outgoing) {
            for (to in tos) {
                if (to !in nodes) problems += "Node '$from' points to unknown node '$to'"
            }
        }
        for (edge in edges) {
            if (edge.from !in nodes) problems += "Edge starts at unknown node '${edge.from}'"
            if (edge.to !in nodes) problems += "Edge ends at unknown node '${edge.to}'"
        }

        if (start != null) {
            // Reachability follows both node links and declared edges.
            val seen = mutableSetOf<String>()
            fun visit(id: String) {
                if (seen.add(id)) {
                    outgoing[id]?.forEach(::visit)
                    edges.filter { it.from == id }.forEach { visit(it.to) }
                }
            }
            visit(startNodeId)
            for (id in nodes.keys) {
                if (id !in seen) problems += "Node '$id' is unreachable from start"
            }
        }
        return problems
    }
}
