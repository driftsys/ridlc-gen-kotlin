// Events: subscription, delivery, a short buffer, and the sender's sequence
// number (`EventSink` and `EventSource`). `crates/ridl-rt-conformance/src/events.rs`.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ReadError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import kotlin.reflect.KFunction0

/** The tests of `EventSink` and `EventSource`. */
public class EventsContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`an event raise and receive round trips`,
        ::`an occurrence raised before the subscription is not delivered`,
        ::`unsubscribe stops delivery of what is already queued`,
        ::`two sources each receive their own copy of one occurrence`,
        ::`a short buffer leaves the occurrence for the next call`,
        ::`a sink sequence number counts one channel publications`,
    )

    /** A raised occurrence reaches a subscribed source, whole. */
    public fun `an event raise and receive round trips`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))
        rt.raise(IFACE, ORD, bytes(5, 6))

        val buf = out(8)
        val occurrence = checkNotNull(rt.next(buf)) { "an occurrence is waiting" }
        assertEquals(IFACE, occurrence.iface)
        assertEquals(ORD, occurrence.ord)
        assertArrayEquals(array(5, 6), buf.written())
    }

    /** A late joiner receives nothing retroactive on an event. */
    public fun `an occurrence raised before the subscription is not delivered`() {
        val rt = runtime()
        rt.raise(IFACE, ORD, bytes(1))
        rt.subscribe(IFACE, listOf(ORD))
        assertNull(rt.next(out(8)), "a late joiner receives nothing retroactive on an event")
    }

    /** `unsubscribe` also stops delivery of what is already queued. */
    public fun `unsubscribe stops delivery of what is already queued`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))
        rt.raise(IFACE, ORD, bytes(1))
        rt.unsubscribe(IFACE, listOf(ORD))
        assertNull(rt.next(out(8)))
    }

    /** Two subscribed sources each receive a copy of one occurrence, and consuming one copy does not consume the other. */
    public fun `two sources each receive their own copy of one occurrence`() {
        val rt = runtime()
        val second = factory.source(rt)
        rt.subscribe(IFACE, listOf(ORD))
        second.subscribe(IFACE, listOf(ORD))

        rt.raise(IFACE, ORD, bytes(8))

        assertEquals(1, checkNotNull(rt.next(out(8))).len, "the first source consumes its own copy")
        assertEquals(1, checkNotNull(second.next(out(8))).len, "and does not consume the second source's")
    }

    /** `ReadError.Short` does not consume the occurrence: the next call returns the same one. */
    public fun `a short buffer leaves the occurrence for the next call`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))
        rt.raise(IFACE, ORD, bytes(1, 2, 3))

        assertEquals(ReadError.Short(3), assertThrows<ReadError.Short> { rt.next(out(1)) })

        val buf = out(8)
        checkNotNull(rt.next(buf)) { "still waiting" }
        assertArrayEquals(array(1, 2, 3), buf.written())
    }

    /**
     * One sink raising on two of its events, with a consumer subscribed to one
     * of them. A counter per sink rather than per channel would number this
     * consumer's two occurrences 1 and 3, and `EventSource.next` states that a
     * gap in seq is a loss — so the consumer would read a loss that did not
     * happen.
     */
    public fun `a sink sequence number counts one channel publications`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))

        rt.raise(IFACE, ORD, bytes(1))
        rt.raise(IFACE, OTHER, bytes(2))
        rt.raise(IFACE, ORD, bytes(3))

        val seqs = generateSequence { rt.next(out(8)) }.map { it.envelope.seq }.toList()
        assertEquals(listOf(1uL, 2uL), seqs, "no gap: the other event has its own counter")
    }
}
