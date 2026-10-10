package com.intelliworks.journey

/**
 * Routes each planning request: customers with a high propensity to buy get the
 * LLM planner with real-time journey building; everyone else gets the rule-based
 * engine. Propensity is scored deterministically in code ([propensityScore]).
 */
class JourneyRouter(
    private val llm: LlmClient,
    private val target: LlmTarget,
    private val propensityThreshold: Double = 0.7,
    planner: JourneyPlanner? = null,
    rules: JourneyPlanner? = null,
) : JourneyPlanner {

    private val planner: JourneyPlanner = planner ?: LlmJourneyPlanner(llm, target)
    private val rules: JourneyPlanner = rules ?: LlmJourneyEngine(llm).asPlanner()

    override suspend fun plan(request: PlanRequest): Journey {
        val score = propensityScore(summarize(request.behavior, request.nowEpochMillis))
        return if (score >= propensityThreshold) planner.plan(request)
        else rules.plan(request)
    }
}
