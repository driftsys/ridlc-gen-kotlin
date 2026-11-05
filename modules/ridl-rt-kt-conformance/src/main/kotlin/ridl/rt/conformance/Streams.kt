// `StreamCaller` and `StreamHandler`: the streams of a call.
// `crates/ridl-rt-conformance/src/streams.rs`. A runtime may omit the
// extensions, so these tests run only through [streamsSuite].
//
// The contract is the documentation of the two interfaces, of [Pull] and of
// [PushError]: a stream is keyed by its call and a `StreamNo`, where
// `REPLY_STREAM` is the reply stream and `StreamNo(k)` from 1 is the `k`-th
// stream parameter; the unit is the chunk, which a port never splits or merges;
// a push takes the whole chunk or nothing, and answers 0 for a non-empty chunk
// the full window did not take; an empty chunk is always taken and is pulled as
// `Pull.Chunk(0)`; the reply stream ends with the call's settlement; a stop on
// purpose is an abort, and a forget stops every stream of the call that its
// producer has not ended with `Transport.Interrupted`, and keeps the ended ones.
//
// Every test asks for `StreamCaller`, `StreamHandler` and `Wakeable` on the
// runtime, so these tests are in their own suite. The tests send each call
// through the runtime and take its claim through the same runtime, so one value
// holds both sides of every stream.
//
// The suite attaches to a catalog with no descriptor, so a runtime under test
// holds no member table and does not know which streams a member declares. Such
// a runtime must accept `REPLY_STREAM` on a query, and `StreamNo(1)` and
// `StreamNo(2)` on any call; the tests use no other stream number, and a stream
// number beyond those is not tested.
//
// The window is the runtime's own choice. A test that needs a full window pushes
// until a push answers 0, and does at most 1,024 pushes; a runtime whose window
// never fills in that many pushes skips the part of the test that needs a full
// window.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import ridl.rt.contract.Kind
import ridl.rt.contract.StreamNo
import ridl.rt.contract.defaultCeiling
import ridl.rt.error.CallError
import ridl.rt.error.Contract as ContractError
import ridl.rt.error.Transport
import ridl.rt.payload.Rule
import ridl.rt.payload.Violation
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.Pull
import ridl.rt.port.PushError
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.StreamCaller
import ridl.rt.port.StreamHandler
import ridl.rt.port.Wakeable
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KFunction0

/** The first stream parameter. */
private val PARAM_1 = StreamNo(1u)

/** The second stream parameter. */
private val PARAM_2 = StreamNo(2u)

/** The most pushes a test makes to fill a window. */
private const val MAX_PUSHES = 1_024

