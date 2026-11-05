// The one store every handle shares, and the operations the handles call on
// it: the spelling of `crates/ridl-loopback/src/store.rs`.
//
// State two handles must agree on lives here — the signal map, the event
// queues, the call table and the clock; state that belongs to one handle alone
// lives on that handle. Every operation below runs under the store's monitor,
// reads or writes the maps and returns: no port method waits for data while
// holding it, which is what `ridl.rt.port` requires of every port method.
//
// No waker runs under the monitor either. Every operation here that would wake
// a task returns the wakers instead, and the handle wakes them after leaving
// the monitor, so a waker that reaches back into this runtime from another
// thread cannot find the monitor held by the thread that woke it. A waker is
// stored here although one handle registered it, because another handle's
// operation wakes it.
package ridl.rt.loopback

import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Kind
import ridl.rt.contract.Ordinal
import ridl.rt.contract.StreamNo
import ridl.rt.contract.defaultCeiling
import ridl.rt.correlate.Forgotten
import ridl.rt.correlate.Settled
import ridl.rt.correlate.Table
import ridl.rt.correlate.Waiters
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.port.Changed
import ridl.rt.port.Check
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.port.Pull
import ridl.rt.port.PushError
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.RaiseError
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.port.WriteError
import ridl.rt.port.Watermark
import ridl.rt.sample.Cause
import ridl.rt.sample.Detection
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import ridl.rt.task.noopWaker
import ridl.rt.trace.TraceContext
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.TreeMap
import java.util.TreeSet

/** One interaction, addressed the way every port method addresses one. */
internal data class Key(val iface: InterfaceNo, val ord: Ordinal) : Comparable<Key> {
    override fun compareTo(other: Key): Int = compareValuesBy(this, other, { it.iface }, { it.ord })
}

/** A staged change, held on the writer handle until its `commit`. */
internal sealed interface Staged {
    class Set(val bytes: ByteArray) : Staged

    data object Invalidate : Staged

    data object Touch : Staged
}

/** The outcome a forget records on every stream of the call. */
private val INTERRUPTED: CallError = Transport.Interrupted

/** One side of a call's streams: the caller, which holds the correlation, or the provider, which holds the claim. */
internal enum class Side {
    Caller,
    Provider,
    ;

    val other: Side get() = if (this == Caller) Provider else Caller

    companion object {
        /** The side that pushes to stream [s]: the provider for the reply stream, and the caller for a stream parameter. */
        fun producer(s: StreamNo): Side = if (s == REPLY_STREAM) Provider else Caller
    }
}

/**
 * One stream of one call: the chunks pushed and not yet pulled, and how the
 * stream stopped, if it did. The caller's `Interest.Stream` waker of the stream
 * is kept in the call table, with the call; the provider's
 * `Interest.ClaimStream` waker is kept here.
 */
private class StreamState {
    val chunks = ArrayDeque<ByteArray>()

    /** `true` once the producer ended the stream. */
    var ended = false

    /** The outcome the stream stopped with and the side that stopped it: an abort, a settlement with an error of the reply stream, or a forget. */
    var stopped: Pair<CallError, Side>? = null

    /** The provider's `Interest.ClaimStream` waker. */
    var claimWaker: Waker? = null

    /** `true` once the provider pulled `End` or `Failed` from the stream. */
    var drained = false

    /** Whether the window has no room for another non-empty chunk. */
    val full: Boolean get() = chunks.count { it.isNotEmpty() } >= Loopback.STREAM_WINDOW

    /** Whether the stream ended or stopped. */
    val finished: Boolean get() = ended || stopped != null

    /**
     * What a push, an end or an abort of [side] answers when the stream no
     * longer takes one: `Failed` with the outcome the other side stopped it
     * with, and `Ended` after its end or this side's own stop.
     */
    fun closed(side: Side): PushError? {
        val (error, by) = stopped ?: return if (ended) PushError.Ended else null
        return if (by != side) PushError.Failed(error) else PushError.Ended
    }

    /**
     * Drops the unread chunks of a stream parameter the provider is done with,
     * stops it with `Transport.Interrupted` by the provider's side unless it
     * already finished, and counts it as read.
     */
    fun release() {
        chunks.clear()
        if (!finished) stopped = INTERRUPTED to Side.Provider
        drained = true
    }

    fun takeClaimWaker(): Waker? = claimWaker.also { claimWaker = null }
}

internal enum class CallKind(
    /** The interaction kind of the call, which names its default ceiling. */
    val kind: Kind,
) {
    Command(Kind.Command),
    Query(Kind.Query),
}

/**
 * Everything two handles must agree on.
 *
 * The maps are sorted, as the Rust `BTreeMap`s are, so a commit applies its
 * staged changes and a scan reports its changes in one order on every run.
 */
internal class Store {
    private class SignalEntry(
        val bytes: ByteArray,
        val envelope: Envelope,
        /** The provenance of the last publication, including a rejected `set`. */
        val provenance: Provenance,
        /** The interface generation this entry last changed at, which [scan] selects on. */
        val changedAt: ULong,
    )

    private class QueuedEvent(val key: Key, val bytes: ByteArray, val envelope: Envelope, val trace: TraceContext?)

    /**
     * One source handle's subscription set and its own queue. `raise` copies
     * an occurrence into the queue of every source subscribed to it at that
     * moment, so no source consumes another's.
     */
    private class SourceState {
        val subscribed = TreeSet<Key>()
        val queue = ArrayDeque<QueuedEvent>()

        /**
         * The one `Interest.Event` waker this handle holds, whatever interface
         * it was registered under: any occurrence queued here wakes it.
         */
        var waiter: Waker? = null
    }

    /** One handler handle's served set and its one `Interest.Claim` waker. */
    private class HandlerState {
        /** The members `serve` was called with, in the order served, with no duplicate. */
        val served = LinkedHashSet<Key>()

        /**
         * The one `Interest.Claim` waker this handle holds, whatever interface
         * it was registered under: any call it serves wakes it.
         */
        var waiter: Waker? = null

        /**
         * Whether `nextClaim` presents a call on [key]: every call when
         * nothing is served, the Rust loopback's deliberate deviation from
         * `Handler.serve`, kept because the generated `dispatch` never calls
         * `serve`.
         */
        fun serves(key: Key): Boolean = served.isEmpty() || key in served
    }

    /**
     * A presented claim: the call it presented, the handler holding it, and
     * whether the handler took it. A taken call has left the waiting calls;
     * an offered one is still among them, because `nextClaim` offered it
     * through `ReadError.ShortClaim` and has not copied its arguments
     * (driftsys/ridl#569).
     */
    private class ClaimOwner(val call: Correlation, val handler: Int, val taken: Boolean)

