// The caller-side call table and the waiter registry a runtime keeps behind
// `Wakeable` (ADR-0021 decision 15): the spelling of `ridl_rt::correlate`
// (story E11.18).
//
// Every runtime with asynchronous replies keeps a table of the calls it has
// sent and not yet released, and every runtime that implements `Wakeable`
// keeps the wakers its handles registered. These two types are that storage,
// written once so that each runtime does not write it alone. Both are plain
// data structures: they hold no lock and wake nothing. A runtime puts them
// behind its own lock, and every operation that would wake a task returns the
// waker instead, so the runtime wakes it after releasing that lock.
package ridl.rt.correlate

import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.task.Waker

/** The bits of a correlation that hold the slot index. */
private const val SLOT_BITS = 16
private const val SLOT_MASK = (1L shl SLOT_BITS) - 1

/** The 48 bits a generation keeps: it wraps to 0 after `2^48 - 1` reclaims of one slot. */
private const val GENERATION_MASK = (1L shl (64 - SLOT_BITS)) - 1

/**
 * What [Table.settle] did, and the waker it hands back to be woken.
 * `ridl_rt::correlate::Settled`. The runtime wakes the waker after releasing
 * its lock. On [Reclaimed] it also wakes every `Interest.Slot` waiter it holds
 * and frees whatever it stored for the slot.
 */
public sealed interface Settled {
    /**
     * The outcome is recorded and [Table.outcome] reads it from now on. The
     * `Interest.Outcome` waker stored for the call, if any, is handed back and
     * is no longer stored.
     */
    public data class Recorded(public val waker: Waker?) : Settled

    /**
     * The call had been forgotten while in flight: the outcome is not
     * recorded, the slot is free, and its reservation is credited to the budget.
     */
    public data object Reclaimed : Settled

    /**
     * No call in flight has this correlation — never issued, already settled,
     * or of a generation the slot no longer has. Nothing changed.
     */
    public data object Unknown : Settled
}

/**
 * What [Table.forget] did, and the waker it hands back to be woken.
 * `ridl_rt::correlate::Forgotten`. On [Reclaimed] the runtime wakes every
 * `Interest.Slot` waiter it holds and frees whatever it stored for the slot.
 */
public sealed interface Forgotten {
    /** The call was settled: the slot is free now, and its reservation is credited to the budget. */
    public data object Reclaimed : Forgotten

    /**
     * The call is in flight: it is marked, its outcome will not be recorded,
     * and the slot is reclaimed at its settlement, which [Table.settle] reports
     * as [Settled.Reclaimed]. The call's `Interest.Outcome` waker, if any, is
     * handed back, because no outcome will ever be readable for it.
     */
    public data class Marked(public val waker: Waker?) : Forgotten

    /** No call this table holds has this correlation, or it was already forgotten. Nothing changed. */
    public data object Unknown : Forgotten
}

/**
 * The caller-side call table: [capacity] slots, each with a generation, the
 * call's outcome status and one waker, and an optional byte [budget].
 * `ridl_rt::correlate::Table`, with [capacity] for the Rust `N`.
 *
 * A [Correlation] is `(generation shl 16) or slot`: the slot index in the low
 * 16 bits and the slot's generation above it, so [capacity] is at most 65536
 * and 48 bits of generation remain. A slot is reclaimed by [forget] alone — at
 * once for a settled call, and at the settlement for a call in flight — and its
 * generation advances when it is, so the correlation the slot had before
 * answers as unknown. The table stores each call's outcome status and one
 * `Interest.Outcome` waker, but no reply bytes: a runtime keeps those in
 * storage of its own indexed by [slot].
 *
 * [budget] is the byte budget reservations are debited from — a runtime with a
 * catalog descriptor sizes it with `tableBudget` — or `null` for no budget, in
 * which case only the slot count bounds the calls in flight.
 *
 * @throws IllegalArgumentException when [capacity] is not between 0 and 65536,
 *   which Rust refuses at compile time.
 */
public class Table(public val capacity: Int, budget: ULong?) {
    init {
        require(capacity in 0..(1 shl SLOT_BITS)) { "a correlate.Table holds at most 65536 slots, not $capacity" }
    }

    /** Where one slot's call is. */
    private sealed interface State {
        data object Free : State

        data class InFlight(val forgotten: Boolean) : State

        data class Settled(val outcome: Result<Unit>) : State
    }

    private class Entry {
        var generation = 0L
        var state: State = State.Free

        /** The bytes debited from the budget at insert, credited at reclaim. */
        var reservation = 0uL

        /** The call's one `Interest.Outcome` waker. */
        var waker: Waker? = null
    }

    private val slots = Array(capacity) { Entry() }

    /** The bytes still free, or `null` for no budget. */
    private var budget: ULong? = budget

    /**
     * Takes the lowest free slot for a new call and debits [reservation] from
     * the budget. `null` when every slot is in flight or settled and
     * unforgotten, or when the budget has fewer than [reservation] bytes free;
     * a runtime answers either with `SendError.Busy`. Without a budget,
     * [reservation] is not read.
     */
    public fun insert(reservation: ULong): Correlation? {
        val index = slots.indexOfFirst { it.state == State.Free }
        if (index < 0) return null
        budget?.let { free ->
            if (free < reservation) return null
            budget = free - reservation
        }
        val entry = slots[index]
        entry.state = State.InFlight(forgotten = false)
        entry.reservation = reservation
        return Correlation((entry.generation shl SLOT_BITS) or index.toLong())
    }

