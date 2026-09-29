// Calls: delivery, settlement, the caller's sequence number, `forget`, and
// which handler a claim belongs to (`Caller` and `Handler`).
// `crates/ridl-rt-conformance/src/calls.rs`.
//
// Every handler here calls `serve` for the members it takes claims on before
// it takes one, because `Handler.serve` is what starts presentation. The suite
// has no test of a handler that has served nothing.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.InterfaceNo
import ridl.rt.error.Contract as ContractError
import ridl.rt.error.Transport
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import java.nio.ByteBuffer
import kotlin.reflect.KFunction0

/** The tests of `Caller` and `Handler`. */
public class CallsContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`a command is delivered and acknowledged`,
        ::`a query is delivered and replied`,
        ::`settle can be made to fail once then succeed`,
        ::`two callers on one provider are two claims under one seq`,
        ::`a caller sequence number counts that caller calls`,
        ::`a settled outcome reports the contract error the provider settled`,
        ::`a claim is presented once and settled once`,
        ::`an oversized claim is reported with its id and is not consumed`,
        ::`an unread claim is settled by its id`,
        ::`the calls behind an oversized claim are presented once it is settled`,
        ::`forget releases a settled correlation`,
        ::`forget before the claim is presented withdraws or leaves the call`,
        ::`a send with every slot taken is busy for every caller`,
        ::`a reclaimed slots old correlation answers none`,
        ::`forget between the claim and the settlement leaves the settlement valid`,
        ::`forget between the offer and the settlement leaves the settlement valid`,
        ::`a claim that was never presented cannot be settled`,
        ::`an injected settle failure is not spent on an unknown claim`,
        ::`a handler cannot settle another handlers claim`,
        ::`two handlers each receive only what they served`,
    )

    private fun ok(vararg values: Int): Result<ByteBuffer> = Result.success(bytes(*values))

    private fun Handler.claim(buf: ByteBuffer = out(8)): Claim = checkNotNull(nextClaim(buf)) { "a claim is waiting" }

    /** A settlement that fails with anything but `UnknownClaim`: the injected fault. */
    private fun assertInjectedFailure(settle: () -> Unit, message: String) {
        val error = assertThrows<SettleError>(message) { settle() }
        assertNotEquals(SettleError.UnknownClaim, error, message)
    }

    /** A command reaches the handler with its arguments, and the settlement is observable through `ack`. */
    public fun `a command is delivered and acknowledged`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1, 2, 3))
        assertNull(rt.ack(correlation), "not yet settled")

        val buf = out(8)
        val claim = rt.claim(buf)
        assertEquals(IFACE, claim.iface)
        assertEquals(ORD, claim.ord)
        assertArrayEquals(array(1, 2, 3), buf.written())

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation), "settlement is observable through ack")
    }

    /**
     * A query reaches the handler, and the reply bytes it settles are read
     * back through `reply`. `ack` answers `null` for a query's correlation.
     */
    public fun `a query is delivered and replied`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.query(IFACE, ORD, bytes(9))

        val buf = out(8)
        val claim = rt.claim(buf)
        assertArrayEquals(array(9), buf.written())

        rt.settle(claim.id, ok(7, 7))

        val reply = out(8)
        assertEquals(Result.success(2), rt.reply(correlation, reply))
        assertArrayEquals(array(7, 7), reply.written())
        // A query's correlation always answers `null` from `ack`.
        assertNull(rt.ack(correlation))
    }

    /**
     * A settlement that fails records no outcome, and the claim can still be
     * settled. [Factory.failNextSettle] is the injected failure that makes the
     * generated `dispatch`'s count of accepted settlements reachable.
     */
    public fun `settle can be made to fail once then succeed`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        val claim = rt.claim()

        factory.failNextSettle(rt)
        assertInjectedFailure({ rt.settle(claim.id, ok()) }, "the injected failure surfaces from settle as an error other than `UnknownClaim`")
        assertNull(rt.ack(correlation), "a failed settle records no outcome")

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * driftsys/ridl#308, and the rule ADR-0021 decision 5 fixes over it: a
     * caller's sequence number is unique per caller, not per channel, so two
     * callers on their first call both carry seq 1 — and they are still two
     * claims, never merged.
     */
    public fun `two callers on one provider are two claims under one seq`() {
        val rt = runtime()
        val second = factory.caller(rt)
        rt.serve(IFACE, listOf(ORD))

        val a = rt.command(IFACE, ORD, bytes(1))
        val b = second.command(IFACE, ORD, bytes(2))
        assertNotEquals(a, b, "the correlations are distinct")

        var buf = out(8)
        val firstClaim = rt.claim(buf)
        assertArrayEquals(array(1), buf.written())
        buf = out(8)
        val secondClaim = rt.claim(buf)
        assertArrayEquals(array(2), buf.written())
        assertEquals(1uL, firstClaim.envelope.seq)
        assertEquals(1uL, secondClaim.envelope.seq, "both callers are on their first call, so both carry seq 1")
        assertNotEquals(firstClaim.id, secondClaim.id, "and they are two claims, not one")

        rt.settle(firstClaim.id, ok())
        rt.settle(secondClaim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(a))
        assertEquals(Result.success(Unit), second.ack(b))
    }

    /** A caller's sequence number counts that caller's calls, commands and queries on one counter. */
    public fun `a caller sequence number counts that caller calls`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1))
        rt.query(IFACE, ORD, bytes(2))

        assertEquals(1uL, rt.claim().envelope.seq)
        assertEquals(2uL, rt.claim().envelope.seq, "a command and a query share one counter")
    }

    /** A contract error the provider settles is the outcome the caller sees. */
    public fun `a settled outcome reports the contract error the provider settled`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        rt.settle(rt.claim().id, Result.failure(ContractError.PreconditionFailed))
        assertEquals(Result.failure<Unit>(ContractError.PreconditionFailed), rt.ack(correlation))
    }

    /** `nextClaim` presents a call once, and a claim already settled is unknown to a second settlement. */
    public fun `a claim is presented once and settled once`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1))
        val claim = rt.claim()
        assertNull(rt.nextClaim(out(8)), "the claim is presented once")

        rt.settle(claim.id, ok())
        assertThrows<SettleError.UnknownClaim>("a claim already settled is unknown to a second settlement") {
            rt.settle(claim.id, ok())
        }
    }

    /**
     * A buffer shorter than the next call's arguments throws
     * `ReadError.ShortClaim` with that call's id and the bytes it needs, and
     * does not consume the call: a later `nextClaim` with at least `needed`
     * bytes presents the same call under the same id, and its settlement
     * reaches the caller (driftsys/ridl#569).
     */
    public fun `an oversized claim is reported with its id and is not consumed`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1, 2, 3))

        val unread = assertThrows<ReadError.ShortClaim> { rt.nextClaim(out(1)) }
        assertEquals(3, unread.needed, "the bytes the arguments need")
        assertEquals(
            unread,
            assertThrows<ReadError.ShortClaim> { rt.nextClaim(out(1)) },
            "the call is not consumed, and is presented again under the same id",
        )

        val buf = out(8)
        val claim = rt.claim(buf)
        assertEquals(unread.claim, claim.id, "the read presents the same claim")
        assertArrayEquals(array(1, 2, 3), buf.written())

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * A claim presented through `ShortClaim` is settled by its id with its
     * arguments never read; the caller sees the outcome, the call leaves the
     * waiting calls, and a second settlement is unknown (driftsys/ridl#569).
     */
    public fun `an unread claim is settled by its id`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1, 2, 3))

        val claim = assertThrows<ReadError.ShortClaim> { rt.nextClaim(out(1)) }.claim
        rt.settle(claim, Result.failure(Transport.Corrupt))
        assertEquals(Result.failure<Unit>(Transport.Corrupt), rt.ack(correlation), "the caller sees the outcome")

        assertNull(rt.nextClaim(out(8)), "the settled call is no longer waiting")
        assertThrows<SettleError.UnknownClaim>("a claim already settled is unknown to a second settlement") {
            rt.settle(claim, ok())
        }
    }

    /**
     * An oversized call holds back the calls sent after it until it is
     * settled: each `nextClaim` with the short buffer reports the same claim,
     * and the settlement of that claim by its id lets the next call be
     * presented, under an id of its own (driftsys/ridl#569).
     */
    public fun `the calls behind an oversized claim are presented once it is settled`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val oversized = rt.command(IFACE, ORD, bytes(1, 2, 3))
        val behind = rt.command(IFACE, ORD, bytes(4))

        val first = assertThrows<ReadError.ShortClaim> { rt.nextClaim(out(2)) }.claim
        assertEquals(
            ReadError.ShortClaim(first, 3),
            assertThrows<ReadError.ShortClaim> { rt.nextClaim(out(2)) },
            "the oversized call stays the next one until it is settled",
        )

        rt.settle(first, Result.failure(Transport.Corrupt))
        val buf = out(2)
        val second = rt.claim(buf)
        assertNotEquals(first, second.id, "a claim id names one call")
        assertArrayEquals(array(4), buf.written())

        rt.settle(second.id, ok())
        assertEquals(Result.failure<Unit>(Transport.Corrupt), rt.ack(oversized))
        assertEquals(Result.success(Unit), rt.ack(behind))
    }

    /** After `forget`, a settled outcome is no longer retrievable. */
    public fun `forget releases a settled correlation`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        rt.settle(rt.claim().id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))

        rt.forget(correlation)
        assertNull(rt.ack(correlation), "the outcome is no longer retrievable")
    }

    /**
     * `Caller.forget` releases the caller's interest in an outcome. What
     * happens to a call no provider has claimed yet is the runtime's: one may
     * withdraw it, and one whose transport has already sent the request cannot
     * recall it, so the call is still presented and settled. The test accepts
     * either result. Either way the caller is not told the outcome, and the
     * forgotten call gives its slot back: the runtime then accepts exactly
     * [Factory.slots] sends before `SendError.Busy`.
     */
    public fun `forget before the claim is presented withdraws or leaves the call`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        rt.forget(correlation)

        val buf = out(8)
        val claim = rt.nextClaim(buf)
        val result = if (claim != null) {
            assertArrayEquals(array(1), buf.written())
            rt.settle(claim.id, ok())
            "held until settled"
        } else {
            "withdrawn"
        }
        assertNull(rt.ack(correlation), "the caller asked not to be told")
        assertEquals(factory.slots, sendsUntilBusy(rt), "the forgotten call, $result, gave its slot back")
    }

    /** The number of commands [caller] accepts before it throws `SendError.Busy`, counting at most [Factory.slots] + 1. */
    private fun sendsUntilBusy(caller: Caller): Int {
        for (sent in 0..factory.slots) {
            try {
                caller.command(IFACE, ORD, bytes(2))
            } catch (_: SendError.Busy) {
                return sent
            }
        }
        return factory.slots + 1
    }

    /**
     * The call table is the runtime's, shared by every caller on it: with
     * [Factory.slots] calls held, whichever callers sent them, a send by any
     * caller is refused with `SendError.Busy`. Reading an outcome does not
     * free a slot; forgetting a settled call does, and whichever caller sends
     * next takes it (ADR-0021 decision 15).
     */
    public fun `a send with every slot taken is busy for every caller`() {
        assertTrue(factory.slots > 0, "a runtime holds at least one call")
        val rt = runtime()
        val second = factory.caller(rt)
        rt.serve(IFACE, listOf(ORD))

        // The two callers take turns, so each holds part of the table.
        val mine = mutableListOf<Correlation>()
        val theirs = mutableListOf<Correlation>()
        repeat(factory.slots) { n ->
            if (n % 2 == 0) mine += rt.command(IFACE, ORD, bytes(1)) else theirs += second.command(IFACE, ORD, bytes(1))
            rt.settle(rt.claim().id, ok())
        }

        assertThrows<SendError.Busy> { rt.command(IFACE, ORD, bytes(2)) }
        assertThrows<SendError.Busy> { rt.query(IFACE, ORD, bytes(2)) }
        assertThrows<SendError.Busy>("the table is the runtime's, shared by every caller") { second.command(IFACE, ORD, bytes(2)) }

        mine.forEach { assertEquals(Result.success(Unit), rt.ack(it)) }
        theirs.forEach { assertEquals(Result.success(Unit), second.ack(it)) }
        assertThrows<SendError.Busy>("reading an outcome frees no slot") { second.command(IFACE, ORD, bytes(2)) }

        rt.forget(mine[0])
        second.command(IFACE, ORD, bytes(3))
        assertThrows<SendError.Busy>("and the table is full again") { rt.command(IFACE, ORD, bytes(4)) }
    }

    /** After its slot is reclaimed and taken by a new call, an old correlation answers `null`, and forgetting it again leaves the new call alone. */
    public fun `a reclaimed slots old correlation answers none`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val old = fill(rt)[0]
        rt.forget(old)

        val new = rt.query(IFACE, ORD, bytes(2))
        assertNotEquals(old, new, "the slot is taken under a new correlation")
        rt.settle(rt.claim().id, ok(8, 8))

        assertNull(rt.ack(old), "the old correlation has no outcome")
        assertNull(rt.reply(old, out(8)), "and does not read the new call's reply")
        rt.forget(old)
        val buf = out(8)
        assertEquals(Result.success(2), rt.reply(new, buf), "forgetting the old correlation again leaves the new call alone")
        assertArrayEquals(array(8, 8), buf.written())
    }

    /** A `forget` between the claim and the settlement does not revoke the provider's settlement, and the caller is not told of it. */
    public fun `forget between the claim and the settlement leaves the settlement valid`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.query(IFACE, ORD, bytes(1))
        val claim = rt.claim()
        rt.forget(correlation)

        rt.settle(claim.id, ok(7))
        assertNull(rt.reply(correlation, out(8)))
    }

    /**
     * A `forget` between the offer of a claim through `ShortClaim` and its
     * settlement does not revoke the settlement either: the offered claim is
     * the provider's, and settling it by the id `ShortClaim` carried frees the
     * slot (driftsys/ridl#569).
     */
    public fun `forget between the offer and the settlement leaves the settlement valid`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1, 2, 3))
        val claim = assertThrows<ReadError.ShortClaim> { rt.nextClaim(out(1)) }.claim
        rt.forget(correlation)

        rt.settle(claim, ok())
        assertNull(rt.ack(correlation), "nothing is readable for a forgotten call")
        assertEquals(factory.slots, sendsUntilBusy(rt), "the settlement freed the slot")
    }

    /**
     * A correlation is not a claim. Before this call is presented there is no
     * claim to settle, and a settlement accepted here would acknowledge a call
     * the provider has not seen.
     */
    public fun `a claim that was never presented cannot be settled`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val correlation = rt.command(IFACE, ORD, bytes(1))
        assertThrows<SettleError.UnknownClaim> { rt.settle(ClaimId(correlation.value), ok()) }
        assertNull(rt.ack(correlation), "and nothing was acknowledged")

        rt.settle(rt.claim().id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * A settlement of a claim the handler does not hold is checked before the
     * injected failure is consumed, so the failure still has the next real
     * settlement to fail. The claim the handler does not hold is one it has
     * already settled, which no runtime can hold again.
     */
    public fun `an injected settle failure is not spent on an unknown claim`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        rt.command(IFACE, ORD, bytes(1))
        rt.command(IFACE, ORD, bytes(2))
        val settled = rt.claim()
        val claim = rt.claim()
        rt.settle(settled.id, ok())

        factory.failNextSettle(rt)
        assertThrows<SettleError.UnknownClaim>("the claim is checked before the injected failure is consumed") {
            rt.settle(settled.id, ok())
        }
        assertInjectedFailure({ rt.settle(claim.id, ok()) }, "so the injected failure still has the next real settlement to fail")
    }

    /** Two providers in one process settle their own calls and not each other's: a claim belongs to the handler it was presented to. */
    public fun `a handler cannot settle another handlers claim`() {
        val rt = runtime()
        val second = factory.handler(rt)
        rt.serve(IFACE, listOf(ORD))
        second.serve(InterfaceNo(2u), listOf(ORD))

        val correlation = rt.command(IFACE, ORD, bytes(1))
        val claim = rt.claim()

        assertThrows<SettleError.UnknownClaim>("the claim is not the second handler's to settle") {
            second.settle(claim.id, ok())
        }
        assertNull(rt.ack(correlation), "and nothing was acknowledged in the caller's name")

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(correlation))
    }

    /**
     * Two components providing different interfaces in one process: each
     * handler is presented only the calls to what it served. A handler
     * presented another handler's call would settle it `UnknownInteraction`
     * through the generated dispatch, and the call would be lost.
     */
    public fun `two handlers each receive only what they served`() {
        val rt = runtime()
        val second = factory.handler(rt)
        rt.serve(IFACE, listOf(ORD))
        second.serve(InterfaceNo(2u), listOf(ORD))

        rt.command(InterfaceNo(2u), ORD, bytes(7))
        rt.command(IFACE, ORD, bytes(8))

        var buf = out(8)
        assertEquals(IFACE, rt.claim(buf).iface, "the call the first handler served")
        assertArrayEquals(array(8), buf.written())

        buf = out(8)
        assertEquals(InterfaceNo(2u), second.claim(buf).iface, "and the second handler's own")
        assertArrayEquals(array(7), buf.written())

        assertNull(rt.nextClaim(out(8)), "neither handler consumed the other's call")
        assertNull(second.nextClaim(out(8)))
    }
}