    /**
     * What the loopback keeps for one sent call, by its slot in the call
     * table, from the caller's send until the table reclaims the slot. The
     * outcome status, whether the call was forgotten, and its
     * `Interest.Outcome` waker are the table's.
     *
     * A forgotten call that a handler holds keeps its entry until its
     * settlement reclaims the slot, because a claim already presented is the
     * provider's and is still settled. A forgotten call that no handler holds
     * is withdrawn: its entry goes with its slot, at the `forget` for a
     * waiting call and at the handler's close for a claimed one.
     */
    private class CallEntry(
        /** The call's correlation, which a closed caller's calls are forgotten by. */
        val correlation: Correlation,
        /** The caller handle that sent the call. */
        val caller: Int,
        val kind: CallKind,
        val key: Key,
        val args: ByteArray,
        val envelope: Envelope,
        /** The trace context the call was sent with, delivered on its claim and on its `ShortClaim`. */
        val trace: TraceContext?,
        /** The call's place in send order, which a returned claim goes back in by; a reused slot's correlation does not give it. */
        val sent: Long,
    ) {
        /** The bytes of a successful settlement. Empty until then. */
        var reply: ByteArray = ByteArray(0)

        /**
         * `true` once the caller forgot the call while a handler held it. The
         * table keeps the same mark but has no query for it; a closed
         * handler's claim is withdrawn rather than returned by this one.
         */
        var forgotten: Boolean = false

        /**
         * The claim id minted when the call was first presented, through
         * `ReadError.ShortClaim` or through a returned claim, and reused by
         * every later presentation of the same call, so a provider that
         * received `ShortClaim` reads or settles the call under the id it was
         * given. `null` until then.
         */
        var claim: Long? = null

        /**
         * The handler that settled the claim. A settled claim's streams go on
         * until the call is forgotten — a command is settled at its
         * acknowledgment — so this handler still addresses them by the claim
         * id.
         */
        var settledBy: Int? = null

        /** The call's streams, by stream number. A stream has an entry from the first operation that names it. */
        val streams = TreeMap<StreamNo, StreamState>()

        /**
         * `true` while the caller's `forget` waits for the provider: the call
         * is forgotten, but the table is not told until the provider has pulled
         * every stream parameter to its end or its handler is closed, so the
         * slot is not reclaimed before then. [forgotten] is set as well.
         */
        var pendingForget: Boolean = false

        /** `true` once a query was settled, or a command settled with an error: its stream parameters are released. */
        var parametersReleased: Boolean = false

        /**
         * `true` once the provider moved one of the call's streams: it pulled
         * a chunk, an end or a failure, pushed or ended a stream, or aborted
         * one. A closed handler's call with this mark is interrupted rather
         * than presented again, because another handler would read partial
         * streams. A pull that answers `Pending` and a registration move
         * nothing.
         */
        var providerUsedStreams: Boolean = false

        /**
         * Releases the stream parameters of a query at its settlement, and of
         * a command at a settlement with an error, which refuses the whole
         * call: the provider is done with them, so their unread chunks are
         * dropped and every unfinished one stops with `Transport.Interrupted`,
         * by the provider's side. A stream parameter first named afterwards
         * starts in the same state. The provider's waker of each released
         * stream goes on [wake], because the stream will not change again.
         */
        fun releaseParameters(wake: MutableList<Waker>) {
            parametersReleased = true
            for ((no, stream) in streams) {
                if (no != REPLY_STREAM) {
                    stream.release()
                    stream.takeClaimWaker()?.let(wake::add)
                }
            }
        }

        /** Whether the provider still has a stream parameter to pull to its end. */
        fun undrained(): Boolean = streams.any { (no, stream) -> no != REPLY_STREAM && !stream.drained }

        /**
         * Stream [s] of the call, made empty when nothing has named it yet. A
         * stream first named after the caller forgot the call starts stopped
         * with `Transport.Interrupted`, as the forget stopped every stream the
         * call had.
         */
        fun stream(s: StreamNo): StreamState = streams.getOrPut(s) {
            StreamState().also {
                if (forgotten) it.stopped = INTERRUPTED to Side.Caller
                if (parametersReleased && s != REPLY_STREAM) it.release()
            }
        }
    }

    private var now = Timestamp(0)
    private val signals = TreeMap<Key, SignalEntry>()
    private val generations = TreeMap<InterfaceNo, ULong>()
    private val fixed = TreeMap<Key, ByteArray>()
    private val sources = TreeMap<Int, SourceState>()
    private var nextSourceId = 0

    /** The call table: `Loopback.SLOTS` slots and no byte budget (note F-9). */
    private val table = Table(Loopback.SLOTS, null)

    /** The calls the table holds, by slot. */
    private val calls = TreeMap<Int, CallEntry>()

    /** The calls sent and not yet presented, in send order. */
    private val pending = ArrayList<Correlation>()
    private var nextSent = 0L

    /** Each open caller's `Interest.Slot` waker, from [openCaller] to [closeCaller]. */
    private val callers = TreeMap<Int, Waiters>()
    private var nextCallerId = 0

    /**
     * The calls presented and not yet settled, by a claim identity minted by
     * [nextClaim]: a `ClaimId` is never a call that was never presented,
     * never one already settled, and never one another handler holds. A
     * call offered through `ReadError.ShortClaim` is here and still among
     * the waiting calls; a taken call is here alone.
     */
    private val claims = TreeMap<Long, ClaimOwner>()
    private var nextClaimId = 0L
    private var nextHandlerId = 0
    private val handlers = TreeMap<Int, HandlerState>()
    private var failNextSettle = false

    /**
     * The limit of each member `setLimit` was given, in bytes. A member with
     * no entry has the default ceiling of its kind.
     */
    private val limits = TreeMap<Key, Int>()

    /** The check of each signal a binding registered one for. */
    private val checks = TreeMap<Key, Check>()

    /** Runs [block] under the store's monitor. */
    inline fun <T> locked(block: Store.() -> T): T = synchronized(this) { block() }

    /**
     * Runs [block] under the store's monitor with a list for the wakers it
     * must wake, and returns the list, for the handle to wake once the monitor
     * is left.
     */
    inline fun wakeList(block: Store.(MutableList<Waker>) -> Unit): List<Waker> =
        mutableListOf<Waker>().also { locked { block(it) } }

    // -- the member limits --------------------------------------------------

    fun setLimit(key: Key, limit: Int) {
        limits[key] = limit
    }

