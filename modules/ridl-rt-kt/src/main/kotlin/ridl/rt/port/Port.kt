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
import ridl.rt.contract.StreamNo
import ridl.rt.encoding.Encoding
import ridl.rt.error.CallError
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import ridl.rt.trace.TraceContext
import java.nio.ByteBuffer

/** A port attached to one catalog. `ridl_rt::port::Attached`. */
public interface Attached {
    /** The catalog this port serves. */
    public val catalog: CatalogRef
}

/**
 * The encoding of the bytes a port carries. `ridl_rt::port::Encoded`.
 *
 * A separate interface, not a supertype of [Attached], so a port that never
 * meets a face — a conformance fixture — is not forced to name an encoding.
 * A generated face takes its payload encoding from its port: every face over
 * a port requires `Encoded`, and refuses at construction a port whose
 * [encoding] the package carries no codec for, which Rust refuses at compile
 * time. Rust states the encoding as the port's associated type; Kotlin, whose
 * generics are erased, as a value the face reads from the port.
 */
public interface Encoded {
    /** The encoding of the bytes this port carries. */
    public val encoding: Encoding
}

/** The clock envelopes are stamped from. A runtime has one. `ridl_rt::port::Clock`. */
public interface Clock {
    /** The current time in the platform time base. */
    public fun now(): Timestamp
}

/** The number of a call's reply stream (ridl §12). A stream parameter is numbered from 1. `ridl_rt::port::REPLY_STREAM`. */
public val REPLY_STREAM: StreamNo = StreamNo(0u)

/**
 * A payload check registered by a generated binding. `ridl_rt::port::Check`.
 *
 * [check] takes the payload from the buffer's position to its limit, and throws
 * the [ridl.rt.sample.Detection] when the payload fails. It calls no port, so a
 * runtime may call it while holding its own lock.
 */
public fun interface Check {
    /** @throws ridl.rt.sample.Detection when [bytes] fail the check. */
    public fun check(bytes: ByteBuffer)
}

/**
 * Signals, consumer side. `ridl_rt::port::SignalReader`.
 *
 * The binding registers a check through [checkWith], so the runtime keeps the
 * last good bytes when a payload fails. A [ReadError] reports the read itself.
 */
public interface SignalReader : Attached {
    /**
     * Registers or replaces the check for one signal.
     *
     * From this call on, the runtime calls [check] on every payload it receives
     * for this signal under `Live`, before replacing its held value. When the
     * check passes, the payload becomes the value. When it throws a detection,
     * the runtime keeps the last good bytes (no bytes when there are none),
     * reports `Invalid(Cause.Detected(detection))`, and takes the rejected
     * publication's envelope. A later passing `Live` payload restores `Live`.
     *
     * A held `Live` value is checked at once. When it fails, its bytes are
     * cleared and its provenance becomes `Invalid(Cause.Detected(detection))`
     * with the held envelope. Registration is not a publication: it advances no
     * generation and changes no `seq`. A second call replaces the check.
     */
    public fun checkWith(iface: InterfaceNo, ord: Ordinal, check: Check)

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
    /** The trace context the occurrence was raised with, under the delivery contract of [EventSink]. */
    public val trace: TraceContext?,
    /** The number of bytes copied into `out`. */
    public val len: Int,
)

/**
 * Events, provider side. `ridl_rt::port::EventSink`.
 *
 * The delivery contract of the trace context, as for [Caller] (ADR-0021
 * decision 21): a runtime that carries it delivers, on every [RawOccurrence]
 * a `raise` produces — one for each subscriber — the value its sender
 * passed, unchanged; a runtime or transport that does not carry it delivers
 * `null`; and a sender's `null` is delivered as `null`.
 */
public interface EventSink : Attached {
    /**
     * Raises one occurrence: the bytes from [bytes]' position to its limit,
     * with the sender's [trace] context or `null`.
     */
    public fun raise(iface: InterfaceNo, ord: Ordinal, bytes: ByteBuffer, trace: TraceContext?)
}

/**
 * Calls, consumer side. `ridl_rt::port::Caller`.
 *
 * The delivery contract of the trace context (ADR-0021 decision 21): a
 * runtime that carries it delivers, on the [Claim] a command or a query
 * produces, and on the [ReadError.ShortClaim] that offers it, the value its
 * sender passed, unchanged; a runtime or transport that does not carry it
 * delivers `null`; and a sender's `null` is delivered as `null`.
 */
