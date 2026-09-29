// `ridl-rt-kt-loopback`, the in-process reference runtime: the spelling of
// `crates/ridl-loopback/src/lib.rs` (docs/design.md §3, §6).
package ridl.rt.loopback

import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.port.Caller
import ridl.rt.port.Changed
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.CoherentSignals
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.FixedReader
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SignalWriter
import ridl.rt.port.Wakeable
import ridl.rt.port.Watermark
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer

/** The six role handles of one runtime, as [Loopback.split] hands them out. */
public class Handles internal constructor(
    /** `Attached`, `Clock`, `SignalReader`, `FixedReader`, `ScannableSignals`, `CoherentSignals` and `Wakeable`. */
    public val reader: ReaderHandle,
    /** `SignalWriter` and `Wakeable`. */
    public val writer: WriterHandle,
    /** `EventSource` and `Wakeable`. */
    public val source: SourceHandle,
    /** `EventSink` and `Wakeable`. */
    public val sink: SinkHandle,
    /** `Caller`, `Clock` and `Wakeable`. */
    public val caller: CallerHandle,
    /** `Handler` and `Wakeable`. */
    public val handler: HandlerHandle,
)

/**
 * The in-process reference runtime: every port of `ridl-rt-kt` over one
 * in-memory store, and the aggregate handle that implements all twelve port
 * interfaces by delegating to the six role handles it holds. It is what a
 * generated `Client`, `Publisher` or `dispatch` is built over in a test.
 *
 * It is not a transport — nothing leaves the process — and not a checker:
 * payload bytes are opaque to it. It holds no catalog descriptor, so it
 * never reports `NotOwner`, never a `Contract` error except from an
 * unprovisioned [readFixed], and every value's freshness is `Unbounded`.
 * Nothing detaches, so `Detached` never appears. The one bound is the call
 * table's [SLOTS]: a send with every slot taken throws `SendError.Busy`.
 * Nothing else is bounded, so `TooLarge` appears only from [failNextSettle],
 * and a `Transport.Busy` reaches a caller only when a provider settles a call
 * with it.
 *
 * The catalog is carried and never compared: checking it against an
 * interface's own is a generated face's job (ADR-0021 decision 3).
 *
 * It also presents [Wakeable], and routes each key to the role handle that
 * observes it: `Outcome` and `Slot` to the caller, `Event` to the source,
 * `Claim` to the handler.
 */
