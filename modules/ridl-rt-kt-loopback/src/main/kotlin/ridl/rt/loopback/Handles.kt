// The six role handles and their port implementations: the spelling of
// `crates/ridl-loopback/src/handle.rs`.
//
// A runtime presents one handle type per port role (ADR-0021 decision 12).
// The six here group the eleven roles as that decision derives the threading
// split: the five roles with a mutating method take a handle each, and the six
// whose methods only read share one.
//
// | Handle          | Port roles beside `Attached` and `Wakeable`                                    | Kinds of key it stores         | Threading            |
// | --------------- | ------------------------------------------------------------------------------ | ------------------------------ | -------------------- |
// | `ReaderHandle`  | `Clock`, `SignalReader`, `FixedReader`, `ScannableSignals`, `CoherentSignals` | none                           | any threads at once  |
// | `WriterHandle`  | `SignalWriter`                                                                 | none                           | one thread at a time |
// | `SourceHandle`  | `EventSource`                                                                  | `Event`, one waker             | one thread at a time |
// | `SinkHandle`    | `EventSink`                                                                    | none                           | one thread at a time |
// | `CallerHandle`  | `Clock`, `Caller`                                                              | `Outcome` with each call, `Slot` | one thread at a time |
// | `HandlerHandle` | `Handler`                                                                      | `Claim`, one waker             | one thread at a time |
//
// `Wakeable` is on every handle, because each handle wakes its own waiters. A
// handle stores a waker only under a kind of key one of its roles observes:
// one waker per kind for `Event` and `Claim`, which a change to any key of
// that kind wakes (ADR-0021 decision 13), and an `Outcome` waker with its
// call, and the caller one `Slot` waker, which a reclaimed slot of the call
// table wakes and a free slot wakes at once. A registration under any other
// kind is woken at once, because nothing that handle could read changes under
// it.
// `CallerHandle` carries `Clock`, because a client that waits for an outcome
// within a bound reads the clock of the port it calls (ADR-0023 decision 6).
//
// Every store access is under the store's monitor, so the reader handle holds
// no state of its own and is safe from any thread; the other five hold state
// of their own — staged changes, sequence counters, a served set — that is not
// synchronized, and each is driven by one thread at a time, as the Rust
// handles are `Send` and not `Sync`.
//
// Input buffers are read from their position to their limit and left as they
// were, as a Rust slice is borrowed; output buffers are written from their
// position, which is advanced past the bytes written.
package ridl.rt.loopback

import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.port.Attached
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
import ridl.rt.port.SettleError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.Wakeable
import ridl.rt.port.Watermark
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.TreeMap

/** Wakes what a store operation returned, after the monitor was left. */
private fun wake(wakers: Iterable<Waker>) {
    for (waker in wakers) waker.wake()
}

/**
 * The read-only port roles: `Attached`, `Clock`, `SignalReader`,
 * `FixedReader`, and the two signal extensions. Safe to share between
 * threads, so a face that needs only `SignalReader` can be built over it.
 */
public class ReaderHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : Clock, SignalReader, FixedReader, ScannableSignals, CoherentSignals, Wakeable {
    override fun now(): Timestamp = store.locked { now() }

    /** Wakes every key at once: no role of this handle observes one. */
    override fun wakeOn(what: Interest, waker: Waker): Unit = waker.wake()

    override fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample =
        store.locked { read(Key(iface, ord), out) }

    override fun readFixed(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): Int =
        store.locked { readFixed(Key(iface, ord), out) }

    override fun generation(iface: InterfaceNo): ULong = store.locked { generation(iface) }

    override fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int = store.locked { scan(marks, out) }

    override fun readCoherent(
        iface: InterfaceNo,
        ords: List<Ordinal>,
        out: ByteBuffer,
        samples: Array<RawSample?>,
    ): Int = store.locked { readCoherent(iface, ords, out, samples) }
}

/**
 * The `SignalWriter` port role. The staged changes and the per channel
 * sequence counters live on the handle: staging is one provider's private
 * state until its `commit`, and a sequence number is the sender's (ridl §3.1).
 */
public class WriterHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : SignalWriter, Wakeable {
    private val staged = TreeMap<Key, Staged>()
    private val seqs = TreeMap<Key, ULong>()

    override fun set(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        staged[Key(iface, ord)] = Staged.Set(bytes.remainingBytes())
    }

    override fun invalidate(iface: InterfaceNo, ord: Ordinal) {
        staged[Key(iface, ord)] = Staged.Invalidate
    }

    /**
     * Stages a re-affirmation only when nothing else is staged for the
     * channel: a staged `set` or `invalidate` is itself a publication, and
     * replacing it would discard what this writer staged. A later `set` or
     * `invalidate` does replace a staged touch.
     */
    override fun touch(iface: InterfaceNo, ord: Ordinal) {
        staged.putIfAbsent(Key(iface, ord), Staged.Touch)
    }

    override fun commit() {
        store.locked { commit(staged, seqs) }
    }

    /** Wakes every key at once: no role of this handle observes one. */
    override fun wakeOn(what: Interest, waker: Waker): Unit = waker.wake()
}

/**
 * The `EventSource` port role: one subscription set and one queue in the
 * store under this handle's identity, so each source receives its own copy of
 * every occurrence raised while it is subscribed.
 *
 * [close] removes the queue and the waiter from the store, which the Rust
 * handle does on drop; a source that is never closed keeps receiving copies
 * until the runtime is collected.
 */
public class SourceHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : EventSource, Wakeable, AutoCloseable {
    private val id: Int = store.locked { openSource() }

    override fun subscribe(iface: InterfaceNo, ords: List<Ordinal>) {
        store.locked { subscribe(id, iface, ords) }
    }

