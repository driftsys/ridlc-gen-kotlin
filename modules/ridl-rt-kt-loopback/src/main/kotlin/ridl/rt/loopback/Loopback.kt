// `ridl-rt-kt-loopback`, the in-process reference runtime: the spelling of
// `crates/ridl-loopback/src/lib.rs` (docs/design.md §3, §6).
package ridl.rt.loopback

import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.contract.StreamNo
import ridl.rt.contract.defaultCeiling
import ridl.rt.encoding.Encoding
import ridl.rt.error.CallError
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
import ridl.rt.port.Pull
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SignalWriter
import ridl.rt.port.StreamCaller
import ridl.rt.port.StreamHandler
import ridl.rt.port.Wakeable
import ridl.rt.port.Watermark
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import ridl.rt.trace.TraceContext
import java.nio.ByteBuffer

/** The six role handles of one runtime, as [Loopback.split] hands them out, each carrying the runtime's encoding. */
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
 * in-memory store, and the aggregate handle that implements all fourteen port
 * interfaces by delegating to the six role handles it holds. It is what a
 * generated `Client`, `Publisher` or `dispatch` is built over in a test.
 *
 * It is not a transport — nothing leaves the process — and not a payload type
 * owner: it names no payload type and never verifies a payload on its own. It
 * calls the check a generated binding registers for a signal
 * (`SignalReader.checkWith`) on each payload it commits for that signal. It
 * holds no catalog descriptor, so it never reports `NotOwner`, and every
 * value's freshness is `Unbounded`. An unprovisioned [readFixed] reports a
 * `Contract` error, as do stream operations on an unknown call or claim, or
 * from the wrong side of a stream.
 * Nothing detaches, so `Detached` never appears. Three things are bounded. A
 * stream's [STREAM_WINDOW]: a push into a full one returns 0. The
 * call table's [SLOTS]: a send with every slot taken throws `SendError.Busy`.
 * And the size of a payload: a sending port refuses a payload longer than its
 * member's limit with `TooLarge` — the limit [setLimit] gave the member, else
 * the default ceiling of the port's kind (ADR-0030 decision 16) — and
 * [provisionFixed] refuses a `fixed` the same way. `TooLarge` comes from that
 * limit, and from [failNextSettle], which reports it with `cap` 0. A
 * `Transport.Busy` reaches a caller only when a provider settles a call with
 * it.
 *
 * The catalog is carried and never compared: checking it against an
 * interface's own is a generated face's job, made once per binding
 * (ADR-0021 decision 3, ADR-0023 decision 8).
 *
 * It also presents [Wakeable], and routes each key to the role handle that
 * observes it: `Outcome` and `Slot` to the caller, `Event` to the source,
 * `Claim` to the handler.
 *
 * [attach] makes a second aggregate on the same store, for a program that
 * holds several faces over one runtime, each owning its own.
 *
 * Every aggregate and every role handle presents [Encoded], with the
 * [encoding] the runtime was made with: `Loopback<E>`, `Handles<E>` and the
 * six `<Role>Handle<E>` of Rust, whose type parameter is a value here.
 */
