// #78: a stand-in for a shared-memory runtime's signal channel, the case the
// issue is about: a read is two loads of a sequence counter around a copy, and
// a clock read for the freshness, as a runtime computes it on every read
// (frame specification §8). Only `temperature` has a channel; everything else
// a `CabinAsyncClient` needs is the loopback's.
package ridl.sample.cabin

import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.loopback.Loopback
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.EventSource
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SignalReader
import ridl.rt.port.Wakeable
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import veh.cabin.CabinTemperature
import veh.cabin.TemperatureCodec
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class SeqlockPort(private val loopback: Loopback) :
    SignalReader,
    EventSource by loopback,
    Caller by loopback,
    Clock by loopback,
    Wakeable by loopback {
    override val catalog: CatalogRef get() = loopback.catalog

    /** Odd while the writer writes; each publication adds 2. */
    private val seq = AtomicLong()

    /** The channel, off-heap as a mapped segment would be. Written by one writer, under [seq]. */
    private val slot: ByteBuffer = ByteBuffer.allocateDirect(TemperatureCodec.maxSize)
    private var len = 0
    private var stamp = 0L

    private val epoch = System.nanoTime()

    private fun nowMicros(): Long = (System.nanoTime() - epoch) / 1_000

    /** Publishes [bytes], the encoded value. One writer at a time. */
    fun publish(bytes: ByteArray) {
        val s = seq.get()
        seq.setOpaque(s + 1)
        // The odd count is seen before any byte of the new value.
        VarHandle.releaseFence()
        slot.put(0, bytes)
        len = bytes.size
        stamp = nowMicros()
        seq.setRelease(s + 2)
    }

    override fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample {
        while (true) {
            val before = seq.getAcquire()
            if (before and 1L != 0L) {
                Thread.onSpinWait()
                continue
            }
            val n = len
            val at = stamp
            if (n > out.remaining()) {
                // A length torn by a write is read again, not reported.
                VarHandle.acquireFence()
                if (seq.get() == before) throw ReadError.Short(n) else continue
            }
            out.put(out.position(), slot, 0, n)
            // The copy is done before the count is read again.
            VarHandle.acquireFence()
            if (seq.get() != before) continue
            out.position(out.position() + n)
            val envelope = Envelope(Timestamp(at), (before / 2).toULong())
            val freshness = Freshness.of(envelope, Timestamp(nowMicros()), CabinTemperature.member.timing)
            return RawSample(Provenance.Live, freshness, envelope, n)
        }
    }
}