/** The tests of `StreamCaller` and `StreamHandler`. */
public class StreamsContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : StreamCaller, R : StreamHandler, R : Wakeable {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a reply stream is pulled in order then ends`,
        ::`a pull before the first chunk is pending`,
        ::`a zero length chunk is a chunk`,
        ::`a short buffer reports needed and does not consume`,
        ::`end claim on the reply stream settles the claim`,
        ::`a settle err after chunks fails the stream after them`,
        ::`a push into a full window answers zero and a pull frees it`,
        ::`a stream waker is woken by a chunk an end and a freed window`,
        ::`a parameter streams wakers are woken by a chunk and a freed window`,
        ::`a settlement and a forget wake the stream wakers`,
        ::`forget fails the providers pull and push with interrupted`,
        ::`an abort by the producer fails the receivers pull after the chunks before it`,
        ::`an abort or a push after the end is ended and the end stands`,
        ::`a receivers abort of a parameter stream fails the producers next push`,
        ::`a refusal after the acknowledgment leaves the acknowledgment`,
        ::`a parameter stream pushed before the claim is pulled after it`,
        ::`a parameter stream ends explicitly and a second end is ended`,
        ::`a chunk longer than the limit is too large and not sent`,
        ::`two stream parameters are two streams`,
        ::`a bidirectional call carries both streams`,
        ::`a reclaimed slots stream answers unknown`,
        ::`a forget after a commands acknowledgment keeps an ended stream and interrupts an open one`,
        ::`a callers abort of a parameter stream fails the providers pull after the chunks before it`,
        ::`a callers refusal of the reply stream fails the providers push and end`,
        ::`after the receivers refusal the producers end fails and its abort is ended`,
        ::`a providers abort of an ended parameter stream lets the forget free the slot`,
        ::`a querys settlement and a commands refusal release the parameter streams`,
        ::`a pull by the producer and a push by the receiver are unknown`,
        ::`a claim only offered has no streams until it is taken`,
        ::`a querys settlement drops the unread chunks of its parameter streams`,
        ::`a settlement with an error drops the unread chunks of the parameter streams`,
        ::`a settle ok after the callers refusal leaves the refusal on the reply stream`,
        ::`a receivers abort of an ended stream drops its unread chunks`,
    )

    /** A waker that counts its wakes. Each one stands for a task. */
    private class Count : Waker {
        private val count = AtomicInteger()

        val wakes: Int get() = count.get()

        override fun wake() {
            count.incrementAndGet()
        }
    }

    private fun ok(): Result<ByteBuffer> = Result.success(bytes())

    /**
     * A runtime that serves `IFACE`/`ORD`, with one query sent through it and
     * claimed by it: the call's correlation and its claim.
     */
    private fun claimedQuery(): Triple<R, Correlation, ClaimId> {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.query(IFACE, ORD, bytes(1), null)
        val claim = claimNext(rt)
        return Triple(rt, c, claim)
    }

    /** The next claim [rt] presents. */
    private fun claimNext(rt: Handler): ClaimId =
        checkNotNull(rt.nextClaim(out(8))) { "the call just sent is waiting" }.id

    /**
     * Pushes one byte to the reply stream of [claim] until a push answers 0, at
     * most [MAX_PUSHES] times. Answers the number of chunks taken when a push
     * answered 0, and `null` when every push was taken.
     */
    private fun fillReplyWindow(rt: StreamHandler, claim: ClaimId): Int? {
        for (taken in 0 until MAX_PUSHES) {
            when (val pushed = rt.pushClaim(claim, REPLY_STREAM, bytes(1))) {
                1 -> {}
                0 -> return taken
                else -> fail("a push into an open stream answers 1 or 0, not $pushed")
            }
        }
        return null
    }

    /**
     * Pushes one byte to the first parameter stream of [c] until a push answers
     * 0, at most [MAX_PUSHES] times, as [fillReplyWindow] does for the reply
     * stream.
     */
    private fun fillParameterWindow(rt: StreamCaller, c: Correlation): Int? {
        for (taken in 0 until MAX_PUSHES) {
            when (val pushed = rt.push(c, PARAM_1, bytes(1))) {
                1 -> {}
                0 -> return taken
                else -> fail("a push into an open stream answers 1 or 0, not $pushed")
            }
        }
        return null
    }

    /**
     * The provider pushes three chunks to a query's reply stream and ends it;
     * the caller pulls the three in the order pushed, each with its bytes, and
     * then `End`, and `End` again on the next pull. Each chunk is pulled before
     * the next one is pushed, so a window of one chunk is enough.
     */
    public fun `a reply stream is pulled in order then ends`() {
        val (rt, c, claim) = claimedQuery()
        for (chunk in listOf(array(1), array(2, 2), array(3, 3, 3))) {
            assertEquals(
                chunk.size,
                rt.pushClaim(claim, REPLY_STREAM, ByteBuffer.wrap(chunk)),
                "a push takes the whole chunk",
            )
            val buf = out(8)
            assertEquals(
                Pull.Chunk(chunk.size),
                rt.pull(c, REPLY_STREAM, buf),
                "the chunk is pulled whole, with its own length",
            )
            assertArrayEquals(chunk, buf.written())
        }
        rt.endClaim(claim, REPLY_STREAM)
        assertEquals(Pull.End, rt.pull(c, REPLY_STREAM, out(8)))
        assertEquals(Pull.End, rt.pull(c, REPLY_STREAM, out(8)), "pulling an ended stream again answers End again")
    }

    /** A pull before the provider pushed anything answers `Pending`, before the call is claimed and after. */
    public fun `a pull before the first chunk is pending`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.query(IFACE, ORD, bytes(1), null)
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(8)), "nothing was pushed before the claim")
        claimNext(rt)
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(8)), "nothing was pushed after the claim")
    }

    /**
     * An empty chunk is a chunk: its push answers 0 and it is taken, and the
     * receiver pulls `Chunk(0)`, not `End`. It is taken into a full window too,
     * because the window bounds the non-empty chunks only, and it is pulled
     * after the chunks that filled the window.
     */
    public fun `a zero length chunk is a chunk`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(0, rt.pushClaim(claim, REPLY_STREAM, bytes()))
        assertEquals(
            Pull.Chunk(0),
            rt.pull(c, REPLY_STREAM, out(8)),
            "an empty chunk is pulled as a chunk of length 0, not as the end",
        )
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(8)))

        val taken = fillReplyWindow(rt, claim) ?: MAX_PUSHES
        assertEquals(0, rt.pushClaim(claim, REPLY_STREAM, bytes()), "an empty chunk is taken whatever the window")
        repeat(taken) {
            assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))
        }
        assertEquals(
            Pull.Chunk(0),
            rt.pull(c, REPLY_STREAM, out(8)),
            "the empty chunk pushed into the full window was taken",
        )
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(8)))
    }

    /**
     * A pull into a buffer shorter than the next chunk reports the length it
     * needs and does not consume the chunk: a pull into a buffer long enough
     * reads it.
     */
    public fun `a short buffer reports needed and does not consume`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(3, rt.pushClaim(claim, REPLY_STREAM, bytes(3, 3, 3)))
        assertEquals(ReadError.Short(3), assertThrows<ReadError> { rt.pull(c, REPLY_STREAM, out(2)) })
        val buf = out(8)
        assertEquals(Pull.Chunk(3), rt.pull(c, REPLY_STREAM, buf), "the short pull did not consume the chunk")
        assertArrayEquals(array(3, 3, 3), buf.written())
    }

    /**
     * `endClaim` on the reply stream settles the claim with success and no
     * reply bytes: `reply` reports it as a reply of length 0, the stream reads
     * `End`, and a `settle` after it finds no claim.
     */
    public fun `end claim on the reply stream settles the claim`() {
        val (rt, c, claim) = claimedQuery()
        rt.endClaim(claim, REPLY_STREAM)
        assertEquals(Result.success(0), rt.reply(c, out(8)), "the end of the reply stream is the call's settlement")
        assertEquals(Pull.End, rt.pull(c, REPLY_STREAM, out(8)))
        assertEquals(
            SettleError.UnknownClaim,
            assertThrows<SettleError> { rt.settle(claim, ok()) },
            "the claim was settled by the end, so no settle follows",
        )
    }

    /**
     * A settlement with an error ends the reply stream with that error, after
     * the chunks already pushed. The first chunk is pulled before the second is
     * pushed, so a window of one chunk is enough.
     */
    public fun `a settle err after chunks fails the stream after them`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        val first = out(8)
        assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, first))
        assertArrayEquals(array(1), first.written())
        assertEquals(2, rt.pushClaim(claim, REPLY_STREAM, bytes(2, 2)))
        val error = ContractError.PreconditionFailed
        rt.settle(claim, Result.failure(error))

        val second = out(8)
        assertEquals(Pull.Chunk(2), rt.pull(c, REPLY_STREAM, second))
        assertArrayEquals(array(2, 2), second.written())
        assertEquals(
            Pull.Failed(error),
            rt.pull(c, REPLY_STREAM, out(8)),
            "the settlement's error follows the chunks pushed before it",
        )
    }

    /**
     * A push into a full window answers 0 and takes nothing; one pull frees
     * the window, and the same push is then taken. A runtime whose window does
     * not fill in 1,024 pushes checks only that every push was taken.
     */
    public fun `a push into a full window answers zero and a pull frees it`() {
        val (rt, c, claim) = claimedQuery()
        val taken = fillReplyWindow(rt, claim) ?: return
        assertTrue(taken > 0, "a window holds at least one chunk")
        assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)), "the pull freed room for one chunk")
        repeat(taken) {
            assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)), "the push that answered 0 took nothing")
        }
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(8)))
    }

    /**
     * A `Stream` waker on the reply stream is woken by a chunk, and a fresh one
     * by the end of the stream. A `ClaimStream` waker on the same stream,
     * registered by the producer while the window is full, is woken by the pull
     * that frees it.
     */
    public fun `a stream waker is woken by a chunk an end and a freed window`() {
        val (rt, c, claim) = claimedQuery()

        val chunk = Count()
        rt.wakeOn(Interest.Stream(c, REPLY_STREAM), chunk)
        assertEquals(0, chunk.wakes, "nothing changed since the registration")
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        assertEquals(1, chunk.wakes, "a chunk wakes the receiver's waker")
        assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))

        val taken = fillReplyWindow(rt, claim)
        if (taken != null) {
            val window = Count()
            rt.wakeOn(Interest.ClaimStream(claim, REPLY_STREAM), window)
            assertEquals(0, window.wakes, "nothing changed since the registration")
            assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))
            assertEquals(1, window.wakes, "the pull that frees a full window wakes the producer's waker")
            repeat(taken - 1) {
                assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))
            }
        }

        val end = Count()
        rt.wakeOn(Interest.Stream(c, REPLY_STREAM), end)
        assertEquals(0, end.wakes, "nothing changed since the registration")
        rt.endClaim(claim, REPLY_STREAM)
        assertEquals(1, end.wakes, "the end wakes the receiver's waker")
    }

    /**
     * The wakers of a parameter stream: a `ClaimStream` waker on the provider's
     * side is woken by a chunk the caller pushes, and a `Stream` waker of the
     * caller, registered while the parameter window is full, is woken by the
     * provider's pull that frees it. A runtime whose window does not fill in
     * 1,024 pushes checks only the first.
     */
    public fun `a parameter streams wakers are woken by a chunk and a freed window`() {
        val (rt, c, claim) = claimedQuery()

        val chunk = Count()
        rt.wakeOn(Interest.ClaimStream(claim, PARAM_1), chunk)
        assertEquals(0, chunk.wakes, "nothing changed since the registration")
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        assertEquals(1, chunk.wakes, "a chunk wakes the provider's waker")
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, out(8)))

        val taken = fillParameterWindow(rt, c) ?: return
        val window = Count()
        rt.wakeOn(Interest.Stream(c, PARAM_1), window)
        assertEquals(0, window.wakes, "nothing changed since the registration")
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, out(8)))
        assertEquals(1, window.wakes, "the pull that frees a full window wakes the caller's waker")
        repeat(taken - 1) {
            assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, out(8)))
        }
    }

    /**
     * A `Stream` waker of a call is woken when the call is settled and when the
     * call is forgotten, so no task waits on a stream that can no longer change.
     * A `ClaimStream` waker of the provider is woken when the caller forgets
     * the call. A runtime passes this only if it takes and wakes the stream
     * wakers of a call at each settlement and each forget.
     *
     * The settlement is an error settlement of a query, which ends its reply
     * stream. The forget is of a query that is claimed and not settled, so the
     * call is in flight when it is forgotten.
     */
    public fun `a settlement and a forget wake the stream wakers`() {
        // The settlement path.
        val (rt, settledCall, settledClaim) = claimedQuery()
        val settled = Count()
        rt.wakeOn(Interest.Stream(settledCall, REPLY_STREAM), settled)
        assertEquals(0, settled.wakes, "nothing changed since the registration")
        rt.settle(settledClaim, Result.failure(ContractError.PreconditionFailed))
        assertEquals(1, settled.wakes, "the settlement wakes the caller's stream waker")
        rt.forget(settledCall)

        // The forget path, on a call in flight.
        val c = rt.query(IFACE, ORD, bytes(2), null)
        val claim = claimNext(rt)
        val reply = Count()
        val param = Count()
        val provider = Count()
        rt.wakeOn(Interest.Stream(c, REPLY_STREAM), reply)
        rt.wakeOn(Interest.Stream(c, PARAM_1), param)
        rt.wakeOn(Interest.ClaimStream(claim, PARAM_1), provider)
        // The parameter window is empty, so the producer's key already holds and
        // a runtime may wake that waker at once: each count is measured from
        // what it is before the forget.
        val before = Triple(reply.wakes, param.wakes, provider.wakes)
        rt.forget(c)
        assertTrue(reply.wakes > before.first, "the forget wakes the caller's waker on the reply stream")
        assertTrue(param.wakes > before.second, "the forget wakes the caller's waker on a parameter stream")
        assertTrue(provider.wakes > before.third, "the forget wakes the provider's waker on the claim's stream")
    }

    /**
     * After the caller forgets a claimed call, the provider's pull answers
     * `Failed(Transport.Interrupted)` and its push fails with the same outcome.
     * Its `endClaim` on the reply stream fails the same way and still settles
     * the claim, so a `settle` after it finds no claim.
     */
    public fun `forget fails the providers pull and push with interrupted`() {
        val (rt, c, claim) = claimedQuery()
        rt.forget(c)
        val interrupted = Transport.Interrupted
        assertEquals(Pull.Failed(interrupted), rt.pullClaim(claim, PARAM_1, out(8)))
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, bytes(1)) },
        )
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { rt.endClaim(claim, REPLY_STREAM) },
            "the end of the reply stream after the forget is interrupted",
        )
        assertEquals(
            SettleError.UnknownClaim,
            assertThrows<SettleError> { rt.settle(claim, ok()) },
            "the end settled the claim all the same",
        )
    }

    /**
     * A producer's abort reaches the receiver after the chunks pushed before
     * it: the receiver pulls them, then `Failed` with the abort's outcome.
     */
    public fun `an abort by the producer fails the receivers pull after the chunks before it`() {
        val (rt, c, claim) = claimedQuery()
        val interrupted = Transport.Interrupted
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        rt.abortClaim(claim, REPLY_STREAM, interrupted)
        val buf = out(8)
        assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, buf))
        assertArrayEquals(array(1), buf.written())
        assertEquals(Pull.Failed(interrupted), rt.pull(c, REPLY_STREAM, out(8)))
    }

    /**
     * After the producer ends a stream, an abort and a push answer `Ended`, and
     * the receiver still reads the chunks and then `End`: a late failure does
     * not turn a complete transfer into an interrupted one.
     */
    public fun `an abort or a push after the end is ended and the end stands`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        rt.endClaim(claim, REPLY_STREAM)
        assertEquals(
            PushError.Ended,
            assertThrows<PushError> { rt.abortClaim(claim, REPLY_STREAM, Transport.Interrupted) },
        )
        assertEquals(PushError.Ended, assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, bytes(2, 2)) })
        assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))
        assertEquals(Pull.End, rt.pull(c, REPLY_STREAM, out(8)), "the end stands")
    }

    /**
     * A command acknowledged at its claim, whose parameter stream the caller
     * pushes, and whose provider refuses the first chunk with `abortClaim`:
     * the runtime, the command's correlation and the refusal's outcome.
     */
    private fun refusedParameterStream(): Triple<R, Correlation, CallError> {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.command(IFACE, ORD, bytes(1), null)
        val claim = claimNext(rt)
        rt.settle(claim, ok())
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        val refusal = ContractError.InvalidValue(Violation(null, Rule.Range))
        rt.abortClaim(claim, PARAM_1, refusal)
        return Triple(rt, c, refusal)
    }

    /**
     * The provider's refusal of a parameter stream reaches the producer through
     * its next push, as `PushError.Failed` with the refusal's outcome.
     */
    public fun `a receivers abort of a parameter stream fails the producers next push`() {
        val (rt, c, refusal) = refusedParameterStream()
        assertEquals(PushError.Failed(refusal), assertThrows<PushError> { rt.push(c, PARAM_1, bytes(2, 2)) })
    }

    /** A refusal of a parameter stream after the command's acknowledgment does not change the acknowledgment. */
    public fun `a refusal after the acknowledgment leaves the acknowledgment`() {
        val (rt, c, refusal) = refusedParameterStream()
        assertEquals(PushError.Failed(refusal), assertThrows<PushError> { rt.push(c, PARAM_1, bytes(2, 2)) })
        assertEquals(Result.success(Unit), rt.ack(c), "the acknowledgment stands after the refusal")
    }

    /**
     * A chunk of a parameter stream pushed before the provider takes the claim
     * is kept, and the provider pulls it after the claim.
     */
    public fun `a parameter stream pushed before the claim is pulled after it`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.query(IFACE, ORD, bytes(1), null)
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        val claim = claimNext(rt)
        val buf = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, buf))
        assertArrayEquals(array(1), buf.written())
    }

    /** The caller ends a parameter stream with `end`; a second `end` answers `Ended`, and the provider pulls `End`. */
    public fun `a parameter stream ends explicitly and a second end is ended`() {
        val (rt, c, claim) = claimedQuery()
        rt.end(c, PARAM_1)
        assertEquals(PushError.Ended, assertThrows<PushError> { rt.end(c, PARAM_1) })
        assertEquals(Pull.End, rt.pullClaim(claim, PARAM_1, out(8)))
    }

    /**
     * A chunk longer than the member's limit is refused with the limit, and
     * nothing is pushed, on the reply stream and on a parameter stream. The
     * suite attaches to a catalog with no descriptor, so the limit of the query
     * is the default ceiling of its kind (ADR-0030 decision 16); a chunk of
     * exactly that length is taken.
     */
    public fun `a chunk longer than the limit is too large and not sent`() {
        val cap = defaultCeiling(Kind.Query)
        val (rt, c, claim) = claimedQuery()
        assertEquals(
            PushError.TooLarge(cap),
            assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, out(cap + 1)) },
            "one byte above the limit is refused",
        )
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(cap + 1)), "the refused chunk was not pushed")
        assertEquals(
            cap,
            rt.pushClaim(claim, REPLY_STREAM, ByteBuffer.wrap(ByteArray(cap) { 7 })),
            "a chunk of exactly the limit is taken",
        )
        assertEquals(Pull.Chunk(cap), rt.pull(c, REPLY_STREAM, out(cap + 1)))

        // The limit bounds every chunk in both directions: the caller's chunk to
        // a parameter stream too.
        assertEquals(
            PushError.TooLarge(cap),
            assertThrows<PushError> { rt.push(c, PARAM_1, out(cap + 1)) },
            "one byte above the limit is refused on a parameter stream",
        )
        assertEquals(
            Pull.Pending,
            rt.pullClaim(claim, PARAM_1, out(cap + 1)),
            "the refused parameter chunk was not pushed",
        )
        assertEquals(
            cap,
            rt.push(c, PARAM_1, ByteBuffer.wrap(ByteArray(cap) { 7 })),
            "a parameter chunk of exactly the limit is taken",
        )
        assertEquals(Pull.Chunk(cap), rt.pullClaim(claim, PARAM_1, out(cap + 1)))
    }

    /**
     * Two stream parameters of one call are two streams: each holds its own
     * chunks, and pulling one does not read the other's.
     */
    public fun `two stream parameters are two streams`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        assertEquals(2, rt.push(c, PARAM_2, bytes(2, 2)))
        val second = out(8)
        assertEquals(Pull.Chunk(2), rt.pullClaim(claim, PARAM_2, second))
        assertArrayEquals(array(2, 2), second.written())
        val first = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, first))
        assertArrayEquals(array(1), first.written())
        assertEquals(Pull.Pending, rt.pullClaim(claim, PARAM_1, out(8)))
        assertEquals(Pull.Pending, rt.pullClaim(claim, PARAM_2, out(8)))
    }

    /**
     * A call with a parameter stream and a reply stream carries both at once:
     * the provider pulls the caller's chunk, and the caller pulls the
     * provider's.
     */
    public fun `a bidirectional call carries both streams`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        assertEquals(2, rt.pushClaim(claim, REPLY_STREAM, bytes(2, 2)))
        val param = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, param))
        assertArrayEquals(array(1), param.written())
        val reply = out(8)
        assertEquals(Pull.Chunk(2), rt.pull(c, REPLY_STREAM, reply))
        assertArrayEquals(array(2, 2), reply.written())
        assertEquals(Pull.Pending, rt.pullClaim(claim, PARAM_1, out(8)))
        assertEquals(Pull.Pending, rt.pull(c, REPLY_STREAM, out(8)))
    }

    /**
     * A slot reclaimed and taken by a new call does not answer to the
     * correlation it had before: a pull or a push on the old correlation is an
     * unknown interaction, and does not read or write the new call's streams.
     *
     * The old call is a query the test claims itself, so it holds the claim:
     * after the forget the provider pulls the old call's parameter stream to its
     * end, the chunk and then `Failed(Interrupted)`, because the slot of a
     * forgotten call is not reclaimed while the provider has a stream to read,
     * and then settles the claim. Every other slot holds a settled command, so
     * the new call can only take the slot that was reclaimed.
     */
    public fun `a reclaimed slots stream answers unknown`() {
        val (rt, old, oldClaim) = claimedQuery()
        repeat(factory.slots - 1) {
            rt.command(IFACE, ORD, bytes(1), null)
            rt.settle(claimNext(rt), ok())
        }
        // A chunk on the old call's parameter stream, which the call that takes
        // the slot must not inherit.
        assertEquals(1, rt.push(old, PARAM_1, bytes(9)))
        rt.forget(old)
        assertEquals(
            Pull.Chunk(1),
            rt.pullClaim(oldClaim, PARAM_1, out(8)),
            "the chunk pushed before the forget is still read",
        )
        assertEquals(
            Pull.Failed(Transport.Interrupted),
            rt.pullClaim(oldClaim, PARAM_1, out(8)),
            "the stream was not ended, so the forget interrupted it",
        )
        rt.settle(oldClaim, ok())
        val new = rt.query(IFACE, ORD, bytes(2), null)
        assertNotEquals(old, new, "the slot is taken under a new correlation")
        val claim = claimNext(rt)
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))

        val unknown = ContractError.UnknownInteraction
        assertEquals(
            ReadError.Contract(unknown),
            assertThrows<ReadError> { rt.pull(old, REPLY_STREAM, out(8)) },
            "the old correlation does not read the new call's stream",
        )
        assertEquals(
            PushError.Contract(unknown),
            assertThrows<PushError> { rt.push(old, PARAM_1, bytes(3)) },
            "the old correlation does not push to the new call's stream",
        )
        val buf = out(8)
        assertEquals(Pull.Chunk(1), rt.pull(new, REPLY_STREAM, buf))
        assertArrayEquals(array(1), buf.written())
        assertEquals(
            Pull.Pending,
            rt.pullClaim(claim, PARAM_1, out(8)),
            "the new call holds neither the old call's chunk nor the push on the old correlation",
        )
    }

    /**
     * A forget after a command's acknowledgment keeps a stream its producer
     * ended, which the provider still reads to its end, and interrupts a stream
     * that was not ended, which the provider reads to `Failed(Interrupted)`
     * after the chunk pushed before the forget. A command has no reply stream,
     * so the provider's push to the reply stream after a forget is checked on a
     * query, by `forget fails the providers pull and push with interrupted`.
     */
    public fun `a forget after a commands acknowledgment keeps an ended stream and interrupts an open one`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.command(IFACE, ORD, bytes(1), null)
        val claim = claimNext(rt)
        rt.settle(claim, ok())
        assertEquals(1, rt.push(c, PARAM_1, bytes(7)))
        rt.end(c, PARAM_1)
        assertEquals(1, rt.push(c, PARAM_2, bytes(8)))
        rt.forget(c)

        val interrupted = Transport.Interrupted
        val first = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, first))
        assertArrayEquals(array(7), first.written())
        assertEquals(Pull.End, rt.pullClaim(claim, PARAM_1, out(8)), "a stream its producer ended is kept to its end")
        val second = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_2, second))
        assertArrayEquals(array(8), second.written())
        assertEquals(
            Pull.Failed(interrupted),
            rt.pullClaim(claim, PARAM_2, out(8)),
            "a stream that was not ended is interrupted after its chunks",
        )
    }

    /**
     * The caller's abort of a parameter stream it produces reaches the provider
     * after the chunk pushed before it: the provider pulls the chunk, then
     * `Failed` with the abort's outcome. After its own abort, the caller's push
     * and end answer `Ended`.
     */
    public fun `a callers abort of a parameter stream fails the providers pull after the chunks before it`() {
        val (rt, c, claim) = claimedQuery()
        val interrupted = Transport.Interrupted
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        rt.abort(c, PARAM_1, interrupted)
        val buf = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, buf))
        assertArrayEquals(array(1), buf.written())
        assertEquals(Pull.Failed(interrupted), rt.pullClaim(claim, PARAM_1, out(8)))
        assertEquals(
            PushError.Ended,
            assertThrows<PushError> { rt.push(c, PARAM_1, bytes(2)) },
            "a push after the producer's own abort is Ended",
        )
        assertEquals(
            PushError.Ended,
            assertThrows<PushError> { rt.end(c, PARAM_1) },
            "an end after the producer's own abort is Ended",
        )
    }

    /**
     * The caller's refusal of a query's reply stream: the caller reads none of
     * what it refused, the provider's next push and its `endClaim` answer
     * `Failed` with the refusal, `endClaim` settles the claim with the
     * refusal, and a second abort by the caller answers `Ended`.
     */
    public fun `a callers refusal of the reply stream fails the providers push and end`() {
        val (rt, c, claim) = claimedQuery()
        val refusal = ContractError.PreconditionFailed
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        rt.abort(c, REPLY_STREAM, refusal)
        assertEquals(
            Pull.Failed(refusal),
            rt.pull(c, REPLY_STREAM, out(8)),
            "the refusing side reads none of what it refused",
        )
        assertEquals(
            PushError.Failed(refusal),
            assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, bytes(2)) },
        )
        assertEquals(PushError.Failed(refusal), assertThrows<PushError> { rt.endClaim(claim, REPLY_STREAM) })
        assertEquals(
            Result.failure<Int>(refusal),
            rt.reply(c, out(8)),
            "the end after the refusal settles the claim with the refusal",
        )
        assertEquals(
            PushError.Ended,
            assertThrows<PushError> { rt.abort(c, REPLY_STREAM, refusal) },
            "an abort of a stream already stopped is Ended",
        )
    }

    /**
     * After the provider refused a parameter stream, the caller's end answers
     * `Failed` with the refusal, and its abort answers `Ended`.
     */
    public fun `after the receivers refusal the producers end fails and its abort is ended`() {
        val (rt, c, refusal) = refusedParameterStream()
        assertEquals(PushError.Failed(refusal), assertThrows<PushError> { rt.end(c, PARAM_1) })
        assertEquals(PushError.Ended, assertThrows<PushError> { rt.abort(c, PARAM_1, Transport.Interrupted) })
    }

    /**
     * The provider's abort of a stream parameter its producer already ended
     * answers `Ended`, and the provider is done with the stream all the same:
     * after the forget of the acknowledged command, its slot comes back. The
     * abort comes before the forget in one round and after it in the next, so
     * a call whose slot stayed taken makes a later send answer `Busy`.
     */
    public fun `a providers abort of an ended parameter stream lets the forget free the slot`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val refusal = ContractError.PreconditionFailed
        for (round in 0 until factory.slots * 2) {
            val c = try {
                rt.command(IFACE, ORD, bytes(1), null)
            } catch (e: SendError) {
                fail("round $round: the send answered $e")
            }
            val claim = claimNext(rt)
            rt.settle(claim, ok())
            assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
            rt.end(c, PARAM_1)
            if (round % 2 == 0) {
                assertEquals(
                    PushError.Ended,
                    assertThrows<PushError> { rt.abortClaim(claim, PARAM_1, refusal) },
                    "an abort of an ended stream is Ended",
                )
                rt.forget(c)
            } else {
                rt.forget(c)
                assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, out(8)))
                assertEquals(
                    PushError.Ended,
                    assertThrows<PushError> { rt.abortClaim(claim, PARAM_1, refusal) },
                    "an abort of an ended stream is Ended",
                )
            }
        }
    }

    /**
     * The settlement of a query releases its stream parameters, with success
     * and with an error, and so does a command's settlement with an error,
     * which refuses the whole call: the caller's next push and end on a stream
     * parameter answer `Failed(Interrupted)`, and the call's outcome carries the
     * settlement.
     */
    public fun `a querys settlement and a commands refusal release the parameter streams`() {
        val interrupted = PushError.Failed(Transport.Interrupted)
        val error = ContractError.PreconditionFailed

        for (outcome in listOf(ok(), Result.failure<ByteBuffer>(error))) {
            val (rt, c, claim) = claimedQuery()
            assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
            rt.settle(claim, outcome)
            assertEquals(interrupted, assertThrows<PushError> { rt.push(c, PARAM_1, bytes(2)) }, "$outcome")
            assertEquals(interrupted, assertThrows<PushError> { rt.end(c, PARAM_1) }, "$outcome")
            assertEquals(outcome.map { it.remaining() }, rt.reply(c, out(8)), "the reply carries the settlement")
        }

        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.command(IFACE, ORD, bytes(1), null)
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        val claim = claimNext(rt)
        rt.settle(claim, Result.failure(error))
        assertEquals(interrupted, assertThrows<PushError> { rt.push(c, PARAM_1, bytes(2)) })
        assertEquals(interrupted, assertThrows<PushError> { rt.end(c, PARAM_1) })
        assertEquals(Result.failure<Unit>(error), rt.ack(c), "the ack carries the refusal")
    }

    /**
     * Each stream has one producer and one receiver: a pull by the producer,
     * and a push or an end by the receiver, are unknown interactions, on the
     * reply stream and on a parameter stream alike.
     */
    public fun `a pull by the producer and a push by the receiver are unknown`() {
        val (rt, c, claim) = claimedQuery()
        val unknown = ContractError.UnknownInteraction
        assertEquals(
            ReadError.Contract(unknown),
            assertThrows<ReadError> { rt.pull(c, PARAM_1, out(8)) },
            "the caller produces a parameter stream",
        )
        assertEquals(
            ReadError.Contract(unknown),
            assertThrows<ReadError> { rt.pullClaim(claim, REPLY_STREAM, out(8)) },
            "the provider produces the reply stream",
        )
        assertEquals(PushError.Contract(unknown), assertThrows<PushError> { rt.push(c, REPLY_STREAM, bytes(1)) })
        assertEquals(PushError.Contract(unknown), assertThrows<PushError> { rt.end(c, REPLY_STREAM) })
        assertEquals(PushError.Contract(unknown), assertThrows<PushError> { rt.pushClaim(claim, PARAM_1, bytes(1)) })
        assertEquals(PushError.Contract(unknown), assertThrows<PushError> { rt.endClaim(claim, PARAM_1) })
    }

    /**
     * A claim offered through `ReadError.ShortClaim` and not yet taken has no
     * streams: a pull, a push and an end on it are unknown interactions, and a
     * `ClaimStream` registration on it is woken at once. Once the provider takes
     * the claim with a large enough buffer, under the same id, it reads the
     * parameter stream from its first chunk: the refused operations consumed
     * nothing.
     */
    public fun `a claim only offered has no streams until it is taken`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.query(IFACE, ORD, bytes(1, 2, 3), null)
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        val claim = assertThrows<ReadError.ShortClaim>("a buffer shorter than the arguments reports ShortClaim") {
            rt.nextClaim(out(1))
        }.claim
        val unknown = ContractError.UnknownInteraction
        assertEquals(
            ReadError.Contract(unknown),
            assertThrows<ReadError> { rt.pullClaim(claim, PARAM_1, out(8)) },
            "an offered claim has no stream to pull",
        )
        assertEquals(
            PushError.Contract(unknown),
            assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, bytes(7)) },
            "an offered claim has no stream to push to",
        )
        assertEquals(
            PushError.Contract(unknown),
            assertThrows<PushError> { rt.endClaim(claim, REPLY_STREAM) },
            "an offered claim has no stream to end",
        )
        val count = Count()
        rt.wakeOn(Interest.ClaimStream(claim, PARAM_1), count)
        assertTrue(count.wakes > 0, "a registration on a stream of an offered claim is woken at once")
        val taken = claimNext(rt)
        assertEquals(claim, taken, "the call is presented under the same id")
        val buf = out(8)
        assertEquals(
            Pull.Chunk(1),
            rt.pullClaim(taken, PARAM_1, buf),
            "the provider reads the stream from its first chunk once it took the claim",
        )
        assertArrayEquals(array(1), buf.written())
    }

    /**
     * The settlement of a query drops the unread chunks of its stream
     * parameters: the provider's pull after the settlement answers `End` for a
     * stream the caller ended, and `Failed(Interrupted)` for one it did not,
     * never the chunk.
     */
    public fun `a querys settlement drops the unread chunks of its parameter streams`() {
        val interrupted = Transport.Interrupted
        for (ended in listOf(false, true)) {
            val (rt, c, claim) = claimedQuery()
            assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
            if (ended) {
                rt.end(c, PARAM_1)
            }
            rt.settle(claim, ok())
            val expected = if (ended) Pull.End else Pull.Failed(interrupted)
            assertEquals(
                expected,
                rt.pullClaim(claim, PARAM_1, out(8)),
                "ended $ended: the unread chunk was dropped at the settlement",
            )
        }
    }

    /**
     * A settlement with an error drops the unread chunks of the stream
     * parameters, for a query and for a command, which the error refuses as a
     * whole: the provider's pull after the settlement answers `End` for a stream
     * the caller ended, and `Failed(Interrupted)` for one it did not, never the
     * chunk.
     */
    public fun `a settlement with an error drops the unread chunks of the parameter streams`() {
        val interrupted = Transport.Interrupted
        val error = ContractError.PreconditionFailed
        for (command in listOf(false, true)) {
            for (ended in listOf(false, true)) {
                val rt = runtime()
                rt.serve(IFACE, listOf(ORD))
                val c = if (command) {
                    rt.command(IFACE, ORD, bytes(1), null)
                } else {
                    rt.query(IFACE, ORD, bytes(1), null)
                }
                val claim = claimNext(rt)
                assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
                if (ended) {
                    rt.end(c, PARAM_1)
                }
                rt.settle(claim, Result.failure(error))
                val expected = if (ended) Pull.End else Pull.Failed(interrupted)
                assertEquals(
                    expected,
                    rt.pullClaim(claim, PARAM_1, out(8)),
                    "command $command, ended $ended: the unread chunk was dropped at the settlement",
                )
            }
        }
    }

    /**
     * A `settle` with no bytes after the caller refused the reply stream
     * records success and leaves the refusal on the stream: the caller's pull
     * of the reply stream answers `Failed` with its own refusal, not `End`.
     */
    public fun `a settle ok after the callers refusal leaves the refusal on the reply stream`() {
        val (rt, c, claim) = claimedQuery()
        val refusal = ContractError.PreconditionFailed
        rt.abort(c, REPLY_STREAM, refusal)
        rt.settle(claim, ok())
        assertEquals(
            Pull.Failed(refusal),
            rt.pull(c, REPLY_STREAM, out(8)),
            "the settlement does not turn the refused stream into an ended one",
        )
        assertEquals(Result.success(0), rt.reply(c, out(8)), "settle records Ok, unlike endClaim")
    }

    /**
     * A receiver's abort of a stream that already ended answers `Ended` and
     * drops the receiver's unread chunks: its later pulls answer `End`. The
     * provider aborts a stream parameter the caller ended, and the caller
     * aborts the reply stream the provider ended.
     */
    public fun `a receivers abort of an ended stream drops its unread chunks`() {
        val (rt, c, claim) = claimedQuery()
        val refusal = ContractError.PreconditionFailed
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        rt.end(c, PARAM_1)
        assertEquals(PushError.Ended, assertThrows<PushError> { rt.abortClaim(claim, PARAM_1, refusal) })
        assertEquals(
            Pull.End,
            rt.pullClaim(claim, PARAM_1, out(8)),
            "the provider's abort dropped the unread chunk of the ended stream",
        )
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        rt.endClaim(claim, REPLY_STREAM)
        assertEquals(PushError.Ended, assertThrows<PushError> { rt.abort(c, REPLY_STREAM, refusal) })
        assertEquals(
            Pull.End,
            rt.pull(c, REPLY_STREAM, out(8)),
            "the caller's abort dropped the unread chunk of the ended stream",
        )
    }
}