public class Loopback private constructor(
    override val catalog: CatalogRef,
    override val encoding: Encoding,
    private val store: Store,
) : Encoded, Clock, SignalWriter, EventSource, EventSink, StreamCaller, StreamHandler, FixedReader, ScannableSignals,
    CoherentSignals, Wakeable {
    /**
     * A runtime over a new, empty store, whose ports carry [encoding]:
     * FlatBuffers unless another is named. `Loopback::new`, and
     * `Loopback::<E>::with_encoding` when [encoding] is given. The bytes stay
     * opaque to the runtime; the encoding is what its ports state through
     * [Encoded], so that a face built over one reads it (driftsys/ridl#887).
     */
    public constructor(catalog: CatalogRef, encoding: Encoding = Encoding.FlatBuffers) : this(catalog, encoding, Store())

    private var held: Handles? = Handles(
        reader = ReaderHandle(store, catalog, encoding),
        writer = WriterHandle(store, catalog, encoding),
        source = SourceHandle(store, catalog, encoding),
        sink = SinkHandle(store, catalog, encoding),
        caller = CallerHandle(store, catalog, encoding),
        handler = HandlerHandle(store, catalog, encoding),
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

    /**
     * An additional aggregate on the same store: six new role handles, made
     * the way [reader] to [handler] make one each. This is how an application
     * holds several faces over one runtime — a client, a publisher, a second
     * client with a call of its own in flight — each owning its own aggregate
     * (driftsys/ridl#488).
     *
     * What is in the store is shared: the published signals, the `fixed`
     * values, the clock and the call table. What is on a handle is not carried
     * over: the attached aggregate starts subscribed to nothing, with no value
     * staged, no call sent and nothing served. Closing its handles closes only
     * those handles. It works on a split aggregate too, as the factories do.
     */
    public fun attach(): Loopback = Loopback(catalog, encoding, store)

    /** An additional reader handle on the same store. */
    public fun reader(): ReaderHandle = ReaderHandle(store, catalog, encoding)

    /** An additional writer handle, with its own staging area and sequence counters. */
    public fun writer(): WriterHandle = WriterHandle(store, catalog, encoding)

    /** An additional event source, with its own subscription set and queue. */
    public fun source(): SourceHandle = SourceHandle(store, catalog, encoding)

    /** An additional event sink, with its own sequence counters. */
    public fun sink(): SinkHandle = SinkHandle(store, catalog, encoding)

    /** An additional caller, with its own sequence counter (driftsys/ridl#308). */
    public fun caller(): CallerHandle = CallerHandle(store, catalog, encoding)

    /**
     * An additional handler. Every handler draws from the one queue of
     * waiting calls, filtered by what it has served.
     */
    public fun handler(): HandlerHandle = HandlerHandle(store, catalog, encoding)

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
     * reading it throws `ReadError.Contract(Contract.UnknownInteraction)`. A
     * `fixed` whose only provision was refused reads the same way.
     *
     * @throws ridl.rt.port.WriteError.TooLarge when the value is longer than
     *   the member's limit, the one [setLimit] gave it, else the default ceiling
     *   of a `fixed`, 65,280 bytes. Nothing is stored: a value provisioned
     *   before stays as it was.
     */
    public fun provisionFixed(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        val copy = bytes.remainingBytes()
        store.locked { provisionFixed(Key(iface, ord), copy) }
    }

    /**
     * Gives member [ord] of interface [iface] the limit [limit], in bytes of
     * encoded payload, in place of the default ceiling of its kind.
     *
     * The loopback holds no catalog descriptor, so this is how a program tells
     * it the limit that a `[limits]` entry of the manifest gives a member
     * (ADR-0030 decision 12). The limit is the store's: every handle and every
     * attached aggregate applies it, and a later call replaces it. A member
     * with no limit given has the default ceiling of its kind, read from the
     * port the payload is sent through ([defaultCeiling]): 3,840 bytes for a
     * signal and an event, 16,128 for a command and a query, 65,280 for a
     * fixed.
     *
     * A sending port refuses a payload longer than the member's limit with
     * its `TooLarge`, whose `cap` is that limit, and nothing is staged, raised,
     * sent or recorded. The receiving side of this runtime reads only what its
     * sending side accepted, so it adds no check.
     */
    public fun setLimit(iface: InterfaceNo, ord: Ordinal, limit: Int) {
        store.locked { setLimit(Key(iface, ord), limit) }
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

    // The fourteen port implementations, each one a delegation.

    override fun wakeOn(what: Interest, waker: Waker): Unit = when (what) {
        is Interest.Outcome, Interest.Slot, is Interest.Stream -> handles.caller.wakeOn(what, waker)
        is Interest.Event -> handles.source.wakeOn(what, waker)
        is Interest.Claim, is Interest.ClaimStream -> handles.handler.wakeOn(what, waker)
    }

    override fun now(): Timestamp = handles.reader.now()

    override fun checkWith(iface: InterfaceNo, ord: Ordinal, check: Check): Unit = handles.reader.checkWith(iface, ord, check)

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

    override fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer, trace: TraceContext?): Unit =
        handles.sink.raise(iface, ord, bytes, trace)

    override fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer, trace: TraceContext?): Correlation =
        handles.caller.command(iface, ord, args, trace)

    override fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer, trace: TraceContext?): Correlation =
        handles.caller.query(iface, ord, args, trace)

    override fun ack(c: Correlation): Result<Unit>? = handles.caller.ack(c)

    override fun reply(c: Correlation, out: ByteBuffer): Result<Int>? = handles.caller.reply(c, out)

    override fun pull(c: Correlation, s: StreamNo, out: ByteBuffer): Pull = handles.caller.pull(c, s, out)

    override fun push(c: Correlation, s: StreamNo, chunk: ByteBuffer): Int = handles.caller.push(c, s, chunk)

    override fun end(c: Correlation, s: StreamNo): Unit = handles.caller.end(c, s)

    override fun abort(c: Correlation, s: StreamNo, e: CallError): Unit = handles.caller.abort(c, s, e)

    override fun pullClaim(claim: ClaimId, s: StreamNo, out: ByteBuffer): Pull = handles.handler.pullClaim(claim, s, out)

    override fun pushClaim(claim: ClaimId, s: StreamNo, chunk: ByteBuffer): Int = handles.handler.pushClaim(claim, s, chunk)

    override fun endClaim(claim: ClaimId, s: StreamNo): Unit = handles.handler.endClaim(claim, s)

    override fun abortClaim(claim: ClaimId, s: StreamNo, e: CallError): Unit = handles.handler.abortClaim(claim, s, e)

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

        /**
         * The number of non-empty chunks one stream of a call holds, pushed
         * and not yet pulled. A push of a non-empty chunk into a stream that
         * holds this many returns 0 and takes nothing. Pulling a non-empty
         * chunk frees room for one; pulling an empty chunk does not. An empty
         * chunk is always taken, whatever the window holds. Four is small, so
         * that a test reaches the bound in a few pushes, as with [SLOTS].
         */
        public const val STREAM_WINDOW: Int = 4
    }
}