    /**
     * Throws what [tooLarge] makes of member [key]'s limit when a payload of
     * [len] bytes is longer than it. The limit is the one [setLimit] gave the
     * member, else the default ceiling of [kind], the kind of the port the
     * payload is sent through.
     */
    fun admit(key: Key, kind: Kind, len: Int, tooLarge: (Int) -> Throwable) {
        val cap = limits[key] ?: defaultCeiling(kind)
        if (len > cap) throw tooLarge(cap)
    }

    // -- the clock ----------------------------------------------------------

    fun now(): Timestamp = now

    fun advance(by: Duration) {
        require(by.micros >= 0) { "the clock advances forward: `by` is ${by.micros}" }
        val sum = now.micros + by.micros
        now = Timestamp(if (sum < now.micros) Long.MAX_VALUE else sum)
    }

    // -- signals ------------------------------------------------------------

    fun checkWith(key: Key, check: Check) {
        checks[key] = check
        val entry = signals[key] ?: return
        if (entry.provenance != Provenance.Live) return
        val detection = detected(key, entry.bytes) ?: return
        signals[key] = SignalEntry(ByteArray(0), entry.envelope, Provenance.Invalid(Cause.Detected(detection)), entry.changedAt)
    }

    /** What the check of [key] detected in [bytes], or `null` when they pass or it has none. */
    private fun detected(key: Key, bytes: ByteArray): Detection? = try {
        checks[key]?.check(ByteBuffer.wrap(bytes).asReadOnlyBuffer())
        null
    } catch (detection: Detection) {
        detection
    }

    fun read(key: Key, out: ByteBuffer): RawSample {
        val entry = signals[key] ?: return UNPUBLISHED
        copyInto(entry.bytes, out)
        return sampleOf(entry)
    }

    /**
     * Applies one writer handle's staged changes: one generation increment
     * and one timestamp per interface touched, and one sequence number per
     * channel published, taken from the writer's own counters.
     */
    fun commit(staged: TreeMap<Key, Staged>, seqs: MutableMap<Key, ULong>) {
        val stamp = now
        // A touch of a channel with no publication re-affirms nothing. It is
        // dropped before the generation advances, so a commit of only such
        // touches changes nothing at all.
        staged.entries.removeIf { (key, op) -> op is Staged.Touch && key !in signals }
        for (iface in staged.keys.map { it.iface }.toSortedSet()) {
            generations[iface] = (generations[iface] ?: 0u) + 1u
        }
        for ((key, op) in staged) {
            val seq = (seqs[key] ?: 0u) + 1u
            seqs[key] = seq
            val previous = signals[key]
            val kept = previous?.bytes ?: ByteArray(0)
            val (bytes, provenance) = when (op) {
                is Staged.Set -> detected(key, op.bytes)
                    ?.let { kept to Provenance.Invalid(Cause.Detected(it)) }
                    ?: (op.bytes to Provenance.Live)
                Staged.Invalidate -> kept to Provenance.Invalid(Cause.Declared)
                Staged.Touch -> previous!!.bytes to previous.provenance
            }
            signals[key] = SignalEntry(bytes, Envelope(stamp, seq), provenance, generations.getValue(key.iface))
        }
        staged.clear()
    }

    fun generation(iface: InterfaceNo): ULong = generations[iface] ?: 0u

    /**
     * Writes each interface's changes since its mark, in the order of
     * [marks], and updates the marks it wrote. An interface's changes go into
     * [out] all together or not at all; on the first that does not fit, the
     * scan stops with that mark untouched.
     */
    fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int {
        var written = 0
        for ((index, mark) in marks.withIndex()) {
            val current = generation(mark.iface)
            if (mark.generation >= current) continue
            val changes = signals
                .filter { (key, entry) -> key.iface == mark.iface && entry.changedAt > mark.generation }
                .map { (key, entry) -> Changed(key.iface, key.ord, entry.envelope.seq) }
            if (changes.size > out.size - written) return written
            for (change in changes) out[written++] = change
            marks[index] = mark.copy(generation = current)
        }
        return written
    }

    /** Answers every ordinal from the one state the monitor is held over: that is the coherence. */
    fun readCoherent(iface: InterfaceNo, ords: List<Ordinal>, out: ByteBuffer, samples: Array<RawSample?>): Int {
        if (samples.size < ords.size) throw ReadError.TooFewSamples(ords.size)
        val needed = ords.sumOf { signals[Key(iface, it)]?.bytes?.size ?: 0 }
        if (out.remaining() < needed) throw ReadError.Short(needed)
        var written = 0
        for ((index, ord) in ords.withIndex()) {
            val entry = signals[Key(iface, ord)]
            if (entry == null) {
                samples[index] = UNPUBLISHED
            } else {
                out.put(entry.bytes)
                written += entry.bytes.size
                samples[index] = sampleOf(entry)
            }
        }
        return written
    }

    // -- `fixed` ------------------------------------------------------------

    /**
     * Stores the value of a `fixed`.
     *
     * @throws WriteError.TooLarge when it is longer than the member's limit,
     *   and stores nothing: a value stored before stays as it was.
     */
    fun provisionFixed(key: Key, bytes: ByteArray) {
        admit(key, Kind.Fixed, bytes.size, WriteError::TooLarge)
        fixed[key] = bytes
    }

    fun readFixed(key: Key, out: ByteBuffer): Int {
        val bytes = fixed[key] ?: throw ReadError.Contract(Contract.UnknownInteraction)
        copyInto(bytes, out)
        return bytes.size
    }

    // -- events -------------------------------------------------------------

    fun openSource(): Int {
        val id = nextSourceId++
        sources[id] = SourceState()
        return id
    }

    fun closeSource(id: Int) {
        sources.remove(id)
    }

    fun subscribe(id: Int, iface: InterfaceNo, ords: List<Ordinal>) {
        val state = sources[id] ?: return
        ords.mapTo(state.subscribed) { Key(iface, it) }
    }

    /** Stops delivery: the ordinals leave the set, and what is queued for them is dropped. */
    fun unsubscribe(id: Int, iface: InterfaceNo, ords: List<Ordinal>) {
        val state = sources[id] ?: return
        ords.forEach { state.subscribed.remove(Key(iface, it)) }
        state.queue.removeIf { it.key !in state.subscribed }
    }

    /**
     * Copies the occurrence into the queue of every source subscribed to it
     * now. A late subscriber receives nothing retroactive, ridl's own rule
     * for a late joiner on an event. Each source it queues the occurrence for
     * has its `Event` waker returned, whatever interface that waker was
     * registered under. Every copy carries the sender's [trace].
     *
     * @throws RaiseError.TooLarge when it is longer than the event's limit, and
     *   queues it for no source.
     */
    fun raise(key: Key, bytes: ByteArray, seq: ULong, trace: TraceContext?): List<Waker> {
        admit(key, Kind.Event, bytes.size, RaiseError::TooLarge)
        val envelope = Envelope(now, seq)
        val wake = mutableListOf<Waker>()
        for (state in sources.values) {
            if (key in state.subscribed) {
                state.queue.addLast(QueuedEvent(key, bytes, envelope, trace))
                state.waiter?.let(wake::add)
                state.waiter = null
            }
        }
        return wake
    }

