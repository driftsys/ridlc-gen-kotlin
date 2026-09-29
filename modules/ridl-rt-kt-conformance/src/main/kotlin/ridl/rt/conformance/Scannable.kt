// The `ScannableSignals` extension: the interface generation and `scan`.
// `crates/ridl-rt-conformance/src/scannable.rs`. A runtime may omit the
// extension, so these tests run only through [scannableSuite].
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import ridl.rt.contract.InterfaceNo
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Changed
import ridl.rt.port.Clock
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.Watermark
import kotlin.reflect.KFunction0

/** The tests of `ScannableSignals`. */
public class ScannableContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : ScannableSignals {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a commit advances the interface generation once`,
        ::`a commit of only touches on unpublished channels changes no generation`,
        ::`scan reports the changes since a mark and moves it forward`,
        ::`scan writes an interface changes all together or not at all`,
    )

    private fun mark() = Watermark(IFACE, 0u, 0u)

    /** A commit advances the generation of each interface it publishes to once, however many of its signals it carries, and no other interface's. */
    public fun `a commit advances the interface generation once`() {
        val rt = runtime()
        assertEquals(0uL, rt.generation(IFACE), "no publication yet")

        rt.set(IFACE, ORD, bytes(1))
        rt.set(IFACE, OTHER, bytes(2))
        rt.commit()
        assertEquals(1uL, rt.generation(IFACE), "two signals in one commit advance the generation once")

        rt.set(IFACE, ORD, bytes(3))
        rt.commit()
        assertEquals(2uL, rt.generation(IFACE))
        assertEquals(0uL, rt.generation(InterfaceNo(2u)), "another interface is untouched")
    }

    /** A commit whose every staged change is a `touch` on a channel with no publication publishes nothing, so it advances no generation either. */
    public fun `a commit of only touches on unpublished channels changes no generation`() {
        val rt = runtime()
        rt.touch(IFACE, ORD)
        rt.commit()
        assertEquals(0uL, rt.generation(IFACE), "a commit whose every staged change was such a touch changes nothing")
    }

    /** `scan` reports each signal changed since a mark, moves the mark to the current generation, and reports nothing more for a moved mark. */
    public fun `scan reports the changes since a mark and moves it forward`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1))
        rt.set(IFACE, OTHER, bytes(2))
        rt.commit()

        val marks = arrayOf(mark())
        val out = arrayOfNulls<Changed>(4)
        assertEquals(2, rt.scan(marks, out))
        assertEquals(ORD, out[0]!!.ord)
        assertEquals(OTHER, out[1]!!.ord)
        assertEquals(1uL, marks[0].generation, "the mark moved to the current generation")
        assertEquals(1uL, marks[0].seq)

        assertEquals(0, rt.scan(marks, out), "a second scan with the moved mark reports nothing")

        rt.set(IFACE, OTHER, bytes(3))
        rt.commit()
        assertEquals(1, rt.scan(marks, out), "only the signal that changed since the mark")
        assertEquals(OTHER, out[0]!!.ord)
        assertEquals(2uL, out[0]!!.seq)
    }

    /** An interface's changes are written all together or not at all, and a mark whose changes did not fit is not moved. */
    public fun `scan writes an interface changes all together or not at all`() {
        val rt = runtime()
        rt.set(IFACE, ORD, bytes(1))
        rt.set(IFACE, OTHER, bytes(2))
        rt.commit()

        val marks = arrayOf(mark())
        assertEquals(0, rt.scan(marks, arrayOfNulls(1)), "two changes do not fit in one entry, so none is written")
        assertEquals(0uL, marks[0].generation, "and the mark is not moved")

        // Growing the output is what makes progress, the loop `ScannableSignals.scan` documents.
        assertEquals(2, rt.scan(marks, arrayOfNulls(2)))
        assertEquals(1uL, marks[0].generation)
    }
}
