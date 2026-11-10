// Restricted port delegation for a descriptor attachment: the spelling of
// `crates/ridl-loopback/src/node/attachment.rs` at the pinned release.
package ridl.rt.loopback

import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.encoding.Encoding
import ridl.rt.port.Caller
import ridl.rt.port.Changed
import ridl.rt.port.Check
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.CoherentSignals
import ridl.rt.port.Correlation
import ridl.rt.port.Encoded
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.FixedReader
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.RaiseError
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ScannableSignals
import ridl.rt.port.ServeError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.Wakeable
import ridl.rt.port.Watermark
import ridl.rt.port.WriteError
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import ridl.rt.trace.TraceContext
import java.nio.ByteBuffer

/**
 * One reserved identity and its ports for one granted catalog.
 * `ridl_loopback::Attachment`.
 *
 * It presents the ports of the Rust attachment, over role handles of its own
 * on the catalog's store: no handle can be taken out of it. A listener can
 * read, subscribe and call, but cannot publish, raise, serve or take a claim:
 * `set`, `invalidate`, `touch`, `raise` and `serve` throw `NotOwner`, its
 * `commit` does nothing and its `nextClaim` is always `null`.
 *
 * [close] is the Rust drop: it closes the attachment's handles, which returns
 * the claims its handler held and wakes another serving handler, and only then
 * releases the identity, so a replacement cannot overlap the ports. A second
 * close does nothing.
 */
public class Attachment internal constructor(
    private val ports: Handles,
    private val reservation: Reservation,
    private val listener: Boolean,
) : Encoded, Clock, SignalReader, FixedReader, ScannableSignals, CoherentSignals, SignalWriter, EventSource, EventSink,
    Caller, Handler, Wakeable, AutoCloseable {
    override val catalog: CatalogRef get() = ports.reader.catalog

    override val encoding: Encoding get() = ports.reader.encoding

    override fun now(): Timestamp = ports.reader.now()

    override fun checkWith(iface: InterfaceNo, ord: Ordinal, check: Check): Unit = ports.reader.checkWith(iface, ord, check)

    override fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample = ports.reader.read(iface, ord, out)

    override fun readFixed(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): Int = ports.reader.readFixed(iface, ord, out)

    override fun generation(iface: InterfaceNo): ULong = ports.reader.generation(iface)

    override fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int = ports.reader.scan(marks, out)

    override fun readCoherent(iface: InterfaceNo, ords: List<Ordinal>, out: ByteBuffer, samples: Array<RawSample?>): Int =
        ports.reader.readCoherent(iface, ords, out, samples)

    override fun set(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        if (listener) throw WriteError.NotOwner
        ports.writer.set(iface, ord, bytes)
    }

    override fun invalidate(iface: InterfaceNo, ord: Ordinal) {
        if (listener) throw WriteError.NotOwner
        ports.writer.invalidate(iface, ord)
    }

    override fun touch(iface: InterfaceNo, ord: Ordinal) {
        if (listener) throw WriteError.NotOwner
        ports.writer.touch(iface, ord)
    }

    override fun commit() {
        if (!listener) ports.writer.commit()
    }

    override fun subscribe(iface: InterfaceNo, ords: List<Ordinal>): Unit = ports.source.subscribe(iface, ords)

    override fun unsubscribe(iface: InterfaceNo, ords: List<Ordinal>): Unit = ports.source.unsubscribe(iface, ords)

    override fun next(out: ByteBuffer): RawOccurrence? = ports.source.next(out)

    override fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer, trace: TraceContext?) {
        if (listener) throw RaiseError.NotOwner
        ports.sink.raise(iface, ord, bytes, trace)
    }

    override fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer, trace: TraceContext?): Correlation =
        ports.caller.command(iface, ord, args, trace)

    override fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer, trace: TraceContext?): Correlation =
        ports.caller.query(iface, ord, args, trace)

    override fun ack(c: Correlation): Result<Unit>? = ports.caller.ack(c)

    override fun reply(c: Correlation, out: ByteBuffer): Result<Int>? = ports.caller.reply(c, out)

    override fun forget(c: Correlation): Unit = ports.caller.forget(c)

    override fun serve(iface: InterfaceNo, ords: List<Ordinal>) {
        if (listener) throw ServeError.NotOwner
        ports.handler.serve(iface, ords)
    }

    override fun nextClaim(out: ByteBuffer): Claim? = if (listener) null else ports.handler.nextClaim(out)

    override fun settle(claim: ClaimId, outcome: Result<ByteBuffer>): Unit = ports.handler.settle(claim, outcome)

    // As `Loopback.wakeOn` routes it. The Rust attachment has no stream port,
    // so its stream interests reach handles nothing on it can pull or push.
    override fun wakeOn(what: Interest, waker: Waker): Unit = when (what) {
        is Interest.Outcome, Interest.Slot, is Interest.Stream -> ports.caller.wakeOn(what, waker)
        is Interest.Event -> ports.source.wakeOn(what, waker)
        is Interest.Claim, is Interest.ClaimStream -> ports.handler.wakeOn(what, waker)
    }

    override fun close() {
        ports.source.close()
        ports.caller.close()
        ports.handler.close()
        reservation.release()
    }
}
