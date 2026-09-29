// The `CoherentSignals` extension: several signals of one interface read from
// one publication. `crates/ridl-rt-conformance/src/coherent.rs`. A runtime may
// omit the extension, so these tests run only through [coherentSuite].
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.CoherentSignals
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import kotlin.reflect.KFunction0

/** The tests of `CoherentSignals`. */
public class CoherentContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : CoherentSignals {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a coherent read answers every ordinal from one publication`,
        ::`a coherent read reports a short output and a short sample slice`,
    )

    /** A coherent read copies every value one after another and writes one sample per ordinal, all from one publication. */
    public fun `a coherent read answers every ordinal from one publication`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1, 1))
        rt.set(IFACE, OTHER, bytes(2))
        rt.commit()

        val buf = out(8)
        val samples = arrayOfNulls<RawSample>(2)
        assertEquals(3, rt.readCoherent(IFACE, listOf(ORD, OTHER), buf, samples))
        assertArrayEquals(array(1, 1, 2), buf.written())
        assertEquals(2, samples[0]!!.len)
        assertEquals(1, samples[1]!!.len)
        assertEquals(samples[0]!!.envelope.stamp, samples[1]!!.envelope.stamp, "both values come from one publication")
    }

    /** A short output reports the whole set's size, and a short sample slice reports the entries it needs. */
    public fun `a coherent read reports a short output and a short sample slice`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1, 1))
        rt.set(IFACE, OTHER, bytes(2))
        rt.commit()

        assertEquals(
            ReadError.Short(3),
            assertThrows<ReadError.Short>("the whole set's size, not the first value's") {
                rt.readCoherent(IFACE, listOf(ORD, OTHER), out(2), arrayOfNulls(2))
            },
        )
        assertEquals(
            ReadError.TooFewSamples(2),
            assertThrows<ReadError.TooFewSamples> { rt.readCoherent(IFACE, listOf(ORD, OTHER), out(8), arrayOfNulls(1)) },
        )
    }
}
