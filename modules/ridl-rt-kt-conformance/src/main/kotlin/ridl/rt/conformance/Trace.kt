// The trace context: what a runtime that carries it delivers on a claim, on a
// `ReadError.ShortClaim` and on an occurrence (ADR-0021 decision 21). The
// `trace` arm of `suite!` in `crates/ridl-rt-conformance/src/lib.rs`, whose
// cases Rust keeps in `calls.rs` and `events.rs`: here they are one contract,
// in the order of that arm, because each Kotlin contract is one arm's.
//
// The port interfaces state four delivery rules. The base arm pins rule 4, that
// a sender's `null` is delivered as `null`, for every runtime, because a
// runtime that does not carry the context passes it too. These cases pin rules
// 1 and 2, that the context a command, a query or a `raise` is sent with
// arrives unchanged. Rule 3 lets a runtime that does not carry it deliver
// `null`, so such a runtime does not run [traceSuite].
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Claim
import ridl.rt.port.Clock
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ReadError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.trace.TraceContext
import java.nio.ByteBuffer
import kotlin.reflect.KFunction0

/** The tests of a runtime that carries the trace context. */
public class TraceContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a raised events context arrives on every subscribers occurrence`,
        ::`two occurrences each keep their own context`,
        ::`a short buffer keeps the occurrences context`,
        ::`a commands context arrives on its claim`,
        ::`a querys context arrives on its claim`,
        ::`two calls in flight each keep their own context`,
        ::`one callers calls in flight each keep their own context`,
        ::`an oversized claims context survives its second presentation`,
        ::`an oversized querys context is reported on the error`,
        ::`an oversized claims context is reported on every presentation`,
        ::`an oversized querys context is reported on every presentation`,
        ::`an oversized all zero commands context is reported on the error`,
        ::`an oversized all zero querys context is reported on the error`,
        ::`an oversized claims context is the offered calls not the latest`,
        ::`a reused call slot does not keep the previous context`,
        ::`an all zero commands context is carried unchanged`,
        ::`an all zero querys context is carried unchanged`,
        ::`an all zero occurrence context is carried unchanged`,
    )

    private fun Handler.claim(buf: ByteBuffer = out(8)): Claim = checkNotNull(nextClaim(buf)) { "a claim is waiting" }

    /** The trace context of the `ShortClaim` the next `nextClaim` throws, for a call whose arguments are three bytes long. */
    private fun Handler.shortClaimTrace(): TraceContext? {
        val error = assertThrows<ReadError.ShortClaim>("a buffer shorter than the arguments reports ShortClaim") {
            nextClaim(out(1))
        }
        assertEquals(3, error.needed)
        return error.trace
    }

    /** The trace context an event is raised with arrives on the occurrence of every subscribed source. */
    public fun `a raised events context arrives on every subscribers occurrence`() {
        val rt = runtime()
        val second = factory.source(rt)
        rt.subscribe(IFACE, listOf(ORD))
        second.subscribe(IFACE, listOf(ORD))

        rt.raise(IFACE, ORD, bytes(8), TRACE_A)

        assertEquals(TRACE_A, checkNotNull(rt.next(out(8))) { "waiting" }.trace)
        assertEquals(TRACE_A, checkNotNull(second.next(out(8))) { "waiting" }.trace)
    }

    /**
     * Two occurrences raised with different trace contexts each arrive with
     * their own: a source does not keep the first context for later ones. The
     * payloads are not empty.
     */
    public fun `two occurrences each keep their own context`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))

        rt.raise(IFACE, ORD, bytes(1), TRACE_A)
        rt.raise(IFACE, ORD, bytes(2), TRACE_B)

        assertEquals(TRACE_A, checkNotNull(rt.next(out(8))) { "waiting" }.trace)
        assertEquals(TRACE_B, checkNotNull(rt.next(out(8))) { "waiting" }.trace)
    }

    /**
     * `ReadError.Short` does not drop the trace context: the occurrence the
     * next call returns still carries it. The payload is not empty, because an
     * empty one fits every buffer.
     */
    public fun `a short buffer keeps the occurrences context`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))
        rt.raise(IFACE, ORD, bytes(1, 2, 3), TRACE_A)

        assertEquals(ReadError.Short(3), assertThrows<ReadError.Short> { rt.next(out(0)) })

        assertEquals(TRACE_A, checkNotNull(rt.next(out(8))) { "still waiting" }.trace)
    }

    /** The trace context a command is sent with arrives on its claim. */
    public fun `a commands context arrives on its claim`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1), TRACE_A)

        assertEquals(TRACE_A, rt.claim().trace)
    }

    /** The trace context a query is sent with arrives on its claim. */
    public fun `a querys context arrives on its claim`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.query(IFACE, ORD, bytes(1), TRACE_A)

        assertEquals(TRACE_A, rt.claim().trace)
    }

    /** Two calls in flight from two callers each keep the trace context they were sent with, and the two are not exchanged. */
    public fun `two calls in flight each keep their own context`() {
        val rt = runtime()
        val second = factory.caller(rt)
        rt.serve(IFACE, listOf(ORD))

        rt.command(IFACE, ORD, bytes(1), TRACE_A)
        second.command(IFACE, ORD, bytes(2), TRACE_B)

        var buf = out(8)
        val first = rt.claim(buf)
        assertArrayEquals(array(1), buf.written())
        assertEquals(TRACE_A, first.trace)
        buf = out(8)
        val other = rt.claim(buf)
        assertArrayEquals(array(2), buf.written())
        assertEquals(TRACE_B, other.trace)
    }

    /**
     * Two calls in flight from one caller each keep the trace context they
     * were sent with: the second call does not take the context of the first,
     * which is still in flight when the second is sent. Each claim is
     * identified by its argument bytes, so the case does not depend on the
     * presentation order.
     */
    public fun `one callers calls in flight each keep their own context`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))

        rt.command(IFACE, ORD, bytes(1), TRACE_A)
        rt.command(IFACE, ORD, bytes(2), TRACE_B)

        val seen = mutableListOf<Byte>()
        repeat(2) {
            val buf = out(8)
            val claim = rt.claim(buf)
            val args = buf.written()
            when (args.toList()) {
                listOf<Byte>(1) -> assertEquals(TRACE_A, claim.trace, "the first call")
                listOf<Byte>(2) -> assertEquals(TRACE_B, claim.trace, "the second call")
                else -> error("a claim no call was sent with: ${args.toList()}")
            }
            seen += args[0]
            rt.settle(claim.id, Result.success(bytes()))
        }
        assertEquals(listOf<Byte>(1, 2), seen.sorted(), "each call is presented once")
    }

    /**
     * A claim reported through `ReadError.ShortClaim` carries its trace context
     * on the error, so that a provider that settles it without reading it
     * still has the context, and keeps the context: the claim presented once
     * the buffer is large enough carries it too.
     */
    public fun `an oversized claims context survives its second presentation`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1, 2, 3), TRACE_A)

        assertEquals(TRACE_A, rt.shortClaimTrace(), "the ShortClaim error")
        assertEquals(TRACE_A, rt.claim().trace)
    }

    /**
     * A query reported through `ReadError.ShortClaim` carries its trace context
     * on the error, and the claim presented once the buffer is large enough
     * carries it too.
     */
    public fun `an oversized querys context is reported on the error`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.query(IFACE, ORD, bytes(1, 2, 3), TRACE_A)

        assertEquals(TRACE_A, rt.shortClaimTrace(), "the ShortClaim error")
        assertEquals(TRACE_A, rt.claim().trace)
    }

    /** A claim that is reported through `ReadError.ShortClaim` more than once carries its trace context on every report. */
    public fun `an oversized claims context is reported on every presentation`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1, 2, 3), TRACE_A)

        for (presentation in listOf("first", "second", "third")) {
            assertEquals(TRACE_A, rt.shortClaimTrace(), "the $presentation ShortClaim error")
        }
    }

    /** A query that is reported through `ReadError.ShortClaim` more than once carries its trace context on every report. */
    public fun `an oversized querys context is reported on every presentation`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.query(IFACE, ORD, bytes(1, 2, 3), TRACE_A)

        for (presentation in listOf("first", "second", "third")) {
            assertEquals(TRACE_A, rt.shortClaimTrace(), "the $presentation ShortClaim error")
        }
    }

    /** A command sent with [TRACE_ZERO] that is reported through `ReadError.ShortClaim` carries that context on the error, not `null`. */
    public fun `an oversized all zero commands context is reported on the error`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1, 2, 3), TRACE_ZERO)

        assertEquals(TRACE_ZERO, rt.shortClaimTrace(), "the ShortClaim error")
    }

    /** A query sent with [TRACE_ZERO] that is reported through `ReadError.ShortClaim` carries that context on the error, not `null`. */
    public fun `an oversized all zero querys context is reported on the error`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.query(IFACE, ORD, bytes(1, 2, 3), TRACE_ZERO)

        assertEquals(TRACE_ZERO, rt.shortClaimTrace(), "the ShortClaim error")
    }

    /**
     * The trace context a `ReadError.ShortClaim` carries is the context of the
     * call it offers, not that of another call in flight: the oversized call is
     * sent first and a later call with another context is sent after it. The
     * context is the offered call's on every presentation of the claim.
     */
    public fun `an oversized claims context is the offered calls not the latest`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1, 2, 3), TRACE_A)
        rt.command(IFACE, ORD, bytes(9), TRACE_B)

        for (presentation in listOf("first", "second", "third")) {
            assertEquals(TRACE_A, rt.shortClaimTrace(), "the $presentation ShortClaim error")
        }
    }

    /**
     * A call that takes a slot another call held does not keep that call's
     * trace context. With every other slot held, the new call can only take
     * the slot that was reclaimed.
     */
    public fun `a reused call slot does not keep the previous context`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val sent = List(factory.slots) {
            val c = rt.command(IFACE, ORD, bytes(1), TRACE_A)
            val claim = rt.claim()
            assertEquals(TRACE_A, claim.trace, "the old call carries it")
            rt.settle(claim.id, Result.success(bytes()))
            c
        }
        rt.forget(sent[0])

        rt.command(IFACE, ORD, bytes(2), null)
        assertNull(rt.claim().trace)
    }

    /** A command sent with [TRACE_ZERO] arrives with it unchanged. */
    public fun `an all zero commands context is carried unchanged`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1), TRACE_ZERO)

        assertEquals(TRACE_ZERO, rt.claim().trace)
    }

    /** A query sent with [TRACE_ZERO] arrives with it unchanged. */
    public fun `an all zero querys context is carried unchanged`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.query(IFACE, ORD, bytes(1), TRACE_ZERO)

        assertEquals(TRACE_ZERO, rt.claim().trace)
    }

    /** An occurrence raised with [TRACE_ZERO] arrives with it unchanged. */
    public fun `an all zero occurrence context is carried unchanged`() {
        val rt = runtime()
        rt.subscribe(IFACE, listOf(ORD))
        rt.raise(IFACE, ORD, bytes(8), TRACE_ZERO)

        assertEquals(TRACE_ZERO, checkNotNull(rt.next(out(8))) { "waiting" }.trace)
    }

    private companion object {
        /**
         * A trace context the cases send, and the one they tell it apart from.
         * Every byte differs from every other byte of this value and of
         * [TRACE_B], so a runtime that reverses, truncates or masks a field, or
         * that delivers one context's field in place of the other's, fails a
         * case.
         */
        val TRACE_A = TraceContext(ByteArray(16) { it.toByte() }, ByteArray(8) { (0x10 + it).toByte() }, 0x5Au)

        /** The second trace context, with different identifiers and flags than [TRACE_A]. */
        val TRACE_B = TraceContext(ByteArray(16) { (0xF0 + it).toByte() }, ByteArray(8) { (0xE0 + it).toByte() }, 0xA5u)

        /**
         * A trace context whose bytes are all zero. `ridl-rt-kt` does not
         * validate the context, so a runtime does not drop it or replace it
         * with `null`.
         */
        val TRACE_ZERO = TraceContext(ByteArray(16), ByteArray(8), 0u)
    }
}