public interface Caller : Attached {
    /** Sends a command, with the sender's [trace] context or `null`, and returns the correlation of its outcome. */
    public fun command(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer, trace: TraceContext?): Correlation

    /** Sends a query, with the sender's [trace] context or `null`, and returns the correlation of its reply. */
    public fun query(iface: InterfaceNo, ord: Ordinal, args: ByteBuffer, trace: TraceContext?): Correlation

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
     * when no call is waiting. When [out] has fewer bytes remaining than the
     * next call's arguments, throws [ReadError.ShortClaim] with that call's
     * [ClaimId] and the bytes it needs, and does not consume the call: a
     * later `nextClaim` with at least `needed` bytes presents the same call
     * under the same id. The id is assigned when the call is first presented,
     * through `ShortClaim` or through a returned [Claim], and is unique in its
     * channel. `nextClaim` never throws [ReadError.Short] (driftsys/ridl#569).
     */
    public fun nextClaim(out: ByteBuffer): Claim?

    /**
     * Settles a claim with the reply bytes (empty for a command) or, as a
     * failure carrying a [ridl.rt.error.CallError], the outcome the caller
     * sees. A settled outcome is a value, not a throw.
     *
     * A claim presented through [ReadError.ShortClaim] is settled the same
     * way, with any outcome, although its arguments were never read: `settle`
     * does not distinguish a read claim from an unread one. The settlement
     * takes the call out of the waiting calls, so a later [nextClaim] does
     * not present it.
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
    /** The trace context the call was sent with, under the delivery contract of [Caller]. */
    public val trace: TraceContext?,
    /** The time left before the response bound passes. `null` when the call has no response bound (ridl §9.3). */
    public val remaining: Duration?,
    /** The number of argument bytes copied into `out`. */
    public val len: Int,
)

/** Identifies one claim to its provider. `ridl_rt::port::ClaimId`. */
@JvmInline
public value class ClaimId(public val value: Long)

/**
 * Extension: the streams of a call, consumer side (ridl §12).
 * `ridl_rt::port::StreamCaller`.
 *
 * A runtime that carries streams implements it beside [Caller]; a runtime
 * without streams is still a complete runtime, so no other port names it as a
 * bound. A stream is keyed by its call's [Correlation] and a [StreamNo]:
 * [REPLY_STREAM] is the reply stream, and `StreamNo(k)` from 1 is the `k`-th
 * stream parameter. The caller sends the call with [Caller.command] or
 * [Caller.query], then pushes and ends each parameter stream and pulls the
 * reply stream until [Pull.End] or [Pull.Failed], then calls [Caller.forget].
 * A task that waits registers [Interest.Stream] with [Wakeable] before it pulls
 * or pushes. A push to a parameter stream may come before the provider takes
 * the call's claim: the runtime holds the pushed chunks, up to its window.
 *
 * The rules every method follows:
 *
 * - **The unit is the chunk.** A port never splits or merges a chunk: a pull
 *   copies exactly one chunk, and a push offers exactly one.
 * - **A push takes the whole chunk or nothing.** [push] returns the chunk's
 *   length when it was taken, and 0 when the stream's window is full and a
 *   non-empty chunk was not taken. That 0 is the backpressure: the producer
 *   waits on [Interest.Stream] and pushes the same chunk again. An empty chunk
 *   is always taken, whatever the window: its push returns 0 and the receiver
 *   pulls `Pull.Chunk(0)`.
 * - **A reply stream ends with its call's settlement.** The provider ends it
 *   with [StreamHandler.endClaim] on [REPLY_STREAM], which settles the call
 *   with no reply bytes. A settlement with an error ends the reply stream with
 *   that error, after the chunks already pushed.
 * - **A stop on purpose is an abort.** A producer interrupts its stream with
 *   [abort] and `Transport.Interrupted`; a receiver refuses what it received
 *   with [abort] and the refusal's outcome. A [Caller.forget] stops every
 *   stream of the call that was not ended with `Transport.Interrupted`, and
 *   keeps every stream its producer ended.
 *
 * Each method has two layers of error, as [Caller.reply] has. The port call
 * itself throws [ReadError] for a pull, and the `Contract` and `Detached` cases
 * of [PushError] for the others. The inner outcome, [Pull.Failed] or
 * [PushError.Failed], is the other side's verdict on the stream.
 */
public interface StreamCaller : Caller {
    /**
     * Copies the next chunk of stream [s] of call [c] into [out] from its
     * position, and returns [Pull.Chunk] with its length, [Pull.Pending] when
     * no chunk is waiting, [Pull.End] once the producer ended the stream and
     * every chunk before the end was pulled, or [Pull.Failed] when the stream
     * stopped without its end.
     *
     * A runtime that holds no member table accepts [REPLY_STREAM] on a query,
     * and `StreamNo(1)` and `StreamNo(2)` on any call.
     *
     * @throws ReadError.Short when the chunk does not fit [out], which does not
     *   consume it.
     * @throws ReadError.Contract with `UnknownInteraction` when the call or the
     *   stream number is unknown, and for a pull by the stream's producer.
     * @throws ReadError.Detached when the runtime behind the port is gone.
     */
    public fun pull(c: Correlation, s: StreamNo, out: ByteBuffer): Pull

