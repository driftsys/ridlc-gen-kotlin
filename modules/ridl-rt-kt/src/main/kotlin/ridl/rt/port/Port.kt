// The ports: the interfaces a runtime implements and generated code calls.
// The spelling of `ridl_rt::port` (docs/design.md §3, "the ports").
//
// Every port method returns without waiting. A port carries interface
// numbers, ordinals and bytes, never a payload type: the generated binding
// decodes. Two spellings differ from Rust, for the JVM:
//
// - where a Rust method takes `out: &mut [u8]` and returns a length, the
//   Kotlin method takes a `ByteBuffer`, writes from its position and advances
//   it, and still reports the length. Where a Rust method takes `&[u8]`, the
//   Kotlin method reads the buffer from its position to its limit and leaves
//   the position where it was, as a borrowed slice is left;
// - where a Rust method returns `Result<T, E>`, the Kotlin method returns `T`
//   and throws `E` (D-K3). A state that is not a failure — an outcome not yet
//   known, no occurrence waiting — is a `null`.
package ridl.rt.port

import ridl.rt.RidlError
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer

/** A port attached to one catalog. `ridl_rt::port::Attached`. */
public interface Attached {
    /** The catalog this port serves. */
    public val catalog: CatalogRef
}

/** The clock envelopes are stamped from. A runtime has one. `ridl_rt::port::Clock`. */
public interface Clock {
    /** The current time in the platform time base. */
    public fun now(): Timestamp
}

/** Signals, consumer side. `ridl_rt::port::SignalReader`. */
public interface SignalReader : Attached {
    /**
     * Copies the signal's current value into [out] from its position and
     * returns its provenance, its freshness and its envelope.
     *
     * @throws ReadError.Short when [out] has fewer bytes remaining than the value.
     */
    public fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample
}

/** What [SignalReader.read] returns beside the copied bytes. `ridl_rt::port::RawSample`. */
public data class RawSample(
    /** Where the value comes from. */
    public val provenance: Provenance,
    /** How old the value is. */
    public val freshness: Freshness,
    /** The sender's timestamp and sequence number. */
    public val envelope: Envelope,
    /** The number of bytes copied into `out`. */
    public val len: Int,
)

/**
 * Signals, provider side. `ridl_rt::port::SignalWriter`.
 *
 * [set], [invalidate] and [touch] stage a change; [commit] publishes every
 * staged change, with one generation increment and one timestamp per
 * interface.
 */
public interface SignalWriter : Attached {
    /** Stages a new value: the bytes from [bytes]' position to its limit. */
    public fun set(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer)

    /** Stages the invalid state (ridl §4.5), with [ridl.rt.sample.Cause.Declared]. */
    public fun invalidate(iface: InterfaceNo, ord: Ordinal)

    /** Stages a re-affirmation of the current value, without a new value. */
    public fun touch(iface: InterfaceNo, ord: Ordinal)

    /**
     * Publishes everything staged. It cannot fail: a caller learns that the
     * runtime is gone from [WriteError.Detached] on a later stage call.
     */
    public fun commit()
}

/** Events, consumer side. `ridl_rt::port::EventSource`. */
public interface EventSource : Attached {
    /** Starts delivery of the listed events. */
    public fun subscribe(iface: InterfaceNo, ords: List<Ordinal>)

    /** Stops delivery of the listed events. */
    public fun unsubscribe(iface: InterfaceNo, ords: List<Ordinal>)

    /**
     * Copies the next occurrence into [out] from its position. `null` when no
     * occurrence is waiting. [ReadError.Short] does not consume the
     * occurrence: the next call returns the same one.
     */
    public fun next(out: ByteBuffer): RawOccurrence?
}