    override fun unsubscribe(iface: InterfaceNo, ords: List<Ordinal>) {
        store.locked { unsubscribe(id, iface, ords) }
    }

    override fun next(out: ByteBuffer): RawOccurrence? = store.locked { nextEvent(id, out) }

    /**
     * Stores the one `Event` waker, whatever the interface: a `raise` that
     * queues an occurrence for this source wakes it, and an occurrence already
     * queued wakes it at once. Every other kind is woken at once.
     */
    override fun wakeOn(what: Interest, waker: Waker) {
        if (what !is Interest.Event) return waker.wake()
        wake(store.wakeList { waitEvent(id, waker, it) })
    }

    override fun close() {
        store.locked { closeSource(id) }
    }
}

/**
 * The `EventSink` port role. One sequence counter per event channel, as ridl
 * §3.1 scopes the number: a counter per handle would show a consumer of some
 * of this sink's events a gap where nothing was lost.
 */
public class SinkHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : EventSink, Wakeable {
    private val seqs = TreeMap<Key, ULong>()

    override fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer) {
        val key = Key(iface, ord)
        val seq = (seqs[key] ?: 0u) + 1u
        seqs[key] = seq
        wake(store.locked { raise(key, bytes.remainingBytes(), seq) })
    }

    /** Wakes every key at once: no role of this handle observes one. */
    override fun wakeOn(what: Interest, waker: Waker): Unit = waker.wake()
}

/**
 * The `Caller` port role. One counter for the whole handle: on a call the
 * sequence is the caller's (ADR-0021 decision 5), which is what keeps two
 * callers on one provider from colliding. A send the full call table refuses
 * with `SendError.Busy` draws no number.
 *
 * [close] does what the Rust handle's drop does: it removes the caller's
 * `Slot` waker, then forgets every call it sent and did not forget, so their
 * slots come back to the other callers.
 */
public class CallerHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : Caller, Clock, Wakeable, AutoCloseable {
    private val id: Int = store.locked { openCaller() }
    private var seq: ULong = 0u

    override fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation =
        send(CallKind.Command, iface, ord, args)

    override fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation =
        send(CallKind.Query, iface, ord, args)

    private fun send(kind: CallKind, iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation {
        val next = seq + 1u
        val (correlation, wakers) = store.locked { send(id, kind, Key(iface, ord), args.remainingBytes(), next) }
        seq = next
        wake(wakers)
        return correlation
    }

    override fun ack(c: Correlation): Result<Unit>? = store.locked { ack(c) }

    override fun reply(c: Correlation, out: ByteBuffer): Result<Int>? = store.locked { reply(c, out) }

    override fun forget(c: Correlation) {
        wake(store.wakeList { forget(c, it) })
    }

    override fun close() {
        wake(store.wakeList { closeCaller(id, it) })
    }

    override fun now(): Timestamp = store.locked { now() }

    /**
     * Stores an `Outcome` waker with its call: the settlement or the `forget`
     * of the call, or the close of this caller, wakes it, and an outcome
     * already known, or a correlation no call in flight has, wakes it at once.
     * Stores the one `Slot` waker while every slot of the call table is
     * taken, which a reclaimed slot wakes, and wakes it at once while a slot
     * is free. Every other kind is woken at once.
     */
    override fun wakeOn(what: Interest, waker: Waker) {
        when (what) {
            is Interest.Outcome -> wake(store.wakeList { waitOutcome(what.correlation, waker, it) })
            Interest.Slot -> wake(store.wakeList { waitSlot(id, waker, it) })
            is Interest.Event, is Interest.Claim -> waker.wake()
        }
    }
}

/**
 * The `Handler` port role.
 *
 * A handler that has served nothing is presented every waiting call; once it
 * has served anything, only the members it served. The empty set meaning no
 * filter is the Rust loopback's deliberate deviation from `Handler.serve`,
 * kept because the generated `dispatch` never calls `serve`. A claim belongs
 * to the handler it was presented to, so another handler's `settle` of it
 * throws [SettleError.UnknownClaim].
 *
 * [close] does what the Rust handle's drop does: it removes the handler, its
 * served set and its waker from the store, and returns every claim it held
 * and had not settled to the waiting calls, where another handler that serves
 * the member takes it (ADR-0021 decision 5).
 */
public class HandlerHandle internal constructor(
    private val store: Store,
    override val catalog: CatalogRef,
) : Handler, Wakeable, AutoCloseable {
    private val id: Int = store.locked { openHandler() }

    /** The members `serve` was called with, in the order served, with no duplicate. */
    public val served: List<Pair<InterfaceNo, Ordinal>>
        get() = store.locked { served(id) }.map { it.iface to it.ord }

    override fun serve(iface: InterfaceNo, ords: List<Ordinal>) {
        wake(store.wakeList { serve(id, iface, ords, it) })
    }

    override fun nextClaim(out: ByteBuffer): Claim? = store.locked { nextClaim(id, out) }

    override fun settle(claim: ClaimId, outcome: Result<ByteBuffer>) {
        val stored = outcome.toStored()
        wake(store.wakeList { settle(id, claim, stored, it) })
    }

    /**
     * Stores the one `Claim` waker, whatever the interface: a send of a call
     * this handler serves wakes it, and such a call already waiting wakes it
     * at once. Every other kind is woken at once.
     */
    override fun wakeOn(what: Interest, waker: Waker) {
        if (what !is Interest.Claim) return waker.wake()
        wake(store.wakeList { waitClaim(id, waker, it) })
    }

    override fun close() {
        wake(store.wakeList { closeHandler(id, it) })
    }
}