    /**
     * Offers one chunk to stream [s] of call [c]: the length of [chunk] when it
     * was taken, and 0 when the window is full and a non-empty chunk was not.
     * An empty chunk is always taken and returns 0.
     *
     * @throws PushError
     */
    public fun push(c: Correlation, s: StreamNo, chunk: ByteBuffer): Int

    /**
     * Ends stream [s] of call [c]: the receiver pulls the chunks before the
     * end, then [Pull.End].
     *
     * @throws PushError.Ended on a second end.
     */
    public fun end(c: Correlation, s: StreamNo)

    /**
     * Stops stream [s] of call [c] with outcome [e], from either side of the
     * stream. When the producer aborts, the receiver pulls the chunks pushed
     * before the abort, then `Pull.Failed(e)`. When the receiver refuses, the
     * producer's next push or end throws `PushError.Failed(e)`, and the
     * receiver reads none of what it refused. After its own abort, a producer's
     * push and end throw [PushError.Ended].
     *
     * After the other side stopped the stream, [abort] throws
     * [PushError.Ended], while [push] and [end] throw [PushError.Failed]. An
     * abort of a stream that already ended or stopped throws [PushError.Ended]
     * and changes nothing for the producer; by the receiver, it still drops the
     * receiver's unread chunks.
     */
    public fun abort(c: Correlation, s: StreamNo, e: CallError)
}

/**
 * Extension: the streams of a call, provider side (ridl §12).
 * `ridl_rt::port::StreamHandler`.
 *
 * The counterpart of [StreamCaller], keyed by the [ClaimId] [Handler.nextClaim]
 * presented and a [StreamNo], under the same rules. The provider takes the
 * claim, then pulls each parameter stream until [Pull.End] and pushes the reply
 * stream until it calls [endClaim] on [REPLY_STREAM]. A task that waits
 * registers [Interest.ClaimStream] with [Wakeable].
 *
 * [Handler.settle] with an error on a claim whose reply is a stream has the
 * effect of [abortClaim] on [REPLY_STREAM] with that error, and a settlement
 * with no bytes ends it as [endClaim] does, except after the caller refused the
 * reply stream: [endClaim] throws `PushError.Failed` and settles with the
 * refusal, while `settle` records success and leaves the refusal on the stream.
 * A command's claim is settled at its acknowledgment, before any chunk of a
 * parameter stream is read; a refusal after that travels on the stream through
 * [abortClaim], and the acknowledgment is not changed.
 *
 * A claim offered through [ReadError.ShortClaim] and not yet taken has no
 * streams: a stream operation on it is `UnknownInteraction`, and a [Wakeable]
 * registration on it is woken at once.
 *
 * A handler closed while it holds a claim on which it already moved a stream
 * does not return the call to the waiting calls, because another handler would
 * read partial streams: every stream that has not ended stops with
 * `Transport.Interrupted`, and the call settles with that error. A pull that
 * returns [Pull.Pending] and a registration move nothing.
 *
 * After a caller's [Caller.forget] of a claimed call, a stream its producer
 * ended is kept and the provider still pulls it to [Pull.End]; every other
 * stream stops with `Transport.Interrupted`, after the chunks pushed before the
 * forget. [pushClaim] and [endClaim] on the reply stream throw
 * `PushError.Failed(Transport.Interrupted)`, and an [endClaim] of a claim still
 * held settles it all the same. The runtime keeps the call and its slot until
 * the provider is done with every stream parameter, or its handler is gone: it
 * is done once it pulled [Pull.End] or [Pull.Failed] from it or aborted it. The
 * settlement of a query releases every stream parameter, and so does a command's
 * settlement with an error: their unread chunks are dropped, and the caller's
 * next push or end throws `PushError.Failed(Transport.Interrupted)`. A command's
 * acknowledgment does not release them. A forgotten call whose claim was settled
 * and whose parameters are released is no longer known.
 */
public interface StreamHandler : Handler {
    /** Copies the next chunk of stream [s] of [claim] into [out], with the answers and errors of [StreamCaller.pull]. */
    public fun pullClaim(claim: ClaimId, s: StreamNo, out: ByteBuffer): Pull

