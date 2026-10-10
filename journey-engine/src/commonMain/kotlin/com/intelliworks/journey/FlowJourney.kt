package com.intelliworks.journey

import kotlinx.serialization.Serializable

/**
 * A runtime node: one of the 60 [FlowNodeType]s with its type-specific fields in
 * [params] (values as strings, e.g. eventType, condition, subject).
 */
@Serializable
data class FlowNode(
    val id: String,
    val type: FlowNodeType,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class FlowEdge(
    val from: String,
    val to: String,
    /** Named outgoing slot, e.g. "true"/"false", "A"/"B", "event"/"timeout". Null = default. */
    val slot: String? = null,
)

@Serializable
data class FlowJourney(
    val nodes: Map<String, FlowNode>,
    val edges: List<FlowEdge>,
    val startNodeId: String = "start",
) {
    fun node(id: String): FlowNode = nodes.getValue(id)

    /** Returns a list of problems; empty means the journey is valid. */
    fun validate(specs: Map<FlowNodeType, FlowNodeSpec> = flowNodeSpecs()): List<String> {
        val problems = mutableListOf<String>()
        val start = nodes[startNodeId]
        if (start == null) problems += "Start node '$startNodeId' not found"
        else if (start.type != FlowNodeType.START_TRIGGER) {
            problems += "Start node must be START_TRIGGER, was ${start.type}"
        }

        val outgoing = mutableMapOf<String, MutableList<FlowEdge>>()
        for (edge in edges) {
            if (edge.from !in nodes) problems += "Edge starts at unknown node '${edge.from}'"
            if (edge.to !in nodes) problems += "Edge ends at unknown node '${edge.to}'"
            outgoing.getOrPut(edge.from) { mutableListOf() } += edge
        }

        for ((id, node) in nodes) {
            val spec = specs[node.type]
            if (spec == null) {
                problems += "No spec for node type ${node.type}"
                continue
            }
            for (param in spec.requiredParams) {
                if (node.params[param].isNullOrBlank()) {
                    problems += "Node '$id' (${node.type}) misses required param '$param'"
                }
            }
            for (edge in outgoing[id].orEmpty()) {
                val slot = edge.slot ?: "next"
                if (slot !in spec.slots) {
                    problems += "Node '$id' (${node.type}) has invalid slot '$slot'"
                }
            }
            if (spec.slots.isEmpty() && outgoing[id].orEmpty().isNotEmpty()) {
                problems += "Node '$id' (${node.type}) is terminal but has outgoing edges"
            }
        }

        if (start != null) {
            val seen = mutableSetOf<String>()
            fun visit(id: String) {
                if (seen.add(id)) outgoing[id]?.forEach { visit(it.to) }
            }
            visit(startNodeId)
            for (id in nodes.keys) {
                if (id !in seen) problems += "Node '$id' is unreachable from start"
            }
        }
        return problems
    }
}