public class Loopback(
    override val catalog: CatalogRef,
) : Clock, SignalWriter, EventSource, EventSink, Caller, Handler, FixedReader, ScannableSignals, CoherentSignals,
    Wakeable {
    private val store = Store()
    private var held: Handles? = Handles(
        reader = ReaderHandle(store, catalog),
        writer = WriterHandle(store, catalog),
        source = SourceHandle(store, catalog),
        sink = SinkHandle(store, catalog),
        caller = CallerHandle(store, catalog),
        handler = HandlerHandle(store, catalog),
    )

    private val handles: Handles
        get() = checkNotNull(held) { "this Loopback was split; use the handles split() returned" }

    /**
     * Hands out the six role handles this aggregate holds, all over the same
     * store. The aggregate's own port methods refuse afterwards, as the Rust
     * `split` consumes the aggregate; [reader], [writer] and the other
     * factories, [advance], [provisionFixed] and [failNextSettle] still work.
     */
    public fun split(): Handles = handles.also { held = null }

    /** An additional reader handle on the same store. */
    public fun reader(): ReaderHandle = ReaderHandle(store, catalog)

    /** An additional writer handle, with its own staging area and sequence counters. */
    public fun writer(): WriterHandle = WriterHandle(store, catalog)

    /** An additional event source, with its own subscription set and queue. */
    public fun source(): SourceHandle = SourceHandle(store, catalog)

    /** An additional event sink, with its own sequence counters. */
    public fun sink(): SinkHandle = SinkHandle(store, catalog)

    /** An additional caller, with its own sequence counter (driftsys/ridl#308). */
    public fun caller(): CallerHandle = CallerHandle(store, catalog)

    /**
     * An additional handler. Every handler draws from the one queue of
     * waiting calls, filtered by what it has served.
     */
    public fun handler(): HandlerHandle = HandlerHandle(store, catalog)

    /**
     * Advances the clock by [by]. The clock is a counter only this moves, so
     * a round trip produces the same timestamps on every run; it saturates
     * rather than overflowing.
     *
     * @throws IllegalArgumentException when [by] is negative.
     */
    public fun advance(by: Duration) {
        store.locked { advance(by) }
    }

    /**
     * Supplies the value of a `fixed` (ridl §8). Until one is provisioned,
     * reading it throws `ReadError.Contract(Contract.UnknownInteraction)`.
     */
    public fun provisionFixed(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        val copy = bytes.remainingBytes()
        store.locked { provisionFixed(Key(iface, ord), copy) }
    }

    /**
     * Makes the next `settle` of a claim that exists throw
     * `SettleError.TooLarge` and record no outcome: the one fault this
     * runtime injects, so the generated `dispatch`'s count of accepted
     * settlements can be tested.
     */
    public fun failNextSettle() {
        store.locked { failNextSettle() }
    }

    // The twelve port implementations, each one a delegation.

    override fun wakeOn(what: Interest, waker: Waker): Unit = when (what) {
        is Interest.Outcome, Interest.Slot -> handles.caller.wakeOn(what, waker)
        is Interest.Event -> handles.source.wakeOn(what, waker)
        is Interest.Claim -> handles.handler.wakeOn(what, waker)
    }

    override fun now(): Timestamp = handles.reader.now()

    override fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample =
        handles.reader.read(iface, ord, out)

    override fun readFixed(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): Int =
        handles.reader.readFixed(iface, ord, out)

    override fun generation(iface: InterfaceNo): ULong = handles.reader.generation(iface)

    override fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int = handles.reader.scan(marks, out)

    override fun readCoherent(
        iface: InterfaceNo,
        ords: List<Ordinal>,
        out: ByteBuffer,
        samples: Array<RawSample?>,
    ): Int = handles.reader.readCoherent(iface, ords, out, samples)

    override fun set(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer): Unit = handles.writer.set(iface, ord, bytes)

    override fun invalidate(iface: InterfaceNo, ord: Ordinal): Unit = handles.writer.invalidate(iface, ord)

    override fun touch(iface: InterfaceNo, ord: Ordinal): Unit = handles.writer.touch(iface, ord)

    override fun commit(): Unit = handles.writer.commit()

    override fun subscribe(iface: InterfaceNo, ords: List<Ordinal>): Unit = handles.source.subscribe(iface, ords)

    override fun unsubscribe(iface: InterfaceNo, ords: List<Ordinal>): Unit = handles.source.unsubscribe(iface, ords)

    override fun next(out: ByteBuffer): RawOccurrence? = handles.source.next(out)

    override fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer): Unit = handles.sink.raise(iface, ord, bytes)

    override fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation =
        handles.caller.command(iface, ord, args)

    override fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation =
        handles.caller.query(iface, ord, args)

    override fun ack(c: Correlation): Result<Unit>? = handles.caller.ack(c)

    override fun reply(c: Correlation, out: ByteBuffer): Result<Int>? = handles.caller.reply(c, out)

    override fun forget(c: Correlation): Unit = handles.caller.forget(c)

    override fun serve(iface: InterfaceNo, ords: List<Ordinal>): Unit = handles.handler.serve(iface, ords)

    override fun nextClaim(out: ByteBuffer): Claim? = handles.handler.nextClaim(out)

    override fun settle(claim: ClaimId, outcome: Result<ByteBuffer>): Unit = handles.handler.settle(claim, outcome)

    public companion object {
        /**
         * The number of calls the runtime holds at once: sent, and not yet
         * released by `Caller.forget` or by the close of the caller handle
         * that sent them. A settled call keeps its slot until it is released.
         * With every slot taken, `command` and `query` throw `SendError.Busy`
         * on every caller handle, because the table is the runtime's.
         *
         * Sixteen is small on purpose, so a test reaches it in a few sends and
         * a program that never forgets a call finds out at once. The loopback
         * has no catalog descriptor to size a byte budget from, so the slot
         * count is its only bound (note F-9 of the async face design).
         *
         * A forgotten call that no handler has claimed is withdrawn at once
         * and gives its slot back, so calls to a member no handler serves do
         * not fill the table for good; a claimed one keeps its slot until its
         * settlement.
         *
         * The generated face does not call `forget` yet. Until the clients of
         * driftsys/ridlc-gen-kotlin#7, which forget each call once they have
         * its outcome, a caller using the poll face forgets a correlation once
         * it has read the outcome — `port.forget(c.correlation)` — or it gets
         * `SendError.Busy` from its seventeenth call on. Closing the caller
         * handle forgets its calls too.
         */
        public const val SLOTS: Int = 16
    }
}