    /**
     * Stores a source's `Interest.Event` waker, or wakes it at once when an
     * occurrence is already in the source's queue or the source is closed.
     * The interface of the key is not kept: the handle holds one `Event`
     * waker, and any occurrence queued for it wakes that waker.
     */
    fun waitEvent(id: Int, waker: Waker, wake: MutableList<Waker>) {
        val state = sources[id] ?: return run { wake += waker }
        state.waiter = replace(state.waiter, waker, state.queue.isNotEmpty(), wake)
    }

    fun nextEvent(id: Int, out: ByteBuffer): RawOccurrence? {
        val state = sources[id] ?: return null
        val front = state.queue.peekFirst() ?: return null
        copyInto(front.bytes, out)
        state.queue.removeFirst()
        return RawOccurrence(front.key.iface, front.key.ord, front.envelope, front.trace, front.bytes.size)
    }

    // -- calls --------------------------------------------------------------

    /**
     * Takes a slot in the call table and queues the call for presentation, and
     * returns its correlation and the `Claim` waker of every handler that
     * serves the member, whatever interface that waker was registered under.
     *
     * @throws SendError.TooLarge when the arguments are longer than the call's
     *   limit, before a slot is taken, so nothing is sent and nothing is woken.
     * @throws SendError.Busy when every slot is taken.
     */
    fun send(caller: Int, kind: CallKind, key: Key, args: ByteArray, seq: ULong, trace: TraceContext?): Pair<Correlation, List<Waker>> {
        admit(key, kind.kind, args.size, SendError::TooLarge)
        // No budget, so the reservation is not read.
        val c = table.insert(0u) ?: throw SendError.Busy
        calls[Table.slot(c)] = CallEntry(c, caller, kind, key, args, Envelope(now, seq), trace, nextSent++)
        pending += c
        val wake = mutableListOf<Waker>()
        wakeHandlersServing(key, wake)
        return c to wake
    }

    /** The entry of a call the table holds. */
    private fun entry(c: Correlation): CallEntry = calls.getValue(Table.slot(c))

    /**
     * Stores a call's `Interest.Outcome` waker, or wakes it at once when the
     * outcome is already recorded or no call in flight has that correlation —
     * an unknown or forgotten one. The table decides which.
     */
    fun waitOutcome(c: Correlation, waker: Waker, wake: MutableList<Waker>) {
        if (forgottenPending(c)) {
            wake += waker
            return
        }
        table.wakeOn(c, waker)?.let(wake::add)
    }

    /** Whether [c] is a call whose `forget` waits for the provider: the table still holds it, but the caller released it. */
    private fun forgottenPending(c: Correlation): Boolean = calls[Table.slot(c)]?.let { it.correlation == c && it.pendingForget } ?: false

    fun ack(c: Correlation): Result<Unit>? {
        // The table answers `null` for a call in flight, forgotten, or not
        // held; a correlation it answers for has its entry here.
        val outcome = table.outcome(c) ?: return null
        if (forgottenPending(c)) return null
        // A query's correlation always answers `null` here: its outcome comes from `reply`.
        return outcome.takeIf { entry(c).kind == CallKind.Command }
    }

    fun reply(c: Correlation, out: ByteBuffer): Result<Int>? {
        if (forgottenPending(c)) return null
        val outcome = table.outcome(c) ?: return null
        return outcome.map {
            val bytes = entry(c).reply
            copyInto(bytes, out)
            bytes.size
        }
    }

    /**
     * Releases the caller's interest in a correlation, the one operation that
     * frees a slot. What it does depends on where the call is:
     *
     * - **Settled**: its slot is reclaimed now, unless the provider still has
     *   a stream parameter to pull to its end, as for a command acknowledged
     *   whose stream parameter is not drained. Then [deferForget] marks it
     *   forgotten and keeps its slot until the provider is done with every
     *   stream parameter or its handler is closed.
     * - **Claimed by a handler and not settled**: it is marked forgotten and
     *   keeps its slot. The provider still settles it, and the slot is
     *   reclaimed when that settlement lands. If the handler is closed first,
     *   [closeHandler] withdraws the call instead of returning it.
     * - **Waiting, claimed by no handler**: it is withdrawn. It leaves the
     *   waiting calls, so no handler is ever presented it, and its slot is
     *   reclaimed now. A call to a member no handler serves is never settled,
     *   so without this its slot would be lost for the life of the runtime.
     *   This is this runtime's behaviour, not a port contract: a transport
     *   that has already sent a request cannot recall it.
     * - **Waiting, offered to a handler through `ReadError.ShortClaim`**: it
     *   is a handler's claim although it is still waiting, so it is marked
     *   forgotten as a claimed call is, and it stays among the waiting calls,
     *   so the provider's retry with a larger buffer still presents it under
     *   the same id. Its settlement by the handler that holds it, or that
     *   handler's close, reclaims the slot (driftsys/ridl#569).
     *
     * Either way the correlation answers `null` from `ack` and `reply`
     * afterwards. A waiter on the outcome of a call in flight is woken, and a
     * withdrawal wakes every caller's `Slot` waiter, as any reclaim does, and
     * no handler's `Claim` waiter, because it adds no call to claim.
     */
    fun forget(c: Correlation, wake: MutableList<Waker>) {
        if (forgottenPending(c)) return
        interruptStreams(c, wake)
        if (!offered(c) && pending.remove(c)) return withdraw(c, wake)
        if (deferForget(c, wake)) return
        forgetInTable(c, wake)
    }

    /**
     * Defers the `forget` of a call that a handler holds or settled while the
     * provider has a stream parameter to pull to its end: the call is marked
     * forgotten here and not in the table, so its slot stays taken. Its
     * `Outcome` waker and its `Stream` wakers are woken now, as a forget wakes
     * them. Answers whether it deferred.
     */
    private fun deferForget(c: Correlation, wake: MutableList<Waker>): Boolean {
        val slot = Table.slot(c)
        val entry = calls[slot] ?: return false
        val held = entry.claim?.let { it in claims } == true || entry.settledBy != null
        if (entry.correlation != c || entry.forgotten || !held || !entry.undrained()) return false
        entry.forgotten = true
        entry.pendingForget = true
        // The table hands back the stored `Outcome` waker when another is
        // registered in its place; the no-op waker left behind wakes nothing.
        table.wakeOn(c, noopWaker())?.let(wake::add)
        wake += table.takeStreams(slot)
        return true
    }