    /** Offers one chunk to stream [s] of [claim], as [StreamCaller.push] does. */
    public fun pushClaim(claim: ClaimId, s: StreamNo, chunk: ByteBuffer): Int

    /**
     * Ends stream [s] of [claim]. For [REPLY_STREAM] this is the claim's
     * settlement: it settles the claim with no reply bytes, and no
     * [Handler.settle] follows. After the caller refused the reply stream with
     * outcome `e`, it throws `PushError.Failed(e)` and settles the claim with
     * `e`, so the claim does not stay unsettled.
     */
    public fun endClaim(claim: ClaimId, s: StreamNo)

    /** Stops stream [s] of [claim] with outcome [e], from either side of the stream, as [StreamCaller.abort] does. */
    public fun abortClaim(claim: ClaimId, s: StreamNo, e: CallError)
}

/** The outcome of one [StreamCaller.pull] or [StreamHandler.pullClaim]. `ridl_rt::port::Pull`. */
public sealed interface Pull {
    /** One chunk was copied into the buffer; its length. */
    public data class Chunk(public val len: Int) : Pull

    /** The producer ended the stream. Pulling again answers `End` again while the call, or the claim, is still known. */
    public data object End : Pull

    /** No chunk is waiting yet. */
    public data object Pending : Pull

    /** The stream stopped without its end, or was refused: the outcome from the other side or the transport. */
    public data class Failed(public val error: CallError) : Pull
}

/**
 * A push, an end or an abort of a stream that failed.
 * `ridl_rt::port::PushError`.
 *
 * Two layers, as for a pull: [Contract] and [Detached] are the port call, and
 * [TooLarge] and [Ended] are refused on this side before anything is sent.
 * [Failed] is the other side's verdict on the stream.
 */
public sealed class PushError(message: String) : RidlError(message) {
    /**
     * The chunk is longer than the member's limit: the limit a `[limits]` entry
     * gives it, else the default ceiling of its kind
     * ([ridl.rt.contract.defaultCeiling]). Nothing is pushed. [cap] is the
     * limit in bytes.
     */
    public data class TooLarge(public val cap: Int) : PushError("the chunk is larger than $cap bytes")

    /** The stream already ended or was aborted: a push, an end or an abort after its end. */
    public data object Ended : PushError("the stream already ended")

    /**
     * The other side stopped the stream: a `Contract` error when the receiver
     * refused a chunk at its boundary, `Transport.Corrupt` when it could not
     * decode one, and `Transport.Interrupted` when it cancelled the stream or
     * the call was forgotten.
     */
    public data class Failed(public val error: CallError) : PushError("the stream failed: ${error.message}")

