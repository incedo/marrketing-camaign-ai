package com.intelliworks.journey

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Realistic, fully valid example journeys built from the 60 [FlowNodeType]s.
 *
 * Each journey passes [FlowJourney.validate] (with the profile attributes the
 * runtime provides) and round-trips through kotlinx.serialization JSON, so the
 * files under examples/ are exactly what the runtime would receive.
 */
object SampleJourneys {

    private fun node(id: String, type: FlowNodeType, vararg params: Pair<String, String>) =
        id to FlowNode(id, type, params.toMap())

    private fun edge(from: String, to: String, slot: String? = null) =
        FlowEdge(from, to, slot)

    /** Profile attributes the runtime guarantees for the samples. */
    val knownAttributes: Set<String> = setOf("firstName", "language", "cartSummary")

    /**
     * Win-back for dormant customers: consent + frequency guard, LLM-written
     * subject line, 72h wait, opened-check, then ML_DECISION arbitrates —
     * converters exit, the rest get a channel-switched WhatsApp nudge.
     */
    fun winback(): FlowJourney {
        val nodes = mapOf(
            node("start", FlowNodeType.START_TRIGGER,
                "eventType" to "segment_entered", "segment" to "dormant_90d"),
            node("consent", FlowNodeType.CONSENT_CHECK, "topic" to "marketing"),
            node("freqcap", FlowNodeType.FREQUENCY_CAP,
                "maxTouches" to "3", "periodDays" to "7"),
            node("subject", FlowNodeType.PERSONALIZE_CONTENT,
                "promptTemplate" to "Schrijf een korte onderwerpregel voor {{firstName}}, 90 dagen inactief, in het {{language}}. Max 50 tekens.",
                "outputAttribute" to "subjectLine",
                "timeoutMs" to "1500",
                "fallbackValue" to "We missen je — kom terug met 10% korting"),
            node("email", FlowNodeType.SEND_EMAIL,
                "subject" to "{{subjectLine}}", "templateId" to "winback_v3"),
            node("wait72", FlowNodeType.WAIT, "durationHours" to "72"),
            node("opened", FlowNodeType.WAIT_UNTIL_EVENT, "eventType" to "email_opened"),
            node("decide", FlowNodeType.ML_DECISION, "goalRef" to "purchase"),
            node("channel", FlowNodeType.NEXT_BEST_CHANNEL),
            node("whatsapp", FlowNodeType.SEND_WHATSAPP, "template" to "winback_nudge"),
            node("converted", FlowNodeType.CONVERSION_CHECK),
            node("exit", FlowNodeType.EXIT_JOURNEY),
        )
        val edges = listOf(
            edge("start", "consent"),
            edge("consent", "freqcap", "true"),
            edge("consent", "exit", "false"),
            edge("freqcap", "subject", "true"),
            edge("freqcap", "exit", "false"),
            edge("subject", "email"),
            edge("email", "wait72"),
            edge("wait72", "opened"),
            edge("opened", "decide", "event"),
            edge("opened", "channel", "timeout"),
            edge("decide", "converted"),
            edge("channel", "whatsapp"),
            edge("whatsapp", "converted"),
            edge("converted", "exit", "true"),
            edge("converted", "exit", "false"),
        )
        return FlowJourney(nodes, edges)
    }

    /**
     * New-signup onboarding: newsletter consent, quiet hours, LLM welcome copy,
     * wait for first app open, branch on onboarding completion.
     */
    fun welcome(): FlowJourney {
        val nodes = mapOf(
            node("start", FlowNodeType.START_TRIGGER, "eventType" to "signed_up"),
            node("consent", FlowNodeType.CONSENT_CHECK, "topic" to "newsletter"),
            node("quiet", FlowNodeType.QUIET_HOURS, "start" to "22:00", "end" to "08:00"),
            node("welcome_copy", FlowNodeType.PERSONALIZE_CONTENT,
                "promptTemplate" to "Schrijf een warme welkomsttekst voor {{firstName}} in het {{language}}. Max 3 zinnen.",
                "outputAttribute" to "welcomeText",
                "timeoutMs" to "1500",
                "fallbackValue" to "Welkom bij ons!"),
            node("email", FlowNodeType.SEND_EMAIL, "templateId" to "welcome_v1"),
            node("app_open", FlowNodeType.WAIT_UNTIL_EVENT, "eventType" to "app_opened"),
            node("onboarded", FlowNodeType.IF_ELSE,
                "condition" to "profile.onboarding_complete"),
            node("segment", FlowNodeType.ADD_TO_SEGMENT, "segmentId" to "onboarded"),
            node("exit", FlowNodeType.EXIT_JOURNEY),
        )
        val edges = listOf(
            edge("start", "consent"),
            edge("consent", "quiet", "true"),
            edge("consent", "exit", "false"),
            edge("quiet", "welcome_copy"),
            edge("welcome_copy", "email"),
            edge("email", "app_open"),
            edge("app_open", "onboarded", "event"),
            edge("app_open", "exit", "timeout"),
            edge("onboarded", "segment", "true"),
            edge("onboarded", "exit", "false"),
            edge("segment", "exit"),
        )
        return FlowJourney(nodes, edges)
    }

    /**
     * Abandoned cart: best send time, frequency guard, coupon, LLM cart summary,
     * WhatsApp reminder, 24h wait, purchase check.
     */
    fun abandonedCart(): FlowJourney {
        val nodes = mapOf(
            node("start", FlowNodeType.START_TRIGGER, "eventType" to "cart_abandoned"),
            node("besttime", FlowNodeType.BEST_SEND_TIME),
            node("freqcap", FlowNodeType.FREQUENCY_CAP,
                "maxTouches" to "2", "periodDays" to "3"),
            node("coupon", FlowNodeType.GENERATE_COUPON,
                "discountPercent" to "10", "expiresHours" to "48"),
            node("cart_copy", FlowNodeType.PERSONALIZE_CONTENT,
                "promptTemplate" to "Vat de winkelwagen van {{firstName}} samen: {{cartSummary}}. Max 2 zinnen, in het {{language}}.",
                "outputAttribute" to "cartText",
                "timeoutMs" to "1500",
                "fallbackValue" to "Je winkelwagen wacht op je."),
            node("whatsapp", FlowNodeType.SEND_WHATSAPP, "template" to "cart_reminder"),
            node("wait24", FlowNodeType.WAIT, "durationHours" to "24"),
            node("purchased", FlowNodeType.CONVERSION_CHECK),
            node("profile", FlowNodeType.UPDATE_PROFILE, "lastCartReminder" to "sent"),
            node("exit", FlowNodeType.EXIT_JOURNEY),
        )
        val edges = listOf(
            edge("start", "besttime"),
            edge("besttime", "freqcap"),
            edge("freqcap", "coupon", "true"),
            edge("freqcap", "exit", "false"),
            edge("coupon", "cart_copy"),
            edge("cart_copy", "whatsapp"),
            edge("whatsapp", "wait24"),
            edge("wait24", "purchased"),
            edge("purchased", "profile", "true"),
            edge("purchased", "exit", "false"),
            edge("profile", "exit"),
        )
        return FlowJourney(nodes, edges)
    }

    fun all(): Map<String, FlowJourney> = mapOf(
        "winback" to winback(),
        "welcome" to welcome(),
        "abandoned-cart" to abandonedCart(),
    )

    /** All samples as pretty-printed JSON, separated by headers. */
    fun samplesJson(): String {
        val json = Json { prettyPrint = true }
        return all().entries.joinToString("\n") { (name, journey) ->
            "=== $name ===\n" + json.encodeToString(journey)
        }
    }
}