    /** Completes a deferred `forget` once the provider has pulled every stream parameter to its end. */
    private fun releaseIfDrained(slot: Int, wake: MutableList<Waker>) {
        val entry = calls[slot] ?: return
        if (!entry.pendingForget || entry.undrained()) return
        entry.pendingForget = false
        forgetInTable(entry.correlation, wake)
    }

    /** The table's part of a `forget`: a settled call's slot is reclaimed, and a call in flight is marked, so that its settlement reclaims it. */
    private fun forgetInTable(c: Correlation, wake: MutableList<Waker>) {
        when (val forgotten = table.forget(c)) {
            Forgotten.Reclaimed -> reclaimed(c, wake)
            is Forgotten.Marked -> {
                forgotten.waker?.let(wake::add)
                wake += table.takeStreams(Table.slot(c))
                entry(c).forgotten = true
            }
            Forgotten.Unknown -> {}
        }
    }

    /**
     * Withdraws a call that no handler holds and that is not among the
     * waiting calls: the table settles it and, when the caller has not
     * forgotten it yet, forgets it, which reclaims its slot either way. Both
     * run under the one monitor, so no reader sees the settlement in between.
     * Two paths reach it: [forget] of a waiting call, just taken out of the
     * queue, and [closeHandler] for a claim whose call the caller forgot while
     * the closed handler held it.
     */
    private fun withdraw(c: Correlation, wake: MutableList<Waker>) {
        // The table discards this outcome with the slot, so no reader sees it.
        // `Undelivered` is what happened: no provider settled the call.
        when (val settled = table.settle(c, Result.failure(Transport.Undelivered))) {
            // A waiting call, which the caller forgets now.
            is Settled.Recorded -> {
                settled.waker?.let(wake::add)
                check(table.forget(c) == Forgotten.Reclaimed) { "the call was settled just above" }
                reclaimed(c, wake)
            }
            // A call the caller forgot while a handler held it, withdrawn at
            // that handler's close: the settlement reclaims the slot.
            Settled.Reclaimed -> reclaimed(c, wake)
            Settled.Unknown -> error("a withdrawn call is in flight")
        }
    }

    /**
     * Drops what the loopback kept for a reclaimed slot, and takes every
     * caller's `Slot` waker: the first to send again takes the slot, and the
     * others find the table full and register again (note F-5, no queue).
     * Every stream waker of the call is taken as well, so that no task waits
     * on a stream that is gone.
     */
    private fun reclaimed(c: Correlation, wake: MutableList<Waker>) {
        val slot = Table.slot(c)
        wake += table.takeStreams(slot)
        calls.remove(slot)?.streams?.values?.forEach { stream -> stream.claimWaker?.let(wake::add) }
        for (waiters in callers.values) waiters.take(Interest.Slot)?.let(wake::add)
    }

    fun openCaller(): Int = nextCallerId++.also { callers[it] = Waiters() }

    /**
     * Removes a closed caller, its `Slot` waker first, and forgets every call
     * it sent and did not forget, under the rule of [forget]: a settled one's
     * slot is reclaimed now, one no handler has claimed is withdrawn and its
     * slot reclaimed now, and one a handler has claimed is marked, so its
     * settlement reclaims the slot. No handle can read the outcome of a call
     * whose caller is gone, and a slot kept for it would be lost to every
     * other caller. One exception holds the slot longer: a call whose provider
     * still has a stream parameter to read keeps its slot until the provider is
     * done with it or its handler is closed ([deferForget]).
     */
    fun closeCaller(id: Int, wake: MutableList<Waker>) {
        callers.remove(id)
        for (c in calls.values.filter { it.caller == id }.map { it.correlation }) forget(c, wake)
    }

    /** Stores a caller's `Interest.Slot` waker, or wakes it at once when a slot is free or the caller is closed. */
    fun waitSlot(id: Int, waker: Waker, wake: MutableList<Waker>) {
        val waiters = callers[id] ?: return run { wake += waker }
        register(waiters, Interest.Slot, waker, calls.size < Loopback.SLOTS, wake)
    }

    fun openHandler(): Int = nextHandlerId++.also { handlers[it] = HandlerState() }

    /**
     * Removes a closed handler. Every claim it had taken and not settled
     * returns to the waiting calls, in its place by send order and under the
     * id it was first presented with, so another handler that serves the
     * member can take it, and every handler that serves the member has its
     * `Claim` waker returned, unless the handler already moved one of the
     * call's streams: such a call is interrupted instead ([interruptClaim]). A
     * claim it had only been offered, through
     * `ReadError.ShortClaim`, never left the waiting calls, so it is neither
     * re-inserted nor woken for; its id stays on the entry for the next
     * presentation (driftsys/ridl#569). A claim whose call the caller forgot
     * is withdrawn instead, taken or offered, as [forget] withdraws a waiting
     * call: its slot is reclaimed now, no handler is presented it again, and
     * no `Claim` waker is woken for it. The handler's own state goes first, so
     * the return never wakes its own waker. The loopback enforces no deadline
     * on the returned call.
     */
    fun closeHandler(id: Int, wake: MutableList<Waker>) {
        handlers.remove(id)
        val released = mutableListOf<Correlation>()
        // The closed handler's `ClaimStream` wakers, of the claims it holds and
        // of those it settled, are taken; a settled claim's streams are no
        // longer addressable.
        for ((slot, entry) in calls) {
            val holds = entry.claim?.let { claims[it] }?.handler == id
            if (!holds && entry.settledBy != id) continue
            if (entry.settledBy == id) {
                entry.settledBy = null
                // No provider is left to read the stream parameters of the
                // settled claim: they are released, as a query's settlement
                // releases them, and the caller's waiters on them are woken.
                // The reply stream of a settled claim already ended or stopped.
                entry.releaseParameters(wake)
                wake += table.takeStreams(slot)
                if (entry.pendingForget) {
                    entry.pendingForget = false
                    released += entry.correlation
                }
            }
            for (stream in entry.streams.values) stream.takeClaimWaker()?.let(wake::add)
        }
        for (c in released) forgetInTable(c, wake)
        val held = claims.filterValues { it.handler == id }.keys.toList()
        for (claim in held) {
            val owner = claims.remove(claim)!!
            val entry = entry(owner.call)
            if (entry.forgotten) {
                // A forgotten offered call is still among the waiting calls;
                // `withdraw` expects a call that is not.
                if (!owner.taken) pending.remove(owner.call)
                withdraw(owner.call, wake)
                continue
            }
            if (!owner.taken) continue
            if (entry.providerUsedStreams) {
                // This handler already moved the call's streams, so another
                // handler cannot take the call over without reading partial
                // streams.
                interruptClaim(owner.call, wake)
                continue
            }
            val at = pending.indexOfFirst { entry(it).sent > entry.sent }.let { if (it < 0) pending.size else it }
            pending.add(at, owner.call)
            wakeHandlersServing(entry.key, wake)
        }
    }

