// The caller's forget as the provider reads it: `StreamHandler.claimForget`
// and `wakeOnClaimForget` (driftsys/ridl#1298). The `claim_forget` arm of
// `suite!` in `crates/ridl-rt-conformance/src/lib.rs`, whose cases Rust keeps
// in `streams.rs` as the `claim_forget_*` functions: here they are one
// contract, in the order of that arm, because each Kotlin contract is one
// arm's.
//
// The arm is opt-in. Its cases check a runtime that overrides both methods,
// whose defaults answer `ClaimForget.NotForgotten` and never wake; a runtime
// that keeps the defaults does not run [claimForgetSuite], and its streams
// suite is unchanged. The query consumes no chunk and pushes nothing, and the
// registration stays valid whatever the state of a parameter stream. The arm
// has the bounds of the streams suite and asks the factory for nothing more.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail
import ridl.rt.contract.StreamNo
import ridl.rt.error.Transport
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.ClaimForget
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.Pull
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.ReadError
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

/** The tests of `StreamHandler.claimForget` and `StreamHandler.wakeOnClaimForget`. */
public class ClaimForgetContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : StreamCaller, R : StreamHandler, R : Wakeable {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`claim forget reports a forgotten reply stream`,
        ::`claim forget preserves chunks on a parameter only call`,
        ::`claim forget reports an acknowledged command`,
        ::`claim forget waits after a parameter stream ended`,
        ::`claim forget distinguishes ended and stopped parameters`,
        ::`claim forget reports a claim only offered`,
        ::`claim forget registration refreshes displaces and wakes unknown`,
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

    /** A runtime that serves `IFACE`/`ORD`, with one command with no arguments sent through it and claimed by it. */
    private fun claimedCommand(): Triple<R, Correlation, ClaimId> {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.command(IFACE, ORD, bytes(), null)
        val claim = claimNext(rt)
        return Triple(rt, c, claim)
    }

    /** The next claim [rt] presents. */
    private fun claimNext(rt: Handler): ClaimId =
        checkNotNull(rt.nextClaim(out(8))) { "the call just sent is waiting" }.id

