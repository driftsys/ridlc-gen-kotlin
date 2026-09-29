// Signals: staging, the invalid state, the envelope, and a short buffer
// (`SignalWriter` and `SignalReader`). `crates/ridl-rt-conformance/src/signals.rs`.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
import ridl.rt.sample.Cause
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Provenance
import kotlin.reflect.KFunction0

/** The tests of `SignalWriter` and `SignalReader`. */
public class SignalsContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a signal publish and read round trips`,
        ::`a signal with no publication reads as init and copies nothing`,
        ::`a staged value is not visible until commit`,
        ::`one commit publishes every staged signal under one timestamp`,
        ::`a channel sequence number counts that channel publications`,
        ::`invalidate keeps the last good value and reports the declared cause`,
        ::`touch republishes the current value without changing it`,
        ::`a touch does not discard a value staged before it`,
        ::`a touch does not discard an invalidation staged before it`,
        ::`a set after a touch replaces it`,
        ::`touch on a channel with no publication publishes nothing`,
        ::`a short buffer reports what the read needs and consumes nothing`,
    )

    /** A committed value reads back, live. */
    public fun `a signal publish and read round trips`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(42))
        rt.commit()

        val buf = out(8)
        val raw = rt.read(IFACE, ORD, buf)
        assertEquals(Provenance.Live, raw.provenance)
        assertArrayEquals(array(42), buf.written())
    }

    /**
     * Before its first publication a signal reads as `Init`, copies nothing,
     * and carries sequence number 0, stamped when the channel was created
     * (ADR-0021 decision 5). The consumer's binding supplies the init value
     * (ridl §4.4).
     */
    public fun `a signal with no publication reads as init and copies nothing`() {
        val rt = runtime()
        val start = rt.now()
        val buf = out(8)
        val raw = rt.read(IFACE, ORD, buf)
        assertEquals(Provenance.Init, raw.provenance)
        assertEquals(0, raw.len)
        assertEquals(0, buf.position())
        assertEquals(Envelope(start, 0u), raw.envelope)
    }

    /** Staging is private to the writer until `commit`. */
    public fun `a staged value is not visible until commit`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(7))
        assertEquals(Provenance.Init, rt.read(IFACE, ORD, out(8)).provenance, "staging is private to the writer until commit")

        rt.commit()
        assertEquals(Provenance.Live, rt.read(IFACE, ORD, out(8)).provenance)
    }

    /** One commit stamps every signal it publishes with one time, read from the runtime's clock. */
    public fun `one commit publishes every staged signal under one timestamp`() {
        val rt = runtime()
        val start = rt.now()
        factory.advance(rt, Duration(500))
        rt.set(IFACE, ORD, bytes(1))
        rt.set(IFACE, OTHER, bytes(2))
        rt.commit()

        val first = rt.read(IFACE, ORD, out(8))
        val second = rt.read(IFACE, OTHER, out(8))
        assertEquals(later(start, 500), first.envelope.stamp)
        assertEquals(first.envelope.stamp, second.envelope.stamp, "one commit stamps every signal it publishes with one time")
    }

    /**
     * A signal's sequence number counts the publications of that channel, not
     * the commits of the writer or the publications of its interface.
     */
    public fun `a channel sequence number counts that channel publications`() {
        val rt = runtime()
        // Three commits publish ORD, and only the last also publishes OTHER,
        // so a counter kept per writer, per commit or per interface numbers
        // OTHER 3 or 4 rather than 1.
        for (value in 1..3) {
            rt.set(IFACE, ORD, bytes(value))
            if (value == 3) rt.set(IFACE, OTHER, bytes(value))
            rt.commit()
        }
        // The first publication is seq 1, because seq 0 is the envelope of a
        // channel with no publication (ADR-0021 decision 5).
        assertEquals(3uL, rt.read(IFACE, ORD, out(8)).envelope.seq)
        assertEquals(1uL, rt.read(IFACE, OTHER, out(8)).envelope.seq, "the other channel has had one publication")
    }

    /**
     * `invalidate` publishes the invalid state with the declared cause and
     * keeps the last good value (ridl §4.5); a later `set` clears it.
     */
    public fun `invalidate keeps the last good value and reports the declared cause`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(9))
        rt.commit()
        rt.invalidate(IFACE, ORD)
        rt.commit()

        val buf = out(8)
        val raw = rt.read(IFACE, ORD, buf)
        assertEquals(Provenance.Invalid(Cause.Declared), raw.provenance)
        assertArrayEquals(array(9), buf.written(), "the invalid state keeps the last good value (ridl 4.5)")

        rt.set(IFACE, ORD, bytes(10))
        rt.commit()
        assertEquals(Provenance.Live, rt.read(IFACE, ORD, out(8)).provenance, "a set clears the invalid state")
    }

    /** `touch` re-affirms the current value at the new time, as a publication of the channel, without a new value. */
    public fun `touch republishes the current value without changing it`() {
        val rt = runtime()
        val start = rt.now()
        rt.set(IFACE, ORD, bytes(4))
        rt.commit()
        factory.advance(rt, Duration(100))
        rt.touch(IFACE, ORD)
        rt.commit()

        val buf = out(8)
        val raw = rt.read(IFACE, ORD, buf)
        assertArrayEquals(array(4), buf.written(), "touch carries no new value")
        assertEquals(later(start, 100), raw.envelope.stamp, "touch re-affirms at the new time")
        assertEquals(2uL, raw.envelope.seq, "touch is a publication of the channel")
    }

    /**
     * `touch` re-affirms the current value. A `set` already staged is itself a
     * publication, so a touch after it adds nothing — and must not replace it,
     * which would discard the value this writer staged.
     */
    public fun `a touch does not discard a value staged before it`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(7))
        rt.touch(IFACE, ORD)
        rt.commit()

        val buf = out(8)
        assertEquals(Provenance.Live, rt.read(IFACE, ORD, buf).provenance)
        assertArrayEquals(array(7), buf.written())
    }

    /** The same rule for an `invalidate` staged before the touch. */
    public fun `a touch does not discard an invalidation staged before it`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1))
        rt.commit()
        rt.invalidate(IFACE, ORD)
        rt.touch(IFACE, ORD)
        rt.commit()

        val buf = out(8)
        assertEquals(Provenance.Invalid(Cause.Declared), rt.read(IFACE, ORD, buf).provenance)
        assertArrayEquals(array(1), buf.written())
    }

    /** The other direction: a `set` is a newer decision about the channel than a touch already staged, so it replaces it. */
    public fun `a set after a touch replaces it`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1))
        rt.commit()
        rt.touch(IFACE, ORD)
        rt.set(IFACE, ORD, bytes(2))
        rt.commit()

        val buf = out(8)
        rt.read(IFACE, ORD, buf)
        assertArrayEquals(array(2), buf.written())
    }

    /**
     * A re-affirmation of nothing is nothing. Publishing here would put a
     * zero-length value on the channel as `Live`, which a consumer's binding
     * reads as a corrupt payload rather than as the init value it should see.
     * That such a commit also advances no generation is a test of
     * [ScannableContract].
     */
    public fun `touch on a channel with no publication publishes nothing`() {
        val rt = runtime()
        rt.touch(IFACE, ORD)
        rt.commit()
        assertEquals(Provenance.Init, rt.read(IFACE, ORD, out(8)).provenance)
    }

    /** A read into a buffer shorter than the value reports the size it needs and consumes nothing. */
    public fun `a short buffer reports what the read needs and consumes nothing`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1, 2, 3, 4))
        rt.commit()

        assertEquals(ReadError.Short(4), assertThrows<ReadError.Short> { rt.read(IFACE, ORD, out(2)) })
        assertEquals(4, rt.read(IFACE, ORD, out(8)).len)
    }
}
