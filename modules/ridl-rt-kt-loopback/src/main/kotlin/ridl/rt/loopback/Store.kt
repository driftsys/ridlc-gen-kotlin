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
import ridl.rt.contract.Ordinal
import ridl.rt.correlate.Forgotten
import ridl.rt.correlate.Settled
import ridl.rt.correlate.Table
import ridl.rt.correlate.Waiters
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.port.Changed
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.port.Watermark
import ridl.rt.sample.Cause
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
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

internal enum class CallKind { Command, Query }

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
        /** `true` after `invalidate`, cleared by the next `set`. */
        val invalid: Boolean,
        /** The interface generation this entry last changed at, which [scan] selects on. */
        val changedAt: ULong,
    )

    private class QueuedEvent(val key: Key, val bytes: ByteArray, val envelope: Envelope)

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

    /** A presented claim: the call it presented, and the handler holding it. */
    private class ClaimOwner(val call: Correlation, val handler: Int)

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
     * never one already settled, and never one another handler holds.
     */
    private val claims = TreeMap<Long, ClaimOwner>()
    private var nextClaimId = 0L
    private var nextHandlerId = 0
    private val handlers = TreeMap<Int, HandlerState>()
    private var failNextSettle = false

    /** Runs [block] under the store's monitor. */
    inline fun <T> locked(block: Store.() -> T): T = synchronized(this) { block() }

    /**
     * Runs [block] under the store's monitor with a list for the wakers it
     * must wake, and returns the list, for the handle to wake once the monitor
     * is left.
     */
    inline fun wakeList(block: Store.(MutableList<Waker>) -> Unit): List<Waker> =
        mutableListOf<Waker>().also { locked { block(it) } }

    // -- the clock ----------------------------------------------------------

    fun now(): Timestamp = now

    fun advance(by: Duration) {
        require(by.micros >= 0) { "the clock advances forward: `by` is ${by.micros}" }
        val sum = now.micros + by.micros
        now = Timestamp(if (sum < now.micros) Long.MAX_VALUE else sum)
    }

    // -- signals ------------------------------------------------------------

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
            val (bytes, invalid) = when (op) {
                is Staged.Set -> op.bytes to false
                Staged.Invalidate -> (previous?.bytes ?: ByteArray(0)) to true
                Staged.Touch -> previous!!.bytes to previous.invalid
            }
            signals[key] = SignalEntry(bytes, Envelope(stamp, seq), invalid, generations.getValue(key.iface))
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
            marks[index] = mark.copy(
                generation = current,
                seq = changes.maxOfOrNull { it.seq } ?: mark.seq,
            )
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

    fun provisionFixed(key: Key, bytes: ByteArray) {
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
     * registered under.
     */
    fun raise(key: Key, bytes: ByteArray, seq: ULong): List<Waker> {
        val envelope = Envelope(now, seq)
        val wake = mutableListOf<Waker>()
        for (state in sources.values) {
            if (key in state.subscribed) {
                state.queue.addLast(QueuedEvent(key, bytes, envelope))
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
        return RawOccurrence(front.key.iface, front.key.ord, front.envelope, front.bytes.size)
    }

    // -- calls --------------------------------------------------------------

    /**
     * Takes a slot in the call table and queues the call for presentation, and
     * returns its correlation and the `Claim` waker of every handler that
     * serves the member, whatever interface that waker was registered under.
     *
     * @throws SendError.Busy when every slot is taken.
     */
    fun send(caller: Int, kind: CallKind, key: Key, args: ByteArray, seq: ULong): Pair<Correlation, List<Waker>> {
        // No budget, so the reservation is not read.
        val c = table.insert(0u) ?: throw SendError.Busy
        calls[Table.slot(c)] = CallEntry(c, caller, kind, key, args, Envelope(now, seq), nextSent++)
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
        table.wakeOn(c, waker)?.let(wake::add)
    }

    fun ack(c: Correlation): Result<Unit>? {
        // The table answers `null` for a call in flight, forgotten, or not
        // held; a correlation it answers for has its entry here.
        val outcome = table.outcome(c) ?: return null
        // A query's correlation always answers `null` here: its outcome comes from `reply`.
        return outcome.takeIf { entry(c).kind == CallKind.Command }
    }

    fun reply(c: Correlation, out: ByteBuffer): Result<Int>? {
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
     * - **Settled**: nothing is left to happen to it, so its slot is
     *   reclaimed now.
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
     *
     * Either way the correlation answers `null` from `ack` and `reply`
     * afterwards. A waiter on the outcome of a call in flight is woken, and a
     * withdrawal wakes every caller's `Slot` waiter, as any reclaim does, and
     * no handler's `Claim` waiter, because it adds no call to claim.
     */
    fun forget(c: Correlation, wake: MutableList<Waker>) {
        if (pending.remove(c)) return withdraw(c, wake)
        when (val forgotten = table.forget(c)) {
            Forgotten.Reclaimed -> reclaimed(c, wake)
            is Forgotten.Marked -> {
                forgotten.waker?.let(wake::add)
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
     */
    private fun reclaimed(c: Correlation, wake: MutableList<Waker>) {
        calls.remove(Table.slot(c))
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
     * other caller.
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
     * Removes a closed handler. Every claim it held and had not settled
     * returns to the waiting calls, in its place by send order, so another
     * handler that serves the member can take it, and every handler that
     * serves the member has its `Claim` waker returned. A claim whose call
     * the caller forgot is withdrawn instead, as [forget] withdraws a waiting
     * call: its slot is reclaimed now, no handler is presented it again, and
     * no `Claim` waker is woken for it. The handler's own state goes first, so
     * the return never wakes its own waker. The loopback enforces no deadline
     * on the returned call.
     */
    fun closeHandler(id: Int, wake: MutableList<Waker>) {
        handlers.remove(id)
        val held = claims.filterValues { it.handler == id }.keys.toList()
        for (claim in held) {
            val owner = claims.remove(claim)!!
            val entry = entry(owner.call)
            if (entry.forgotten) {
                withdraw(owner.call, wake)
                continue
            }
            val at = pending.indexOfFirst { entry(it).sent > entry.sent }.let { if (it < 0) pending.size else it }
            pending.add(at, owner.call)
            wakeHandlersServing(entry.key, wake)
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
     */
    fun nextClaim(handler: Int, out: ByteBuffer): Claim? {
        val state = handlers[handler] ?: return null
        val c = pending.firstOrNull { state.serves(entry(it).key) } ?: return null
        val entry = entry(c)
        copyInto(entry.args, out)
        val claimId = nextClaimId++
        pending.remove(c)
        claims[claimId] = ClaimOwner(c, handler)
        // No response bound: a bound is a member's timing, and the loopback
        // has no member table to read one from.
        return Claim(ClaimId(claimId), entry.key.iface, entry.key.ord, entry.envelope, null, entry.args.size)
    }

    /**
     * Records a claim's outcome. The claim is looked up before an injected
     * failure is consumed, and an injected failure leaves the claim
     * settleable. A recorded outcome wakes the call's `Outcome` waker; the
     * settlement of a call the caller forgot records nothing and reclaims its
     * slot.
     */
    fun settle(handler: Int, claim: ClaimId, outcome: Result<ByteArray>, wake: MutableList<Waker>) {
        val owner = claims[claim.value]
        // A claim another handler holds is unknown to this one.
        if (owner == null || owner.handler != handler) throw SettleError.UnknownClaim
        if (failNextSettle) {
            failNextSettle = false
            throw SettleError.TooLarge(0)
        }
        claims.remove(claim.value)
        when (val settled = table.settle(owner.call, outcome.map { })) {
            is Settled.Recorded -> {
                outcome.onSuccess { entry(owner.call).reply = it }
                settled.waker?.let(wake::add)
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
            provenance = if (entry.invalid) Provenance.Invalid(Cause.Declared) else Provenance.Live,
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