    /**
     * The claim-forget capability reports a forgotten reply stream without
     * pushing a probe chunk or consuming a parameter chunk.
     * `claim_forget_reports_a_forgotten_reply_stream`.
     */
    public fun `claim forget reports a forgotten reply stream`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(ClaimForget.NotForgotten, rt.claimForget(claim))
        val count = Count()
        rt.wakeOnClaimForget(claim, count)
        val before = count.wakes
        rt.forget(c)
        assertTrue(count.wakes > before, "forget wakes the provider without a push")
        assertEquals(ClaimForget.Forgotten, rt.claimForget(claim))
    }

    /**
     * A call with only a stream parameter exposes the forget through its
     * claim. Reading the forget consumes no queued chunk and pushes no reply
     * chunk. `claim_forget_preserves_chunks_on_a_parameter_only_call`.
     */
    public fun `claim forget preserves chunks on a parameter only call`() {
        val (rt, c, claim) = claimedQuery()
        assertEquals(1, rt.push(c, PARAM_1, bytes(7)))
        rt.forget(c)
        assertEquals(ClaimForget.Forgotten, rt.claimForget(claim))
        val buf = out(8)
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, PARAM_1, buf))
        assertArrayEquals(array(7), buf.written(), "query did not consume the chunk")

        val (other, otherC, otherClaim) = claimedQuery()
        assertEquals(ClaimForget.NotForgotten, other.claimForget(otherClaim))
        assertEquals(Pull.Pending, other.pull(otherC, REPLY_STREAM, out(8)), "query pushes nothing")
    }

    /**
     * An acknowledged command keeps its claim-forget signal while its open
     * parameter stream stays available to the provider.
     * `claim_forget_reports_an_acknowledged_command`.
     */
    public fun `claim forget reports an acknowledged command`() {
        val (rt, c, claim) = claimedCommand()
        assertEquals(1, rt.push(c, PARAM_1, bytes(1)))
        rt.settle(claim, ok())
        assertEquals(Result.success(Unit), rt.ack(c))
        val count = Count()
        rt.wakeOnClaimForget(claim, count)
        val before = count.wakes
        rt.forget(c)
        assertTrue(count.wakes > before)
        assertEquals(ClaimForget.Forgotten, rt.claimForget(claim))
    }

    /**
     * After a parameter ends, a forget wakes the claim-forget registration
     * and reports the normal producer end, even after the provider already
     * read it. `claim_forget_waits_after_a_parameter_stream_ended`.
     */
    public fun `claim forget waits after a parameter stream ended`() {
        val (rt, c, claim) = claimedQuery()
        rt.end(c, PARAM_1)
        assertEquals(Pull.End, rt.pullClaim(claim, PARAM_1, out(0)))
        val count = Count()
        rt.wakeOnClaimForget(claim, count)
        assertEquals(ClaimForget.NotForgotten, rt.claimForget(claim))
        val before = count.wakes
        rt.forget(c)
        assertTrue(count.wakes > before)
        assertEquals(ClaimForget.AfterEnd, rt.claimForget(claim))
    }

    /**
     * A normally forgotten acknowledged command keeps its ended parameter for
     * the provider; every named parameter must have ended without a stop.
     * `claim_forget_distinguishes_ended_and_stopped_parameters`.
     */
    public fun `claim forget distinguishes ended and stopped parameters`() {
        for (stopped in listOf(false, true)) {
            val (rt, c, claim) = claimedCommand()
            rt.end(c, PARAM_1)
            if (stopped) {
                rt.abort(c, PARAM_2, Transport.Interrupted)
            } else {
                rt.end(c, PARAM_2)
            }
            rt.settle(claim, ok())
            rt.forget(c)
            assertEquals(
                if (stopped) ClaimForget.Forgotten else ClaimForget.AfterEnd,
                rt.claimForget(claim),
                "stopped $stopped",
            )
        }
    }

    /**
     * A claim offered through a short read can be queried before it is taken.
     * A runtime that presents the call without a short-read offer skips this
     * case. `claim_forget_reports_a_claim_only_offered`.
     */
    public fun `claim forget reports a claim only offered`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val c = rt.query(IFACE, ORD, bytes(1), null)
        val claim = try {
            rt.nextClaim(out(0)) ?: fail("expected an offer or claim, got none")
            return
        } catch (offer: ReadError.ShortClaim) {
            offer.claim
        }
        assertEquals(ClaimForget.NotForgotten, rt.claimForget(claim))
        val count = Count()
        rt.wakeOnClaimForget(claim, count)
        val before = count.wakes
        rt.forget(c)
        assertTrue(count.wakes > before)
        assertEquals(ClaimForget.Forgotten, rt.claimForget(claim))
    }

    /**
     * A registration may be refreshed; a displaced one wakes the old task. A
     * claim already forgotten, and one no longer known, wakes a registration
     * at once. `claim_forget_registration_refreshes_displaces_and_wakes_unknown`.
     */
    public fun `claim forget registration refreshes displaces and wakes unknown`() {
        val (rt, c, claim) = claimedQuery()
        val first = Count()
        val second = Count()
        rt.wakeOnClaimForget(claim, first)
        rt.wakeOnClaimForget(claim, first)
        var before = first.wakes
        rt.wakeOnClaimForget(claim, second)
        assertTrue(first.wakes > before, "displaced registration wakes")
        before = second.wakes
        rt.forget(c)
        assertTrue(second.wakes > before, "forget wakes the registration")
        before = second.wakes
        rt.wakeOnClaimForget(claim, second)
        assertTrue(second.wakes > before, "already forgotten wakes immediately")
        rt.settle(claim, Result.failure(Transport.Interrupted))
        assertEquals(ClaimForget.Forgotten, rt.claimForget(claim))
        before = second.wakes
        rt.wakeOnClaimForget(claim, second)
        assertTrue(second.wakes > before, "reclaimed unknown claim wakes immediately")
    }
}