    /**
     * Ends a taken call whose handler was closed after its streams were used:
     * every unfinished stream stops with `Transport.Interrupted`, by the
     * provider's side, and the call settles with the same error, which wakes
     * its `Outcome` waker and its `Stream` wakers.
     */
    private fun interruptClaim(c: Correlation, wake: MutableList<Waker>) {
        val entry = entry(c)
        // As any settlement with an error does: every unfinished stream
        // parameter stops, and one first named afterwards starts stopped.
        entry.releaseParameters(wake)
        // It stops the reply stream too, whether or not anything named it yet.
        val reply = entry.stream(REPLY_STREAM)
        if (!reply.finished) reply.stopped = INTERRUPTED to Side.Provider
        reply.takeClaimWaker()?.let(wake::add)
        when (val settled = table.settle(c, Result.failure(INTERRUPTED))) {
            is Settled.Recorded -> {
                settled.waker?.let(wake::add)
                wake += table.takeStreams(Table.slot(c))
            }
            Settled.Reclaimed -> reclaimed(c, wake)
            Settled.Unknown -> error("a held claim names a call in flight")
        }
    }

    /** Takes the `Claim` waker of every handler that serves [key]. */
    private fun wakeHandlersServing(key: Key, wake: MutableList<Waker>) {
        for (state in handlers.values) {
            if (state.serves(key)) {
                state.waiter?.let(wake::add)
                state.waiter = null
            }
        }
    }

    /** The members a handler served, in the order served. */
    fun served(handler: Int): List<Key> = handlers[handler]?.served?.toList().orEmpty()

    /**
     * Adds members to a handler's served set. A call already waiting that the
     * handler now serves wakes its `Claim` waker; with none waiting, the waker
     * stays stored.
     */
    fun serve(handler: Int, iface: InterfaceNo, ords: List<Ordinal>, wake: MutableList<Waker>) {
        val state = handlers[handler] ?: return
        ords.mapTo(state.served) { Key(iface, it) }
        if (state.waiter != null && claimWaiting(state)) {
            wake += state.waiter!!
            state.waiter = null
        }
    }

    /**
     * Stores a handler's `Interest.Claim` waker, or wakes it at once when a
     * call it serves is already waiting or the handler is closed. The
     * interface of the key is not kept: the handle holds one `Claim` waker,
     * and any call it serves wakes that waker.
     */
    fun waitClaim(handler: Int, waker: Waker, wake: MutableList<Waker>) {
        val state = handlers[handler] ?: return run { wake += waker }
        state.waiter = replace(state.waiter, waker, claimWaiting(state), wake)
    }

    /** Whether a call is waiting that `nextClaim` would present to this handler: the same scan it makes. */
    private fun claimWaiting(state: HandlerState): Boolean = pending.any { state.serves(entry(it).key) }

    /**
     * Presents the next waiting call this handler serves: every waiting call
     * when it has served nothing.
     *
     * A call whose arguments do not fit [out] is offered rather than taken
     * (driftsys/ridl#569): the id is minted, the claim is recorded as this
     * handler's, the call stays among the waiting calls, and `nextClaim`
     * throws `ReadError.ShortClaim`. A later `nextClaim`, by this handler or
     * by another that serves the member, presents the same call under the
     * same id, because the id lives on the call's entry; a take by another
     * handler moves the claim to that handler. [settle] accepts the id
     * whether or not the call was read.
     */
    fun nextClaim(handler: Int, out: ByteBuffer): Claim? {
        val state = handlers[handler] ?: return null
        val c = pending.firstOrNull { state.serves(entry(it).key) } ?: return null
        val entry = entry(c)
        val claimId = entry.claim ?: nextClaimId++.also { entry.claim = it }
        if (out.remaining() < entry.args.size) {
            claims[claimId] = ClaimOwner(c, handler, taken = false)
            throw ReadError.ShortClaim(ClaimId(claimId), entry.args.size, entry.trace)
        }
        out.put(entry.args)
        pending.remove(c)
        claims[claimId] = ClaimOwner(c, handler, taken = true)
        // No response bound: a bound is a member's timing, and the loopback
        // has no member table to read one from.
        return Claim(ClaimId(claimId), entry.key.iface, entry.key.ord, entry.envelope, entry.trace, null, entry.args.size)
    }

    /**
     * Records a claim's outcome. The claim is looked up before an injected
     * failure is consumed, and an injected failure leaves the claim
     * settleable. A recorded outcome wakes the call's `Outcome` waker, ends the
     * reply stream, or stops it with the error, after the chunks already pushed,
     * and wakes every `Stream` waker of the call. The settlement of a call the
     * caller forgot records nothing and reclaims its slot.
     */
    fun settle(handler: Int, claim: ClaimId, outcome: Result<ByteArray>, wake: MutableList<Waker>) {
        val owner = claims[claim.value]
        // A claim another handler holds is unknown to this one.
        if (owner == null || owner.handler != handler) throw SettleError.UnknownClaim
        // Before the injected failure, which a size refusal does not spend.
        outcome.onSuccess { reply ->
            val call = entry(owner.call)
            admit(call.key, call.kind.kind, reply.size, SettleError::TooLarge)
        }
        if (failNextSettle) {
            failNextSettle = false
            throw SettleError.TooLarge(0)
        }
        claims.remove(claim.value)
        // An offered call is still among the waiting calls: its settlement
        // takes it out, so no handler is presented it afterwards.
        if (!owner.taken) pending.remove(owner.call)
        when (val settled = table.settle(owner.call, outcome.map { })) {
            is Settled.Recorded -> {
                val entry = entry(owner.call)
                outcome.onSuccess { entry.reply = it }
                entry.settledBy = handler
                // A query's settlement is its end, and a settlement with an
                // error refuses the whole call: either way the provider is
                // done with the stream parameters. A command's settlement
                // with no error is its acknowledgment, and its stream
                // parameters go on after it.
                if (entry.kind == CallKind.Query || outcome.isFailure) entry.releaseParameters(wake)
                // The settlement ends the reply stream, or stops it with the
                // error, after the chunks already pushed.
                val reply = entry.stream(REPLY_STREAM)
                if (!reply.finished) {
                    outcome.fold(
                        onSuccess = { reply.ended = true },
                        onFailure = { reply.stopped = (it as CallError) to Side.Provider },
                    )
                }
                settled.waker?.let(wake::add)
                wake += table.takeStreams(Table.slot(owner.call))
                releaseIfDrained(Table.slot(owner.call), wake)
            }
            // The caller released it while it was in flight: it was still
            // presented and is still settled, and the slot goes with it.
            Settled.Reclaimed -> reclaimed(owner.call, wake)
            Settled.Unknown -> error("a claim names a call in flight")
        }
    }