/** What [EventSource.next] returns beside the copied bytes. `ridl_rt::port::RawOccurrence`. */
public data class RawOccurrence(
    /** The interface of the event. */
    public val iface: InterfaceNo,
    /** The ordinal of the event. */
    public val ord: Ordinal,
    /** The sender's timestamp and sequence number. */
    public val envelope: Envelope,
    /** The number of bytes copied into `out`. */
    public val len: Int,
)

/** Events, provider side. `ridl_rt::port::EventSink`. */
public interface EventSink : Attached {
    /** Raises one occurrence: the bytes from [bytes]' position to its limit. */
    public fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer)
}

/** Calls, consumer side. `ridl_rt::port::Caller`. */
public interface Caller : Attached {
    /** Sends a command and returns the correlation of its outcome. */
    public fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation

    /** Sends a query and returns the correlation of its reply. */
    public fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer): Correlation

    /**
     * A command's delivery acknowledgment (ridl §6.1), once it is known: a
     * success when accepted, a failure carrying a [ridl.rt.error.CallError]
     * when rejected or undelivered. `null` while unknown, and always `null`
     * for a query's correlation.
     */
    public fun ack(c: Correlation): Result<Unit>?

    /**
     * A query's reply, once it is known: the reply bytes copied into [out]
     * from its position and their length, or a failure carrying the
     * [ridl.rt.error.CallError]. `null` while unknown.
     *
     * @throws ReadError.Short when [out] is too short; the reply is not consumed.
     * @throws ReadError.Detached when the local runtime is gone.
     */
    public fun reply(c: Correlation, out: ByteBuffer): Result<Int>?

    /** Releases a correlation whose outcome the caller no longer needs. */
    public fun forget(c: Correlation)
}

/** Identifies one sent call to its caller. `ridl_rt::port::Correlation`. */
@JvmInline
public value class Correlation(public val value: Long)

/** Calls, provider side. `ridl_rt::port::Handler`. */
public interface Handler : Attached {
    /** Starts presenting calls to the listed members. */
    public fun serve(iface: InterfaceNo, ords: List<Ordinal>)

    /**
     * Copies the next call's arguments into [out] from its position. `null`
     * when no call is waiting. [ReadError.Short] does not consume the call.
     */
    public fun nextClaim(out: ByteBuffer): Claim?

    /**
     * Settles a claim with the reply bytes (empty for a command) or, as a
     * failure carrying a [ridl.rt.error.CallError], the outcome the caller
     * sees. A settled outcome is a value, not a throw.
     */
    public fun settle(claim: ClaimId, outcome: Result<ByteBuffer>)
}

/** One call presented to a provider. `ridl_rt::port::Claim`. */
public data class Claim(
    /** Unique in its channel. */
    public val id: ClaimId,
    /** The interface of the call. */
    public val iface: InterfaceNo,
    /** The ordinal of the call. The descriptor at this ordinal gives the kind. */
    public val ord: Ordinal,
    /** The caller's timestamp and sequence number. */
    public val envelope: Envelope,
    /** The time left before the response bound passes. `null` when the call has no response bound (ridl §9.3). */
    public val remaining: Duration?,
    /** The number of argument bytes copied into `out`. */
    public val len: Int,
)

/** Identifies one claim to its provider. `ridl_rt::port::ClaimId`. */
@JvmInline
public value class ClaimId(public val value: Long)

/** `fixed`, consumer side (ridl §8). `ridl_rt::port::FixedReader`. */
public interface FixedReader : Attached {
    /** Copies the provisioned value into [out] from its position and returns its length. */
    public fun readFixed(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): Int
}

/** Extension: signals in a store a consumer can walk. A runtime may omit it. `ridl_rt::port::ScannableSignals`. */
public interface ScannableSignals : SignalReader {
    /** The interface's generation: a counter that each commit to the interface increments. */
    public fun generation(iface: InterfaceNo): ULong

    /**
     * Writes the changes into [out], interface by interface, in the order of
     * [marks], and updates [marks] in place. An interface's changes are
     * written all together or not at all. Returns the number of entries
     * written; compare each mark with [generation] to tell a complete scan
     * from one that ran out of room.
     */
    public fun scan(marks: Array<Watermark>, out: Array<Changed?>): Int
}