    /**
     * Records the outcome of the call in flight under [c], or, when the call
     * was forgotten, reclaims its slot. The first settlement of a call is its
     * outcome; a later one is [Settled.Unknown] and changes nothing. [outcome]
     * is `Result.success(Unit)` or a failure carrying a `CallError`.
     */
    public fun settle(c: Correlation, outcome: Result<Unit>): Settled {
        val index = held(c) ?: return Settled.Unknown
        val entry = slots[index]
        return when (val state = entry.state) {
            is State.InFlight -> if (state.forgotten) {
                reclaim(index)
                Settled.Reclaimed
            } else {
                entry.state = State.Settled(outcome)
                Settled.Recorded(entry.waker.also { entry.waker = null })
            }
            is State.Settled, State.Free -> Settled.Unknown
        }
    }

    /**
     * The outcome recorded for [c], or `null` while it is in flight, after it
     * is forgotten, or when the table holds no call under [c]. Reading it does
     * not reclaim the slot: only [forget] does.
     */
    public fun outcome(c: Correlation): Result<Unit>? = (slots[held(c) ?: return null].state as? State.Settled)?.outcome

    /**
     * Releases [c], the one operation that reclaims a slot: at once for a
     * settled call, and at its settlement for a call in flight, which this
     * marks. Either way [c] has no readable outcome afterwards.
     */
    public fun forget(c: Correlation): Forgotten {
        val index = held(c) ?: return Forgotten.Unknown
        val entry = slots[index]
        return when (val state = entry.state) {
            is State.Settled -> {
                reclaim(index)
                Forgotten.Reclaimed
            }
            is State.InFlight -> if (state.forgotten) {
                Forgotten.Unknown
            } else {
                entry.state = State.InFlight(forgotten = true)
                Forgotten.Marked(entry.waker.also { entry.waker = null })
            }
            State.Free -> Forgotten.Unknown
        }
    }

    /**
     * Stores [waker] as the `Interest.Outcome(c)` waker of the call in flight
     * under [c], and returns the waker to wake: the displaced waker of another
     * task; nothing when the same waker is stored, which is a refresh; or
     * [waker] itself, not stored, when no outcome is still to be recorded under
     * [c] — the outcome is known, the call was forgotten, or the table holds no
     * call under [c].
     */
    public fun wakeOn(c: Correlation, waker: Waker): Waker? {
        val index = held(c) ?: return waker
        val entry = slots[index]
        val state = entry.state
        if (state !is State.InFlight || state.forgotten) return waker
        return store(entry.waker, waker).also { entry.waker = waker }
    }

    /** The slot of [c] when that slot holds a call under [c]'s generation. */
    private fun held(c: Correlation): Int? {
        val index = slot(c)
        val entry = slots.getOrNull(index) ?: return null
        val generation = c.value ushr SLOT_BITS
        return index.takeIf { entry.generation == generation && entry.state != State.Free }
    }

    /** Frees a slot: credits its reservation, drops its waker, and advances its generation. */
    private fun reclaim(index: Int) {
        val entry = slots[index]
        budget?.let { free -> budget = if (ULong.MAX_VALUE - free < entry.reservation) ULong.MAX_VALUE else free + entry.reservation }
        entry.state = State.Free
        entry.reservation = 0uL
        entry.waker = null
        entry.generation = (entry.generation + 1) and GENERATION_MASK
    }

    public companion object {
        /** The slot index of [c], which a runtime uses to index its own storage for the call beside the table. */
        public fun slot(c: Correlation): Int = (c.value and SLOT_MASK).toInt()
    }
}

/**
 * The waiter registry a handle keeps behind `Wakeable`: one waker for each of
 * the kinds `Slot`, `Event` and `Claim`. `ridl_rt::correlate::Waiters`.
 *
 * An `Event` or `Claim` key's interface is not kept: the handle holds one waker
 * per kind, and a change to any key of that kind takes it (ADR-0021 decision
 * 13). `Interest.Outcome` is not stored here; a call's outcome waker is kept
 * with the call, in [Table].
 */
public class Waiters {
    private var slot: Waker? = null
    private var event: Waker? = null
    private var claim: Waker? = null

    /**
     * Stores [waker] as the one waker of [what]'s kind, and returns the waker
     * to wake: the displaced waker of another task; nothing when the same
     * waker is stored, a refresh whatever interface either key named; or
     * [waker] itself, not stored, for `Interest.Outcome`, which a [Table] holds.
     */
    public fun register(what: Interest, waker: Waker): Waker? = when (what) {
        Interest.Slot -> store(slot, waker).also { slot = waker }
        is Interest.Event -> store(event, waker).also { event = waker }
        is Interest.Claim -> store(claim, waker).also { claim = waker }
        is Interest.Outcome -> waker
    }

    /**
     * Takes the waker of [what]'s kind, whatever interface it was registered
     * under, which clears that kind. `null` when none is stored, and always
     * for `Interest.Outcome`.
     */
    public fun take(what: Interest): Waker? = when (what) {
        Interest.Slot -> slot.also { slot = null }
        is Interest.Event -> event.also { event = null }
        is Interest.Claim -> claim.also { claim = null }
        is Interest.Outcome -> null
    }

    /** Takes every stored waker, which clears every kind, for a runtime with one unkeyed "something changed" source. */
    public fun takeAll(): List<Waker> = listOfNotNull(slot, event, claim).also {
        slot = null
        event = null
        claim = null
    }
}

/** The displaced waker of another task, or `null` on a refresh by the same one (the same object). */
private fun store(stored: Waker?, waker: Waker): Waker? = stored?.takeIf { it !== waker }