    fun failNextSettle() {
        failNextSettle = true
    }

    // -- streams ------------------------------------------------------------

    /**
     * The slot of the call the caller holds under [c]: `null` for a correlation
     * the table does not hold, of a generation the slot no longer has, or that
     * the caller forgot.
     */
    fun callerSlot(c: Correlation): Int? {
        val slot = Table.slot(c)
        return slot.takeIf { calls[it]?.let { entry -> entry.correlation == c && !entry.forgotten } == true }
    }

    /**
     * The slot of the call [handler] holds under [claim]: a claim it took and
     * has not settled, or one it settled whose call the caller has not forgotten
     * yet. A claim only offered through `ReadError.ShortClaim` has no streams
     * yet: the handler takes it first, so that a call whose offer moves to
     * another handler, or returns to the waiting calls, has no stream that one
     * holder has partly read.
     */
    fun claimSlot(handler: Int, claim: ClaimId): Int? {
        val (slot, entry) = calls.entries.firstOrNull { it.value.claim == claim.value } ?: return null
        val owner = claims[claim.value]
        val holder = if (owner != null) owner.handler.takeIf { owner.taken } else entry.settledBy
        return slot.takeIf { holder == handler }
    }

    /** Takes the waker of [side] on stream [s] of the call in [slot]: the caller's from the call table, the provider's from the stream. */
    private fun takeStreamWaker(slot: Int, s: StreamNo, side: Side): Waker? {
        val entry = calls[slot] ?: return null
        return when (side) {
            Side.Caller -> table.takeStream(entry.correlation, s)
            Side.Provider -> entry.streams[s]?.takeClaimWaker()
        }
    }

    /**
     * Copies the next chunk of stream [s] of the call in [slot] into [out], for
     * [side], which must be the stream's receiver. A chunk taken wakes the
     * producer, whose window it frees.
     */
    fun pull(slot: Int, side: Side, s: StreamNo, out: ByteBuffer, wake: MutableList<Waker>): Pull {
        if (Side.producer(s) == side) throw ReadError.Contract(Contract.UnknownInteraction)
        val entry = calls.getValue(slot)
        val stream = entry.stream(s)
        val front = stream.chunks.peekFirst()
        if (front == null) {
            val stopped = stream.stopped
            val pull = when {
                stream.ended -> Pull.End
                stopped != null -> Pull.Failed(stopped.first)
                else -> return Pull.Pending
            }
            if (side == Side.Provider) {
                stream.drained = true
                entry.providerUsedStreams = true
                releaseIfDrained(slot, wake)
            }
            return pull
        }
        copyInto(front, out)
        stream.chunks.removeFirst()
        if (side == Side.Provider) entry.providerUsedStreams = true
        takeStreamWaker(slot, s, side.other)?.let(wake::add)
        return Pull.Chunk(front.size)
    }

    /**
     * Offers one chunk to stream [s] of the call in [slot], for [side], which
     * must be the stream's producer. A chunk taken wakes the receiver.
     */
    fun push(slot: Int, side: Side, s: StreamNo, chunk: ByteArray, wake: MutableList<Waker>): Int {
        if (Side.producer(s) != side) throw PushError.Contract(Contract.UnknownInteraction)
        val entry = calls.getValue(slot)
        if (side == Side.Provider && entry.forgotten) throw PushError.Failed(INTERRUPTED)
        val stream = entry.stream(s)
        stream.closed(side)?.let { throw it }
        admit(entry.key, entry.kind.kind, chunk.size, PushError::TooLarge)
        // An empty chunk is always taken: the window bounds the non-empty chunks only.
        if (chunk.isNotEmpty() && stream.full) return 0
        stream.chunks.addLast(chunk)
        if (side == Side.Provider) entry.providerUsedStreams = true
        takeStreamWaker(slot, s, side.other)?.let(wake::add)
        return chunk.size
    }

    /** Ends stream [s] of the call in [slot], for [side], which must be the stream's producer. The end wakes the receiver. */
    fun end(slot: Int, side: Side, s: StreamNo, wake: MutableList<Waker>) {
        if (Side.producer(s) != side) throw PushError.Contract(Contract.UnknownInteraction)
        val entry = calls.getValue(slot)
        if (side == Side.Provider && entry.forgotten) throw PushError.Failed(INTERRUPTED)
        val stream = entry.stream(s)
        stream.closed(side)?.let { throw it }
        // The provider produces only the reply stream, and reaches this end
        // only for a claim already settled, whose reply stream the settlement
        // finished: so the provider never gets past `closed`.
        stream.ended = true
        takeStreamWaker(slot, s, side.other)?.let(wake::add)
    }

    /** Stops stream [s] of the call in [slot] with [error], from either side. The stop wakes the other side. */
    fun abort(slot: Int, side: Side, s: StreamNo, error: CallError, wake: MutableList<Waker>) {
        val entry = calls.getValue(slot)
        if (side == Side.Provider) entry.providerUsedStreams = true
        val stream = entry.stream(s)
        val receiver = Side.producer(s) != side
        if (stream.finished) {
            // A receiver's abort of a stream that already ended or stopped
            // changes nothing for the producer; the receiver is done with the
            // stream all the same, so its unread chunks are dropped, and a
            // provider that refuses a stream parameter this way does not hold
            // the call's slot.
            if (receiver) {
                stream.chunks.clear()
                if (side == Side.Provider) {
                    stream.drained = true
                    releaseIfDrained(slot, wake)
                }
            }
            throw PushError.Ended
        }
        stream.stopped = error to side
        // A receiver that refuses the stream reads none of what it refused:
        // its later pulls answer `Failed` with its own outcome.
        if (receiver) stream.chunks.clear()
        // A stream the provider stopped itself needs no more reading by it.
        if (side == Side.Provider) stream.drained = true
        takeStreamWaker(slot, s, side.other)?.let(wake::add)
        if (side == Side.Provider) releaseIfDrained(slot, wake)
    }