    /** A contract error of the port call, such as `UnknownInteraction` for an unknown call or stream number. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : PushError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : PushError("the runtime behind the port is gone")
}

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

/**
 * How far a consumer has scanned one interface. `ridl_rt::port::Watermark`.
 *
 * A mark records the interface and the generation last scanned, with no
 * sequence number. Each [Changed] entry carries its own signal's sequence
 * number.
 */
public data class Watermark(
    /** The interface. */
    public val iface: InterfaceNo,
    /** The generation last scanned. */
    public val generation: ULong,
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

    /**
     * Stream [no] of one call changed: a chunk or its end arrived, its window
     * freed, the provider stopped it with an abort, the call was settled or
     * forgotten, or the provider's handler was dropped. The waker is stored
     * with the call, as an [Outcome] waker is, one for each stream of the call.
     */
    public data class Stream(public val correlation: Correlation, public val no: StreamNo) : Interest

    /**
     * Stream [no] of one claim changed, on the provider's side: a chunk or its
     * end arrived, its window freed, the caller stopped it with an abort, the
     * caller forgot the call, or a settlement released it. The waker is stored
     * with the claim, one for each stream of the claim.
     */
    public data class ClaimStream(public val claim: ClaimId, public val no: StreamNo) : Interest
}

/** A read that failed. `ridl_rt::port::ReadError`. */
public sealed class ReadError(message: String) : RidlError(message) {
    /** The output buffer is too short. Nothing was consumed. */
    public data class Short(public val needed: Int) : ReadError("the read needs $needed bytes")

    /**
     * The output buffer is too short for the next claim's arguments
     * ([Handler.nextClaim] alone). Nothing was consumed: the claim stays the
     * next one, and a later `nextClaim` with at least [needed] bytes presents
     * it under the same [claim]. The id is reported so that a provider can
     * settle the claim without reading its arguments; the generated `serve`
     * settles it `CallError.Transport(Transport.Corrupt)`, because arguments
     * that do not fit the serving interface's `Sizes.maxBufferSize` are not a
     * well-formed encoding of any of its members (ADR-0021 decision 5,
     * amended 2026-09-28). [trace] is the context the call was sent with,
     * under the delivery contract of [Caller], so that a provider that settles
     * the claim unread still has it.
     */
    public data class ShortClaim(public val claim: ClaimId, public val needed: Int, public val trace: TraceContext?) :
        ReadError("claim ${claim.value} needs $needed bytes")

    /** `samples` has fewer entries than `ords`. Nothing was consumed. */
    public data class TooFewSamples(public val needed: Int) : ReadError("the read needs $needed samples")

    /** A contract error, such as an unknown interaction. */
    public data class Contract(public val contract: ridl.rt.error.Contract) : ReadError(contract.message!!)

    /** The runtime behind the port is gone. */
    public data object Detached : ReadError("the runtime behind the port is gone")
}

/** A [SignalWriter] stage call that failed. `ridl_rt::port::WriteError`. */
public sealed class WriteError(message: String) : RidlError(message) {
    /**
     * The value is longer than the signal's limit: the limit a `[limits]`
     * entry gives it, else the default ceiling of a signal
     * ([ridl.rt.contract.defaultCeiling]). This is the one refusal of a
     * value's size on send, and nothing is staged. [cap] is the limit in bytes.
     */
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

    /**
     * The occurrence is longer than the event's limit: the limit a `[limits]`
     * entry gives it, else the default ceiling of an event
     * ([ridl.rt.contract.defaultCeiling]). This is the one refusal of an
     * occurrence's size on send, and nothing is raised. [cap] is the limit in
     * bytes.
     */
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

    /**
     * The arguments are longer than the call's limit: the limit a `[limits]`
     * entry gives it, else the default ceiling of a command or a query
     * ([ridl.rt.contract.defaultCeiling]). This is the one refusal of the
     * arguments' size on send, and nothing is sent. [cap] is the limit in
     * bytes.
     */
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

    /**
     * The reply is longer than the call's limit: the limit a `[limits]` entry
     * gives it, else the default ceiling of a command or a query
     * ([ridl.rt.contract.defaultCeiling]). This is the one refusal of a
     * reply's size on send: no outcome is recorded, and the claim stays
     * settleable. The generated face settles a refused query reply again with
     * `Transport.Corrupt` (driftsys/ridl#1260). [cap] is the limit in bytes.
     */
    public data class TooLarge(public val cap: Int) : SettleError("the reply is larger than $cap bytes")

    /** The runtime behind the port is gone. */
    public data object Detached : SettleError("the runtime behind the port is gone")
}
