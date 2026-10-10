package com.intelliworks.journey

import kotlin.reflect.KClass

/**
 * Port for event delivery. The module only depends on this interface; the
 * runtime supplies the implementation (in-memory, Kafka, ...). Handlers run on
 * the publisher's coroutine — implementations that need isolation must
 * dispatch themselves.
 */
interface EventBus {
    suspend fun publish(event: JourneyEvent)

    fun <T : JourneyEvent> subscribe(
        type: KClass<T>,
        handler: suspend (T) -> Unit,
    ): Subscription

    fun interface Subscription {
        fun cancel()
    }
}

/**
 * In-memory bus for tests and simple embeddings. Dispatches to subscribers of
 * the event's exact class, in subscription order, on the publishing coroutine.
 */
class InMemoryEventBus : EventBus {
    private val handlers =
        mutableMapOf<KClass<out JourneyEvent>, MutableList<suspend (JourneyEvent) -> Unit>>()

    /** Every event published, in order — handy for assertions. */
    val published = mutableListOf<JourneyEvent>()

    override suspend fun publish(event: JourneyEvent) {
        published += event
        handlers[event::class]?.toList()?.forEach { it(event) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : JourneyEvent> subscribe(
        type: KClass<T>,
        handler: suspend (T) -> Unit,
    ): EventBus.Subscription {
        val list = handlers.getOrPut(type) { mutableListOf() }
        val wrapped: suspend (JourneyEvent) -> Unit = { handler(it as T) }
        list += wrapped
        return EventBus.Subscription { list -= wrapped }
    }
}