    /**
     * `endClaim`: on the reply stream of a claim still held, the claim's
     * settlement with no reply bytes, which ends the stream; on any other
     * stream, or a claim already settled, an end.
     */
    fun endClaim(handler: Int, claim: ClaimId, s: StreamNo, wake: MutableList<Waker>) {
        val slot = claimSlot(handler, claim) ?: throw PushError.Contract(Contract.UnknownInteraction)
        if (s != REPLY_STREAM || claim.value !in claims) return end(slot, Side.Provider, s, wake)
        // The claim is settled whatever the stream's verdict, so that a
        // provider whose reply stream was stopped does not hold the call's
        // slot; the verdict is the answer.
        val entry = calls.getValue(slot)
        val verdict = if (entry.forgotten) PushError.Failed(INTERRUPTED) else entry.stream(s).closed(Side.Provider)
        // After the caller refused the reply stream, the claim settles with
        // that refusal as its outcome.
        val outcome = if (verdict is PushError.Failed) Result.failure(verdict.error) else Result.success(ByteArray(0))
        settleForStream(handler, claim, outcome, wake)
        verdict?.let { throw it }
    }

    /**
     * `abortClaim`: on the reply stream of a claim still held, the claim's
     * settlement with [error], which stops the stream; on any other stream, or
     * a claim already settled, an abort.
     */
    fun abortClaim(handler: Int, claim: ClaimId, s: StreamNo, error: CallError, wake: MutableList<Waker>) {
        val slot = claimSlot(handler, claim) ?: throw PushError.Contract(Contract.UnknownInteraction)
        if (s != REPLY_STREAM || claim.value !in claims) return abort(slot, Side.Provider, s, error, wake)
        // The claim is settled whatever the stream's state, as in `endClaim`.
        val finished = calls.getValue(slot).stream(s).finished
        settleForStream(handler, claim, Result.failure(error), wake)
        if (finished) throw PushError.Ended
    }

    /** [settle], with the push error a settlement through `endClaim` or `abortClaim` throws when it is refused. */
    private fun settleForStream(handler: Int, claim: ClaimId, outcome: Result<ByteArray>, wake: MutableList<Waker>) {
        try {
            settle(handler, claim, outcome, wake)
        } catch (e: SettleError) {
            throw when (e) {
                SettleError.UnknownClaim -> PushError.Contract(Contract.UnknownInteraction)
                is SettleError.TooLarge -> PushError.TooLarge(e.cap)
                SettleError.Detached -> PushError.Detached
            }
        }
    }

    /** Stores the caller's `Interest.Stream(c, s)` waker with the call, in the table, which wakes it at once for a correlation it does not hold or that the caller forgot. */
    fun waitStream(c: Correlation, s: StreamNo, waker: Waker, wake: MutableList<Waker>) {
        if (forgottenPending(c)) {
            wake += waker
            return
        }
        table.wakeOnStream(c, s, waker)?.let(wake::add)
    }

    /**
     * Stores the provider's `Interest.ClaimStream(claim, s)` waker with the
     * stream, or wakes it at once when [handler] holds no such claim or the
     * caller forgot the call, or the stream ended, stopped or was released, since
     * it will not change again.
     */
    fun waitClaimStream(handler: Int, claim: ClaimId, s: StreamNo, waker: Waker, wake: MutableList<Waker>) {
        val entry = claimSlot(handler, claim)?.let(calls::getValue)
        if (entry == null || entry.forgotten) {
            wake += waker
            return
        }
        val stream = entry.stream(s)
        stream.claimWaker = replace(stream.claimWaker, waker, stream.finished, wake)
    }

    /**
     * Stops every stream of the call the caller holds under [c] with
     * `Transport.Interrupted`, except one already ended or stopped, and takes
     * the provider's waker of each stream. A correlation the caller does not
     * hold changes nothing, so a stale one does not touch the call now in its
     * slot.
     */
    private fun interruptStreams(c: Correlation, wake: MutableList<Waker>) {
        val entry = calls[callerSlot(c) ?: return] ?: return
        for (stream in entry.streams.values) {
            if (!stream.finished) stream.stopped = INTERRUPTED to Side.Caller
            stream.takeClaimWaker()?.let(wake::add)
        }
    }

    /**
     * Whether a handler holds a claim on [c] that it has not taken: the call
     * was offered through `ReadError.ShortClaim`.
     */
    private fun offered(c: Correlation): Boolean =
        calls[Table.slot(c)]?.claim?.let { claims[it] }?.let { !it.taken } ?: false

    private companion object {
        /**
         * The sample a channel with no publication answers with: the
         * consumer's binding supplies the init value, so the port reports
         * `Init` and copies nothing (ridl §4.4).
         */
        val UNPUBLISHED = RawSample(Provenance.Init, Freshness.Unbounded, Envelope(Timestamp(0), 0u), 0)

        // Every value is `Unbounded`: freshness is measured against a
        // staleness bound, and the loopback has no member table to read one from.
        fun sampleOf(entry: SignalEntry) = RawSample(
            provenance = entry.provenance,
            freshness = Freshness.Unbounded,
            envelope = entry.envelope,
            len = entry.bytes.size,
        )

        /**
         * Stores [waker] as the one waker a handle holds for one kind of key,
         * or, when [ready], adds it to [wake] at once and stores nothing; the
         * return is what the slot holds next. A stored waker of another task
         * is displaced onto [wake], so that task does not wait on a
         * registration that can no longer fire. The same waker is a refresh:
         * it is kept or replaced without being woken, because a task
         * registers on every poll (ADR-0021 decision 13).
         */
        /**
         * Stores [waker] as a handle's one waker of [what]'s kind in
         * [waiters], or, when [ready], adds it to [wake] at once and leaves
         * that kind empty, with the refresh and displacement rules of
         * `Waiters.register`.
         */
        fun register(waiters: Waiters, what: Interest, waker: Waker, ready: Boolean, wake: MutableList<Waker>) {
            waiters.register(what, waker)?.let(wake::add)
            if (ready) waiters.take(what)?.let(wake::add)
        }

        fun replace(stored: Waker?, waker: Waker, ready: Boolean, wake: MutableList<Waker>): Waker? {
            if (stored != null && stored !== waker) wake += stored
            if (!ready) return waker
            wake += waker
            return null
        }

        /** Copies [bytes] into [out] from its position, or throws `Short` and writes nothing. */
        fun copyInto(bytes: ByteArray, out: ByteBuffer) {
            if (out.remaining() < bytes.size) throw ReadError.Short(bytes.size)
            out.put(bytes)
        }
    }
}

/** The bytes from [ByteBuffer.position] to its limit, without moving the caller's position. */
internal fun ByteBuffer.remainingBytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

/** A settled outcome as the store keeps it: the reply bytes, or the [CallError]. */
internal fun Result<ByteBuffer>.toStored(): Result<ByteArray> = fold(
    onSuccess = { Result.success(it.remainingBytes()) },
    onFailure = { error ->
        require(error is CallError) { "a claim is settled with a CallError, not ${error::class.qualifiedName}" }
        Result.failure(error)
    },
)
