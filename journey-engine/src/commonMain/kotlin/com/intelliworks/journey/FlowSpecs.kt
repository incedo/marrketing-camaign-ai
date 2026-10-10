package com.intelliworks.journey

import com.intelliworks.journey.FlowNodeType.*

/**
 * Per-type contract: what the planner must supply and which outgoing slots exist.
 *
 * Specs marked [needsSpec] are best-effort — confirm params/slots against
 * flow/.../FlowModel.kt in the runtime project before relying on them.
 */
data class FlowNodeSpec(
    val type: FlowNodeType,
    val description: String,
    val requiredParams: Set<String> = emptySet(),
    /** Named outgoing slots. Empty = terminal, no outgoing edges. */
    val slots: Set<String> = setOf("next"),
    val needsSpec: Boolean = false,
)

private fun spec(
    type: FlowNodeType,
    description: String,
    requiredParams: Set<String> = emptySet(),
    slots: Set<String> = setOf("next"),
    needsSpec: Boolean = false,
) = FlowNodeSpec(type, description, requiredParams, slots, needsSpec)

fun flowNodeSpecs(): Map<FlowNodeType, FlowNodeSpec> = listOf(
    // Journey control
    spec(START_TRIGGER, "Entry point. First node of the journey."),
    spec(EXIT_JOURNEY, "Ends the journey for the person.", slots = emptySet()),
    spec(PAUSE_JOURNEY, "Pauses execution until resumed.", needsSpec = true),
    spec(RE_ENTER, "Allows the person to re-enter the journey.", needsSpec = true),
    spec(JUMP_TO_STEP, "Jumps execution to another node.", requiredParams = setOf("targetStepId"), slots = emptySet(), needsSpec = true),
    spec(STOP_CAMPAIGNS, "Stops the person's other campaigns.", slots = emptySet(), needsSpec = true),
    // Communicate
    spec(SEND_EMAIL, "Sends an email. Params: subject, content."),
    spec(SEND_SMS, "Sends an SMS. Params: content."),
    spec(SEND_PUSH, "Sends a push notification. Params: content."),
    spec(SEND_WHATSAPP, "Sends a WhatsApp message. Params: content."),
    spec(SEND_IN_APP, "Shows an in-app message. Params: content."),
    spec(WEB_PERSONAL, "Personalizes web content.", needsSpec = true),
    spec(CALL_CENTER_TASK, "Creates a call-center task.", needsSpec = true),
    spec(DIRECT_MAIL, "Sends physical mail.", needsSpec = true),
    // Decide
    spec(IF_ELSE, "Branches on a condition.", requiredParams = setOf("condition"), slots = setOf("true", "false")),
    spec(AUDIENCE_CHECK, "Checks audience membership.", requiredParams = setOf("audienceId"), slots = setOf("true", "false"), needsSpec = true),
    spec(ELIGIBILITY_CHECK, "Checks eligibility.", slots = setOf("true", "false"), needsSpec = true),
    spec(AB_SPLIT, "Splits traffic A/B.", requiredParams = setOf("splitPercentA"), slots = setOf("A", "B")),
    spec(RANDOM_SPLIT, "Random split.", requiredParams = setOf("splitPercentA"), slots = setOf("A", "B"), needsSpec = true),
    spec(CONTROL_GROUP, "Holds out a control group.", requiredParams = setOf("holdoutPercent"), slots = setOf("test", "control"), needsSpec = true),
    spec(ML_DECISION, "Goal arbiter / model-driven decision; may call the advice API.", requiredParams = setOf("goalRef"), needsSpec = true),
    spec(NEXT_BEST_CHANNEL, "Picks the best channel via the advice API."),
    // AI
    spec(SCORE, "Computes a score, e.g. propensity.", needsSpec = true),
    spec(RECOMMEND, "Recommends products or content.", needsSpec = true),
    // Access
    spec(ACCESS_CHECK, "Checks access.", slots = setOf("true", "false"), needsSpec = true),
    spec(ENTITLEMENT_CHECK, "Checks entitlement.", slots = setOf("true", "false"), needsSpec = true),
    spec(GRANT_ACCESS, "Grants access.", needsSpec = true),
    spec(REVOKE_ACCESS, "Revokes access.", needsSpec = true),
    // Wait & timing
    spec(WAIT, "Waits a duration.", requiredParams = setOf("durationHours")),
    spec(WAIT_UNTIL_DATE, "Waits until a date.", requiredParams = setOf("date")),
    spec(WAIT_UNTIL_EVENT, "Waits for an event or a timeout.", requiredParams = setOf("eventType"), slots = setOf("event", "timeout")),
    spec(BEST_SEND_TIME, "Optimizes send time.", needsSpec = true),
    spec(QUIET_HOURS, "Delays sends during quiet hours.", requiredParams = setOf("start", "end"), needsSpec = true),
    // Personalize
    spec(ASSIGN_OFFER, "Assigns an offer.", needsSpec = true),
    spec(RESERVE_OFFER, "Reserves an offer.", needsSpec = true),
    spec(GENERATE_COUPON, "Generates a coupon.", needsSpec = true),
    spec(PERSONALIZE_CONTENT, "LLM action node: runs promptTemplate, writes outputAttribute.", requiredParams = setOf("promptTemplate", "outputAttribute", "timeoutMs", "fallbackValue")),
    spec(NEXT_BEST_ACTION, "Picks the next best action via the advice API."),
    // Govern
    spec(CONSENT_CHECK, "Checks consent for a topic/channel before sending.", requiredParams = setOf("topic"), slots = setOf("true", "false"), needsSpec = true),
    spec(SUPPRESSION_CHECK, "Checks suppression lists.", slots = setOf("true", "false"), needsSpec = true),
    spec(FREQUENCY_CAP, "Enforces max touches per period.", requiredParams = setOf("maxTouches", "periodDays"), slots = setOf("true", "false"), needsSpec = true),
    spec(CHANNEL_PREFERENCE, "Reads channel preference.", needsSpec = true),
    spec(LEGAL_EXCLUSION, "Applies legal exclusions.", slots = setOf("true", "false"), needsSpec = true),
    spec(INTERACTION_MEMORY, "Reads/writes interaction memory.", needsSpec = true),
    // Integrate
    spec(API_WEBHOOK, "Calls an external webhook.", requiredParams = setOf("url"), needsSpec = true),
    spec(PUBLISH_EVENT, "Publishes an event.", requiredParams = setOf("eventType"), needsSpec = true),
    spec(CREATE_CRM_TASK, "Creates a CRM task.", needsSpec = true),
    spec(CREATE_SERVICE_CASE, "Creates a service case.", needsSpec = true),
    spec(START_FULFILLMENT, "Starts fulfillment.", needsSpec = true),
    spec(AD_PLATFORM_SYNC, "Syncs with an ad platform.", needsSpec = true),
    // Update data
    spec(UPDATE_PROFILE, "Updates the profile.", needsSpec = true),
    spec(ADD_TO_SEGMENT, "Adds the person to a segment.", requiredParams = setOf("segmentId"), needsSpec = true),
    spec(SET_CAMPAIGN_STATE, "Sets campaign state.", needsSpec = true),
    spec(UPDATE_PREFERENCE, "Updates a preference.", needsSpec = true),
    spec(LOG_INTERACTION, "Logs an interaction.", needsSpec = true),
    // Measure
    spec(TRACK_EVENT, "Tracks an event.", requiredParams = setOf("eventType"), needsSpec = true),
    spec(CONVERSION_CHECK, "Checks conversion.", slots = setOf("true", "false"), needsSpec = true),
    spec(ATTRIBUTION, "Runs attribution.", needsSpec = true),
    spec(SURVEY_TRIGGER, "Triggers a survey.", needsSpec = true),
    spec(ANALYTICS_EXPORT, "Exports analytics.", needsSpec = true),
).associateBy { it.type }
