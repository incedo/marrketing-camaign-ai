package com.intelliworks.journey

/** Small DSL for assembling journeys without hand-wiring maps. */
class JourneyBuilder {
    private val nodes = linkedMapOf<String, JourneyNode>()
    private val edges = mutableListOf<JourneyEdge>()

    private fun <N : JourneyNode> add(node: N): N {
        require(node.id !in nodes) { "Duplicate node id '${node.id}'" }
        nodes[node.id] = node
        return node
    }

    fun start(id: String = "start"): StartNode = add(StartNode(id))

    fun end(id: String, outcome: String = "completed"): EndNode = add(EndNode(id, outcome))

    fun waitOnEvent(
        id: String,
        eventType: String,
        timeoutHours: Long? = null,
        onTimeoutNodeId: String,
        nextNodeId: String,
    ): WaitOnEventNode = add(WaitOnEventNode(id, eventType, timeoutHours, onTimeoutNodeId, nextNodeId))

    fun decision(
        id: String,
        question: String,
        trueNodeId: String,
        falseNodeId: String,
    ): DecisionNode = add(DecisionNode(id, question, trueNodeId, falseNodeId))

    fun abTest(
        id: String,
        name: String,
        variantANodeId: String,
        variantBNodeId: String,
        splitPercentA: Int = 50,
    ): ABTestNode = add(ABTestNode(id, name, variantANodeId, variantBNodeId, splitPercentA))

    fun marketing(
        id: String,
        channel: Channel,
        content: String,
        productIds: List<String> = emptyList(),
        nextNodeId: String,
    ): MarketingNode = add(MarketingNode(id, channel, content, productIds, nextNodeId))

    fun edge(from: String, to: String, label: String? = null) {
        edges += JourneyEdge(from, to, label)
    }

    fun build(startNodeId: String = "start"): Journey =
        Journey(nodes.toMap(), edges.toList(), startNodeId)
}

suspend fun buildJourney(startNodeId: String = "start", block: suspend JourneyBuilder.() -> Unit): Journey {
    val builder = JourneyBuilder()
    builder.block()
    return builder.build(startNodeId)
}
