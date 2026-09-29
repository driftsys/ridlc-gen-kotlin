// Time, the envelope, and the values a read returns (ridl §3.1, §4.5, §9):
// the spelling of `ridl_rt::sample` (docs/design.md §3).
package ridl.rt.sample

import ridl.rt.RidlError
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.contract.Timing
import ridl.rt.payload.Violation

/**
 * A point in time, in microseconds since the PTP epoch, on the TAI time scale
 * (ridl §3.1). `ridl_rt::sample::Timestamp`.
 */
@JvmInline
public value class Timestamp(public val micros: Long) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int = micros.compareTo(other.micros)
}

/**
 * A length of time, in microseconds. `ridl_rt::sample::Duration`; not
 * `kotlin.time.Duration`, so generated code names it in full.
 */
@JvmInline
public value class Duration(public val micros: Long) : Comparable<Duration> {
    override fun compareTo(other: Duration): Int = micros.compareTo(other.micros)
}

/**
 * The sender's timestamp and sequence number (ridl §3.1). The runtime stamps
 * it from its clock, and no relay changes it. `ridl_rt::sample::Envelope`.
 */
public data class Envelope(
    /** When the sender published, raised or called the instance (ridl §3.1). */
    public val stamp: Timestamp,
    /** The sender's sequence number. On an event channel, a gap is a loss (ridl §3.1). */
    public val seq: ULong,
)

/** Where a signal's value comes from (ridl §4.5). `ridl_rt::sample::Provenance`. */
public sealed interface Provenance {
    /** No publication yet: the value is the init value. */
    public data object Init : Provenance

    /** The value is the latest publication. */
    public data object Live : Provenance

    /**
     * The channel is in the invalid state: the value is the last good value,
     * or the init value when there is none.
     */
    public data class Invalid(public val cause: Cause) : Provenance
}

/** Why a channel is invalid. `ridl_rt::sample::Cause`. */
public sealed interface Cause {
    /** The provider declared the invalid state (ridl §4.5). */
    public data object Declared : Cause

    /** The consumer's binding detected an invalid payload. */
    public data class Detected(public val detection: Detection) : Cause
}

/**
 * What a consumer's binding detected in a payload. `ridl_rt::sample::Detection`.
 *
 * An error, so that an [Occurrence] carries it in a Kotlin [Result] as a call
 * outcome carries a [ridl.rt.error.CallError].
 */
public sealed class Detection(message: String) : RidlError(message) {
    /** The payload breaks a typl constraint: `INVALID_VALUE`, ridl §10.2. */
    public data class InvalidValue(public val violation: Violation) : Detection("INVALID_VALUE: $violation")

    /** The payload is not a well-formed encoding: a serialization failure, ridl §10.3. */
    public data object Corrupt : Detection("the payload is not a well-formed encoding")
}

/** How old a value is, measured against its staleness bound (ridl §9). `ridl_rt::sample::Freshness`. */
public sealed interface Freshness {
    /** Within the bound. */
    public data object Fresh : Freshness

    /** Older than the bound, by [by]. */
    public data class Stale(public val by: Duration) : Freshness

    /**
     * The value has no staleness bound: its member's timing has no `max`, as
     * under `@[1s..]`. A signal with no `@` annotation is not unbounded,
     * because it receives the default range (ridl §9.1).
     */
    public data object Unbounded : Freshness

    public companion object {
        /**
         * The freshness of a value whose envelope is [envelope], read at
         * [now], against the `max` of its member's [timing] (ridl §4, §9;
         * frame specification §8). `ridl_rt::sample::Freshness::of`.
         *
         * `Fresh` while `now − stamp ≤ max`, `Stale(by = now − stamp − max)`
         * past it, and `Unbounded` when [timing] is `null` or carries no
         * `max`. `min` plays no part. Under a strict period `@Xms`, `max`
         * holds the period. A stamp later than [now] gives a negative age,
         * which the formula makes `Fresh`. The age saturates at the ends of
         * the `Long` range instead of overflowing.
         */
        public fun of(envelope: Envelope, now: Timestamp, timing: Timing?): Freshness {
            val max = timing?.max ?: return Unbounded
            val age = saturatingSub(now.micros, envelope.stamp.micros)
            return if (age <= max.micros) Fresh else Stale(Duration(saturatingSub(age, max.micros)))
        }

        /** `a − b`, clamped to the `Long` range, as Rust's `i64::saturating_sub`. */
        private fun saturatingSub(a: Long, b: Long): Long {
            val difference = a - b
            // Overflow happened when the operands have different signs and the result's sign differs from a's.
            if (((a xor b) and (a xor difference)) < 0) return if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
            return difference
        }
    }
}