/** How far a consumer has scanned one interface. `ridl_rt::port::Watermark`. */
public data class Watermark(
    /** The interface. */
    public val iface: InterfaceNo,
    /** The generation last scanned. */
    public val generation: ULong,
    /** The sequence number last scanned. */
    public val seq: ULong,
)

/** A signal that changed since a [Watermark]. `ridl_rt::port::Changed`. */
public data class Changed(
    /** The interface of the signal. */
    public val iface: InterfaceNo,
    /** The ordinal of the signal. */
    public val ord: Ordinal,
    /** The signal's sequence number. */
    public val seq: ULong,
)

/**
 * Extension: reads of several signals of one interface from one publication.
 * `ridl_rt::port::CoherentSignals`.
 */
public interface CoherentSignals : SignalReader {
    /**
     * Answers every ordinal in [ords] from one publication: copies the values
     * into [out] one after another, writes one [RawSample] per ordinal into
     * [samples] in the order of [ords], and returns the bytes written.
     *
     * @throws ReadError.Short when [out] is too short for the whole set.
     * @throws ReadError.TooFewSamples when [samples] is shorter than [ords].
     */
    public fun readCoherent(iface: InterfaceNo, ords: List<Ordinal>, out: ByteBuffer, samples: Array<RawSample?>): Int
}

/**
 * Extension: a port that can wake a task. `ridl_rt::port::Wakeable`. It is how
 * a face that waits learns when to read a port again, and a runtime that
 * serves a generated client that waits implements it.
 *
 * No port method waits, so a task registers its interest here, reads the
 * port, and returns when the read finds nothing; the runtime wakes the task
 * when the thing it waits for may have changed, and the task reads the port
 * again. The contract:
 *
 * - **One waker per kind of key per handle.** [wakeOn] stores [waker][Waker]
 *   on the handle it is called on, one for each kind — `Slot`, `Event`,
 *   `Claim` — and an `Outcome` waker with its call. A change to any key of
 *   that kind that the handle observes wakes the stored waker, so a task that
 *   registers `Event(a)` and then `Event(b)` is woken by an occurrence of
 *   either; the task reads the port again and finds out which.
 * - **A refresh or a displacement.** A `wakeOn` with the waker already stored,
 *   the same object, is a refresh: it replaces the stored waker without waking
 *   it, because a task registers on every poll. A waker of another task
 *   displaces the stored one, and the displaced waker is woken, so no task
 *   waits on a registration that can no longer fire. A second task waiting for
 *   the same events holds a second handle, and each handle's waiter is woken.
 * - **Woken at most once.** A stored waker is woken after every change of its
 *   kind becomes visible, and is cleared when woken. A spurious wake is
 *   allowed: a runtime with one unkeyed "something changed" source may wake
 *   every waiter it holds on any change.
 * - **Register, then read.** The caller registers on every poll, and
 *   registers before it reads the port, so a change between the read and the
 *   return still wakes it.
 *
 * [Interest.Event] and [Interest.Claim] are keyed per interface, because
 * [EventSource.next] and [Handler.nextClaim] drain one queue whatever the
 * ordinal, and the subscription and the served set already filter by member.
 */
public interface Wakeable {
    /** Wakes [waker] when the thing [what] names may have changed, under the contract above. */
    public fun wakeOn(what: Interest, waker: Waker)
}

/**
 * What a task waits for, as [Wakeable.wakeOn] takes it.
 * `ridl_rt::port::Interest`. Sealed: a runtime handles every key, because an
 * unknown key has no safe default.
 */
public sealed interface Interest {
    /** The outcome of one call is known. */
    public data class Outcome(public val correlation: Correlation) : Interest

    /** A slot for a new call is free. */
    public data object Slot : Interest

    /** An occurrence of one of the interface's events is waiting. */
    public data class Event(public val iface: InterfaceNo) : Interest

    /** A claim on one of the interface's members is waiting. */
    public data class Claim(public val iface: InterfaceNo) : Interest
}

/** A read that failed. `ridl_rt::port::ReadError`. */
public sealed class ReadError(message: String) : RidlError(message) {
    /** The output buffer is too short. Nothing was consumed. */
    public data class Short(public val needed: Int) : ReadError("the read needs $needed bytes")

    /** `samples` has fewer entries than `ords`. Nothing was consumed. */
    public data class TooFewSamples(public val needed: Int) : ReadError("the read needs $needed samples")

    /** A contract error, such as an unknown interaction. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : ReadError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : ReadError("the runtime behind the port is gone")
}

/** A [SignalWriter] stage call that failed. `ridl_rt::port::WriteError`. */
public sealed class WriteError(message: String) : RidlError(message) {
    /** The value is larger than the signal's capacity. */
    public data class TooLarge(public val cap: Int) : WriteError("the value is larger than $cap bytes")

    /** This provider does not own the signal. */
    public data object NotOwner : WriteError("this provider does not own the signal")

    /** A contract error, such as an unknown interaction. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : WriteError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : WriteError("the runtime behind the port is gone")
}

/** An [EventSink.raise] that failed. `ridl_rt::port::RaiseError`. */
public sealed class RaiseError(message: String) : RidlError(message) {
    /** The runtime cannot accept an occurrence now. Retryable. */
    public data object Busy : RaiseError("the runtime cannot accept an occurrence now")

    /** The occurrence is larger than the event's capacity. */
    public data class TooLarge(public val cap: Int) : RaiseError("the occurrence is larger than $cap bytes")

    /** This provider does not own the event. */
    public data object NotOwner : RaiseError("this provider does not own the event")

    /** A contract error, such as an unknown interaction. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : RaiseError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : RaiseError("the runtime behind the port is gone")
}

/** A [Caller.command] or [Caller.query] that failed before sending. `ridl_rt::port::SendError`. */
public sealed class SendError(message: String) : RidlError(message) {
    /** The runtime cannot accept a call now. Retryable. */
    public data object Busy : SendError("the runtime cannot accept a call now")

    /** The arguments are larger than the call's capacity. */
    public data class TooLarge(public val cap: Int) : SendError("the arguments are larger than $cap bytes")

    /** A contract error, such as an unknown interaction. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : SendError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : SendError("the runtime behind the port is gone")
}

/** An [EventSource.subscribe] that failed. `ridl_rt::port::SubscribeError`. */
public sealed class SubscribeError(message: String) : RidlError(message) {
    /** A contract error: an unknown interaction fails when subscribing (ridl §10.2). */
    public data class Contract(public val contract: ridl.rt.error.Contract) : SubscribeError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : SubscribeError("the runtime behind the port is gone")
}

/** A [Handler.serve] that failed. `ridl_rt::port::ServeError`. */
public sealed class ServeError(message: String) : RidlError(message) {
    /** A contract error, such as an unknown interaction. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : ServeError(contract.message!!)

    /** This provider does not own the call. */
    public data object NotOwner : ServeError("this provider does not own the call")

    /** The runtime behind the port is gone. */
    public data object Detached : ServeError("the runtime behind the port is gone")
}

/** A [Handler.settle] that failed. `ridl_rt::port::SettleError`. */
public sealed class SettleError(message: String) : RidlError(message) {
    /** The claim was already settled, or was never issued. */
    public data object UnknownClaim : SettleError("the claim was already settled, or was never issued")

    /** The reply is larger than the call's capacity. */
    public data class TooLarge(public val cap: Int) : SettleError("the reply is larger than $cap bytes")

    /** The runtime behind the port is gone. */
    public data object Detached : SettleError("the runtime behind the port is gone")
}