/** A signal value with its provenance, its freshness and its envelope. `ridl_rt::sample::Sample`. */
public data class Sample<out T>(
    /**
     * The value. Never absent: the init value under [Provenance.Init], and the
     * last good value or the init value under [Provenance.Invalid].
     */
    public val value: T,
    /** Where the value comes from. */
    public val provenance: Provenance,
    /** How old the value is. */
    public val freshness: Freshness,
    /** The sender's timestamp and sequence number. */
    public val envelope: Envelope,
) {
    /** `true` when the provenance is [Provenance.Live] and the freshness is not [Freshness.Stale]. */
    public fun usable(): Boolean = provenance == Provenance.Live && freshness !is Freshness.Stale
}

/**
 * An event occurrence as a consumer receives it. `ridl_rt::sample::Occurrence`.
 *
 * [payload] is the value, or a failure carrying the [Detection] the binding
 * made when the payload failed its check.
 */
public data class Occurrence<out T>(
    /** The payload, or what the binding detected. */
    public val payload: Result<T>,
    /** The sender's timestamp and sequence number. */
    public val envelope: Envelope,
)

/**
 * What [EventSeqTracker.observe] found when it compared an occurrence's `seq`
 * with the last one its channel accepted (ridl §3.1; frame specification §7).
 * `ridl_rt::sample::Continuity`.
 */
public sealed interface Continuity {
    /**
     * The first occurrence the tracker has seen on this channel. No loss is
     * reported, because a consumer receives only the occurrences raised after
     * its subscription was answered (frame specification §6.2), so the first
     * one may carry any `seq`.
     */
    public data object First : Continuity

    /** The `seq` is one more than the last: nothing was lost. */
    public data object Next : Continuity

    /**
     * The `seq` is more than one past the last: [count] occurrences between
     * the two were lost (ridl §3.1: "sequence gaps make loss detectable").
     * [count] is `seq − last − 1`.
     */
    public data class Lost(public val count: ULong) : Continuity

    /**
     * The `seq` is not greater than the last: a duplicate or a reordered
     * occurrence. The tracker keeps [last]. What the caller does with the
     * occurrence is the caller's decision.
     */
    public data class NotNewer(public val last: ULong) : Continuity
}

/**
 * Thrown by [EventSeqTracker.observe] when the occurrence is on a channel the
 * tracker does not hold and every slot is taken. `ridl_rt::sample::TrackerFull`.
 */
public data object TrackerFull : RidlError("the tracker holds no free slot for another channel")

/**
 * The last `seq` accepted on each event channel of one session, and the loss
 * each new occurrence reveals (ridl §3.1; frame specification §5.2 and §7).
 * `ridl_rt::sample::EventSeqTracker`, with [capacity] for the Rust `N`.
 *
 * The sequence counter of an event is per channel, per provider instance, and
 * starts over with each session (frame specification §3, §7). A channel is one
 * `(InterfaceNo, Ordinal)` in the session's catalog, so the tracker keys on
 * that pair and never on the interface alone: two channels of one interface
 * whose occurrences interleave are not a loss. One tracker serves one session;
 * a new session starts with a new tracker.
 *
 * The tracker holds at most [capacity] channels. A channel that finds no free
 * slot is refused with [TrackerFull]; [forget] frees a slot, for example when
 * the consumer unsubscribes (frame specification §6.2). Feed it every
 * occurrence the channel accepts, in the order received.
 */
public class EventSeqTracker(public val capacity: Int) {
    private class Channel(val iface: InterfaceNo, val ord: Ordinal, var last: ULong)

    private val slots = arrayOfNulls<Channel>(capacity)

    /**
     * Records [seq] as the latest occurrence on the channel `(iface, ord)` and
     * reports what it reveals. The first occurrence on a channel takes a free
     * slot and reports [Continuity.First]. A later one reports
     * [Continuity.Next] or [Continuity.Lost] and becomes the channel's last
     * `seq`, or reports [Continuity.NotNewer] and leaves the last as it was.
     *
     * @throws TrackerFull when the channel is not tracked and no slot is free;
     *   every tracked channel is unchanged.
     */
    public fun observe(iface: InterfaceNo, ord: Ordinal, seq: ULong): Continuity {
        var free = -1
        for ((index, channel) in slots.withIndex()) {
            if (channel == null) {
                if (free < 0) free = index
                continue
            }
            if (channel.iface != iface || channel.ord != ord) continue
            val last = channel.last
            if (seq <= last) return Continuity.NotNewer(last)
            channel.last = seq
            val count = seq - last - 1u
            return if (count == 0uL) Continuity.Next else Continuity.Lost(count)
        }
        if (free < 0) throw TrackerFull
        slots[free] = Channel(iface, ord, seq)
        return Continuity.First
    }

    /**
     * Stops tracking the channel `(iface, ord)` and frees its slot. The next
     * occurrence on that channel reports [Continuity.First]. A channel the
     * tracker does not hold is left alone.
     */
    public fun forget(iface: InterfaceNo, ord: Ordinal) {
        for ((index, channel) in slots.withIndex()) {
            if (channel != null && channel.iface == iface && channel.ord == ord) slots[index] = null
        }
    }
}
