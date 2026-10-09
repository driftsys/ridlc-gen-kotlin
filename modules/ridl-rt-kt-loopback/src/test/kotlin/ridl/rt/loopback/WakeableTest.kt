package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.Transport
import ridl.rt.port.ClaimId
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loopback's `Wakeable` (story E11.16), `Transport.Busy` carried back to a
 * caller, and the bounded call table (story E11.18): the "Waking" and "The
 * bounded call table" tests of `crates/ridl-loopback/tests/ports.rs` on ridl
 * `main` at 5ac7082, ahead of the release that carries them, in the same order
 * and under the same names. The three `..._drops_without_deadlock` tests have
 * no JVM spelling: they drop a waker, and with it a handle, under the store's
 * lock, and a JVM handle is closed by a call, never by a destructor.
 *
 * Every handle is `Wakeable`, and stores one waker per kind of key it
 * observes: the source one `Event` waker, the handler one `Claim` waker, the
 * caller an `Outcome` waker with each call. A change to any key of a kind
 * wakes that kind's waker, a key a handle does not observe is woken at once,
 * and `Slot` is always woken at once. Where a Rust test counts the references
 * to a waker to prove a dropped handle released it, the test here counts the
 * wakes that still reach it after `close`.
 */
class WakeableTest {
    private val iface = InterfaceNo(1u)
    private val iface2 = InterfaceNo(2u)
    private val ord = Ordinal(1u)
    private val other = Ordinal(2u)

    private fun runtime() = Loopback(CatalogRef("face.demo", CatalogHash(ByteArray(32))))

    /** A waker that counts its wakes. */
    private class Count : Waker {
        private val count = AtomicInteger()

        val wakes: Int get() = count.get()

        override fun wake() {
            count.incrementAndGet()
        }
    }

    private fun out() = ByteBuffer.allocate(8)

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

    private fun ok() = Result.success(bytes())

    /** The bytes a port wrote into this buffer: from 0 to its position. */
    private fun ByteBuffer.written(): ByteArray = ByteArray(position()).also {
        val view = duplicate()
        view.flip()
        view.get(it)
    }

    @Test
    fun `each settlement wakes only its own calls waiter`() {
        val h = runtime().split()
        val firstCall = h.caller.command(iface, ord, bytes(1), null)
        val secondCall = h.caller.command(iface, ord, bytes(2), null)
        val first = Count()
        val second = Count()
        h.caller.wakeOn(Interest.Outcome(firstCall), first)
        h.caller.wakeOn(Interest.Outcome(secondCall), second)
        assertEquals(0 to 0, first.wakes to second.wakes, "two calls, two wakers, no displacement")

        val claimOne = h.handler.nextClaim(out())!!
        val claimTwo = h.handler.nextClaim(out())!!
        h.handler.settle(claimTwo.id, ok())
        assertEquals(0 to 1, first.wakes to second.wakes, "only the second call is settled")
        h.handler.settle(claimOne.id, ok())
        assertEquals(1 to 1, first.wakes to second.wakes)
    }

    @Test
    fun `a waiter registered after the settlement is woken at once`() {
        val h = runtime().split()
        val c = h.caller.query(iface, ord, bytes(1), null)
        h.handler.settle(h.handler.nextClaim(out())!!.id, Result.success(bytes(7)))

        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(1, count.wakes, "the outcome is already known")
        assertEquals(Result.success(1), h.caller.reply(c, out()))
    }

    @Test
    fun `a raise wakes a subscribed source and not an unsubscribed one`() {
        val rt = runtime()
        val sink = rt.sink()
        val subscribed = rt.source()
        val elsewhere = rt.source()
        subscribed.subscribe(iface, listOf(ord))
        // Subscribed to another event of the same interface: its key is the
        // same `Event(iface)`, and the occurrence below is still not its.
        elsewhere.subscribe(iface, listOf(other))
        val unsubscribed = rt.source()

        val woken = Count()
        val otherCount = Count()
        val none = Count()
        subscribed.wakeOn(Interest.Event(iface), woken)
        elsewhere.wakeOn(Interest.Event(iface), otherCount)
        unsubscribed.wakeOn(Interest.Event(iface), none)

        sink.raise(iface, ord, bytes(1), null)
        assertEquals(1, woken.wakes, "the subscribed source is woken")
        assertEquals(0, otherCount.wakes, "a source subscribed to another event is not")
        assertEquals(0, none.wakes, "an unsubscribed source is not")
        assertNotNull(subscribed.next(out()))
    }

    @Test
    fun `a waiting occurrence of another interface wakes an event registration at once`() {
        val rt = runtime()
        val source = rt.source()
        source.subscribe(iface2, listOf(ord))
        rt.sink().raise(iface2, ord, bytes(1), null)

        val count = Count()
        source.wakeOn(Interest.Event(iface), count)
        assertEquals(1, count.wakes, "an occurrence of another interface is waiting")
    }

    @Test
    fun `a waiting call on another interface wakes a claim registration at once`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface2, listOf(ord))
        rt.caller().command(iface2, ord, bytes(1), null)

        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(1, count.wakes, "a call on another interface is waiting")
    }

    @Test
    fun `a drop returns only the dropped handlers claims and wakes every serving handler`() {
        val rt = runtime()
        val caller = rt.caller()
        val keeper = rt.handler()
        val dropped = rt.handler()
        val otherHandler = rt.handler()
        val elsewhere = rt.handler()
        keeper.serve(iface, listOf(ord))
        dropped.serve(iface, listOf(ord))
        otherHandler.serve(iface, listOf(ord))
        elsewhere.serve(iface, listOf(other))

        val kept = caller.command(iface, ord, bytes(1), null)
        val keptClaim = keeper.nextClaim(out())!!
        caller.command(iface, ord, bytes(2), null)
        dropped.nextClaim(out())!!

        val first = Count()
        val second = Count()
        val third = Count()
        keeper.wakeOn(Interest.Claim(iface), first)
        otherHandler.wakeOn(Interest.Claim(iface), second)
        elsewhere.wakeOn(Interest.Claim(iface), third)
        dropped.close()
        assertEquals(1, first.wakes, "every serving handler is woken: the first")
        assertEquals(1, second.wakes, "every serving handler is woken: the second")
        assertEquals(0, third.wakes, "a handler serving another member is not woken")
        assertNull(elsewhere.nextClaim(out()), "and is presented nothing")

        val buf = out()
        assertNotNull(otherHandler.nextClaim(buf), "returned")
        assertArrayEquals(byteArrayOf(2), buf.written(), "only the dropped handler's call")
        assertNull(otherHandler.nextClaim(out()), "the call another handler holds stays with it")
        keeper.settle(keptClaim.id, ok())
        assertEquals(Result.success(Unit), caller.ack(kept))
    }

    @Test
    fun `a waiter woken at once is not stored`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        sink.raise(iface, ord, bytes(1), null)
        val count = Count()
        source.wakeOn(Interest.Event(iface), count)
        assertEquals(1, count.wakes, "an occurrence is waiting")

        sink.raise(iface, ord, bytes(2), null)
        assertEquals(1, count.wakes, "a waker woken at once was not stored")
    }

    @Test
    fun `a registration on a forgotten call is woken at once`() {
        // A forgotten call a handler has claimed is still in the call table,
        // but no outcome will be recorded for it.
        val h = runtime().split()
        val c = h.caller.command(iface, ord, bytes(1), null)
        h.handler.nextClaim(out())!!
        h.caller.forget(c)
        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(1, count.wakes, "no outcome will be recorded")
    }

    @Test
    fun `every subscribed source and every serving handler is woken`() {
        val rt = runtime()
        val sink = rt.sink()
        val caller = rt.caller()
        val firstSource = rt.source()
        val secondSource = rt.source()
        firstSource.subscribe(iface, listOf(ord))
        secondSource.subscribe(iface, listOf(ord))
        val firstHandler = rt.handler()
        val secondHandler = rt.handler()
        firstHandler.serve(iface, listOf(ord))
        secondHandler.serve(iface, listOf(ord))

        val wakers = List(4) { Count() }
        firstSource.wakeOn(Interest.Event(iface), wakers[0])
        secondSource.wakeOn(Interest.Event(iface), wakers[1])
        firstHandler.wakeOn(Interest.Claim(iface), wakers[2])
        secondHandler.wakeOn(Interest.Claim(iface), wakers[3])

        sink.raise(iface, ord, bytes(1), null)
        assertEquals(1, wakers[0].wakes, "the first source")
        assertEquals(1, wakers[1].wakes, "the second source")

        caller.command(iface, ord, bytes(1), null)
        assertEquals(1, wakers[2].wakes, "the first handler")
        assertEquals(1, wakers[3].wakes, "the second handler")
    }

    @Test
    fun `a claim registration is not woken by a call the handler does not serve`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        rt.caller().command(iface, ord, bytes(1), null)
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes, "the waiting call is not this handler's")
    }

    @Test
    fun `a serve that admits nothing does not wake the handler`() {
        val rt = runtime()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        handler.serve(iface, listOf(ord))
        assertEquals(0, count.wakes, "no call is waiting")
    }

    @Test
    fun `a dropped handler returns its claims to the waiting calls`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(ord))

        val c = caller.command(iface, ord, bytes(1), null)
        first.nextClaim(out())!!
        val later = caller.command(iface, ord, bytes(2), null)
        first.nextClaim(out())!!
        assertNull(second.nextClaim(out()), "both calls are held by the first handler")
        val count = Count()
        second.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes)
        // The caller waits on the first call across the drop: the return does
        // not wake it, and the settlement by the handler that takes it does.
        val outcome = Count()
        caller.wakeOn(Interest.Outcome(c), outcome)

        first.close()
        assertEquals(1, count.wakes, "the returned claims wake a serving handler")
        assertEquals(0, outcome.wakes, "a returned claim is not an outcome")

        // They return in send order, and are settled by the handler that takes them.
        var buf = out()
        val again = second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(1), buf.written(), "the earlier call first")
        second.settle(again.id, ok())
        assertEquals(1, outcome.wakes, "the settlement by the second handler wakes the caller")
        buf = out()
        val then = second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(2), buf.written())
        second.settle(then.id, ok())
        assertEquals(Result.success(Unit), caller.ack(c))
        assertEquals(Result.success(Unit), caller.ack(later))
    }

    /** The number of commands [caller] accepts before it throws `SendError.Busy`, counting at most one more than `Loopback.SLOTS`. */
    private fun sendsUntilBusy(caller: ridl.rt.port.Caller): Int {
        for (sent in 0..Loopback.SLOTS) {
            try {
                caller.command(iface, ord, bytes(9), null)
            } catch (_: SendError.Busy) {
                return sent
            }
        }
        return Loopback.SLOTS + 1
    }

    /** Offers [handler] the next waiting call through a buffer too short for it, and returns the id `ShortClaim` carried. */
    private fun offer(handler: ridl.rt.port.Handler): ClaimId =
        assertThrows<ReadError.ShortClaim>("a buffer shorter than the arguments reports ShortClaim") {
            handler.nextClaim(ByteBuffer.allocate(1))
        }.claim

    /**
     * driftsys/ridl#569: a call offered through `ShortClaim` whose caller then
     * forgets it stays among the waiting calls, so the provider's retry with a
     * larger buffer presents it under the same id, and its settlement frees
     * the slot as for a forgotten taken claim.
     */
    @Test
    fun `a forgotten offered call is presented again under the same id and its settlement frees the slot`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(ord))
        val c = caller.command(iface, ord, bytes(1, 2, 3), null)
        val claim = offer(handler)
        caller.forget(c)

        val buf = out()
        val retry = checkNotNull(handler.nextClaim(buf)) { "a forgotten offered call stays presentable" }
        assertEquals(claim, retry.id, "under the id ShortClaim carried")
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.written())
        handler.settle(retry.id, ok())
        assertEquals(Loopback.SLOTS, sendsUntilBusy(caller), "the settlement freed the slot")
    }

    /**
     * driftsys/ridl#569: a closed handler's offered claim never left the
     * waiting calls, so it is not re-inserted and no serving handler is woken
     * for it; another handler that serves the member takes it exactly once,
     * under the same id, and its settlement reaches the caller.
     */
    @Test
    fun `a dropped handlers offered claim is taken once by another handler under the same id`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(ord))
        val c = caller.command(iface, ord, bytes(1, 2, 3), null)
        val claim = offer(first)
        // Registered while the offered call is already waiting, so the
        // registration is woken at once; the count is read before the close
        // and compared after.
        val count = Count()
        second.wakeOn(Interest.Claim(iface), count)
        val before = count.wakes

        first.close()
        assertEquals(before, count.wakes, "the close wakes no handler for an offered claim: the call never left the waiting calls")
        val taken = checkNotNull(second.nextClaim(out())) { "the offered call is still waiting" }
        assertEquals(claim, taken.id, "the id stays on the call")
        assertNull(second.nextClaim(out()), "the call is presented once, not re-inserted")
        second.settle(taken.id, ok())
        assertEquals(Result.success(Unit), caller.ack(c), "the settlement reaches the caller")
    }

    /**
     * driftsys/ridl#569: an offered call whose caller forgot it is withdrawn
     * when its handler is closed, as a forgotten taken claim is: no handler is
     * presented it, and its slot is reclaimed.
     */
    @Test
    fun `a forgotten offered call is withdrawn when its handler drops`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(ord))
        val c = caller.command(iface, ord, bytes(1, 2, 3), null)
        offer(first)
        caller.forget(c)

        first.close()
        assertNull(second.nextClaim(out()), "a forgotten offered call is withdrawn at the close")
        assertEquals(Loopback.SLOTS, sendsUntilBusy(caller), "and its slot is reclaimed")
    }

    /**
     * driftsys/ridl#569: another handler that serves the member may take a
     * call offered to the first, under the same id; the claim moves with the
     * take, so the first handler's settlement of that id is unknown.
     */
    @Test
    fun `an offered call taken by another handler moves the claim to it`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(ord))
        val c = caller.command(iface, ord, bytes(1, 2, 3), null)
        val claim = offer(first)

        val taken = checkNotNull(second.nextClaim(out())) { "an offered call can be taken by another serving handler" }
        assertEquals(claim, taken.id)
        second.settle(taken.id, ok())
        assertThrows<SettleError.UnknownClaim>("the claim moved to the taker") { first.settle(claim, ok()) }
        assertEquals(Result.success(Unit), caller.ack(c))
    }

    @Test
    fun `a send wakes the handler that serves the member`() {
        val rt = runtime()
        val caller = rt.caller()
        val serving = rt.handler()
        val elsewhere = rt.handler()
        serving.serve(iface, listOf(ord))
        // Serves another member of the same interface: its key is the same
        // `Claim(iface)`, and the call below is still not presented to it.
        elsewhere.serve(iface, listOf(other))

        val woken = Count()
        val otherCount = Count()
        serving.wakeOn(Interest.Claim(iface), woken)
        elsewhere.wakeOn(Interest.Claim(iface), otherCount)

        caller.command(iface, ord, bytes(1), null)
        assertEquals(1, woken.wakes, "the serving handler is woken")
        assertEquals(0, otherCount.wakes, "a handler serving another member is not")
        assertNotNull(serving.nextClaim(out()))
    }

    @Test
    fun `a serve that admits a waiting call wakes the handler`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        caller.command(iface, ord, bytes(1), null)
        assertEquals(0, count.wakes, "the handler does not serve the member")

        handler.serve(iface, listOf(ord))
        assertEquals(1, count.wakes, "the call is now waiting for this handler")
    }

    @Test
    fun `a caller handle reads the clock`() {
        val rt = runtime()
        val caller = rt.caller()
        rt.advance(Duration(250))
        assertEquals(Timestamp(250), caller.now())
        assertEquals(rt.now(), caller.now())
    }

    @Test
    fun `a registration whose key already holds is woken at once`() {
        val rt = runtime()
        val caller = rt.caller()
        val sink = rt.sink()
        val source = rt.source()
        val handler = rt.handler()
        source.subscribe(iface, listOf(ord))

        // The call table has no bound, so a slot is always free.
        val slot = Count()
        caller.wakeOn(Interest.Slot, slot)
        assertEquals(1, slot.wakes, "a slot is free")

        sink.raise(iface, ord, bytes(1), null)
        val event = Count()
        source.wakeOn(Interest.Event(iface), event)
        assertEquals(1, event.wakes, "an occurrence is waiting")

        caller.command(iface, ord, bytes(1), null)
        val claim = Count()
        handler.wakeOn(Interest.Claim(iface), claim)
        assertEquals(1, claim.wakes, "a call is waiting")

        // A correlation that names no call in flight has no outcome to wait
        // for; storing its waker would leave it waiting on nothing.
        val unknown = Count()
        caller.wakeOn(Interest.Outcome(Correlation(99)), unknown)
        assertEquals(1, unknown.wakes, "no call has that correlation")
    }

    @Test
    fun `a forgotten call wakes its waiter`() {
        val h = runtime().split()
        val c = h.caller.command(iface, ord, bytes(1), null)
        val count = Count()
        h.caller.wakeOn(Interest.Outcome(c), count)
        h.caller.forget(c)
        assertEquals(1, count.wakes, "the forget wakes the waiter")
    }

    @Test
    fun `a key no role of the handle observes is woken at once`() {
        val h = runtime().split()
        val count = Count()
        h.reader.wakeOn(Interest.Slot, count)
        h.writer.wakeOn(Interest.Event(iface), count)
        h.sink.wakeOn(Interest.Claim(iface), count)
        h.caller.wakeOn(Interest.Event(iface), count)
        h.caller.wakeOn(Interest.Claim(iface), count)
        h.source.wakeOn(Interest.Slot, count)
        h.source.wakeOn(Interest.Claim(iface), count)
        h.source.wakeOn(Interest.Outcome(Correlation(0)), count)
        h.handler.wakeOn(Interest.Slot, count)
        h.handler.wakeOn(Interest.Event(iface), count)
        h.handler.wakeOn(Interest.Outcome(Correlation(0)), count)
        assertEquals(11, count.wakes)
    }

    @Test
    fun `a dropped handler leaves no waiter behind`() {
        // `close` removes the handler's state, its stored waker with it, and
        // returns its claims; the handler's own state is gone before the
        // return wakes the handlers that serve the member.
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        caller.command(iface, ord, bytes(1), null)
        handler.nextClaim(out())!!
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        handler.close()
        assertEquals(0, count.wakes, "the returned claim does not wake it")
        caller.command(iface, ord, bytes(2), null)
        assertEquals(0, count.wakes, "nothing wakes a closed handler's waker")
    }

    @Test
    fun `a dropped source leaves no waiter behind`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        val count = Count()
        source.wakeOn(Interest.Event(iface), count)
        source.close()
        sink.raise(iface, ord, bytes(1), null)
        assertEquals(0, count.wakes, "nothing wakes a closed source's waker")
    }

    @Test
    fun `a providers busy settlement reaches the caller`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val command = caller.command(iface, ord, bytes(1), null)
        val query = caller.query(iface, ord, bytes(2), null)
        while (true) {
            val claim = handler.nextClaim(out()) ?: break
            handler.settle(claim.id, Result.failure(Transport.Busy))
        }
        assertEquals(Result.failure<Unit>(Transport.Busy), caller.ack(command))
        assertEquals(Result.failure<Int>(Transport.Busy), caller.reply(query, out()))
    }

    @Test
    fun `a returned claim keeps its place by send order`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()

        caller.command(iface, ord, bytes(1), null)
        first.nextClaim(out())!!
        caller.command(iface, ord, bytes(2), null)
        first.close()

        val buf = out()
        second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(1), buf.written(), "the returned call was sent first, so it is presented first")
    }

    @Test
    fun `a returned claim keeps its send order across a reused slot`() {
        // A reused slot carries a higher generation, so a call sent into it
        // has a larger correlation than a call sent later into a fresh slot.
        // A returned claim goes back in send order, not correlation order.
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()

        val old = caller.command(iface, ord, bytes(0), null)
        first.settle(first.nextClaim(out())!!.id, ok())
        caller.forget(old)

        val reused = caller.command(iface, ord, bytes(1), null)
        val fresh = caller.command(iface, ord, bytes(2), null)
        assertTrue(reused.value > fresh.value, "the reused slot's correlation is the larger one")
        first.nextClaim(out())!!
        first.close()

        val buf = out()
        second.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(1), buf.written(), "the returned call was sent first, so it is presented first")
    }

    // ---------------------------------------------------------------------
    // The bounded call table: `Loopback.SLOTS` calls in flight, reclaimed by
    // `forget` (ADR-0021 decision 15, note F-9).
    // ---------------------------------------------------------------------

    /** Sends `Loopback.SLOTS` commands through [caller], which fills the table. */
    private fun fill(caller: ridl.rt.port.Caller): List<Correlation> =
        List(Loopback.SLOTS) { caller.command(iface, ord, bytes(it), null) }

    @Test
    fun `the seventeenth in flight call is busy`() {
        assertEquals(16, Loopback.SLOTS)
        val rt = runtime()
        val caller = rt.caller()
        val other = rt.caller()
        val handler = rt.handler()
        val calls = fill(caller)

        assertThrows<SendError.Busy> { caller.command(iface, ord, bytes(99), null) }
        assertThrows<SendError.Busy> { caller.query(iface, ord, bytes(99), null) }
        assertThrows<SendError.Busy>("the table is the runtime's, shared by every caller") { other.command(iface, ord, bytes(99), null) }

        handler.settle(handler.nextClaim(out())!!.id, ok())
        assertEquals(Result.success(Unit), caller.ack(calls[0]))
        assertThrows<SendError.Busy>("a settled call keeps its slot until it is forgotten") { caller.command(iface, ord, bytes(99), null) }

        caller.forget(calls[0])
        other.command(iface, ord, bytes(99), null)
        assertThrows<SendError.Busy> { caller.command(iface, ord, bytes(100), null) }
    }

    @Test
    fun `a refused send draws no sequence number`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val calls = fill(caller)
        assertThrows<SendError.Busy> { caller.command(iface, ord, bytes(99), null) }

        var last = 0uL
        repeat(calls.size) {
            val claim = handler.nextClaim(out())!!
            last = claim.envelope.seq
            handler.settle(claim.id, ok())
        }
        caller.forget(calls[0])
        caller.command(iface, ord, bytes(99), null)
        assertEquals(last + 1u, handler.nextClaim(out())!!.envelope.seq, "nothing was sent, so no number was used")
    }

    @Test
    fun `a claimed then forgotten call holds its slot until its settlement`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val calls = fill(caller)
        val count = Count()
        caller.wakeOn(Interest.Slot, count)

        val buf = out()
        val claim = handler.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(0), buf.written(), "the claim is calls[0]")
        caller.forget(calls[0])
        assertEquals(0, count.wakes, "the claimed call still holds its slot")
        assertThrows<SendError.Busy> { caller.command(iface, ord, bytes(99), null) }

        handler.settle(claim.id, ok())
        assertEquals(1, count.wakes, "its settlement reclaims the slot")
        caller.command(iface, ord, bytes(99), null)
    }

    @Test
    fun `forgotten calls to an unserved member leave room for another send`() {
        // No handler serves `other`, so these calls are never claimed and
        // never settled. Each `forget` withdraws its call and reclaims its slot
        // at once; without that, the table stays full for the life of the runtime.
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(ord))
        val calls = List(Loopback.SLOTS) { caller.command(iface, other, bytes(it), null) }
        assertThrows<SendError.Busy> { caller.command(iface, other, bytes(99), null) }
        val count = Count()
        caller.wakeOn(Interest.Slot, count)

        calls.forEach(caller::forget)
        assertEquals(1, count.wakes, "the withdrawal reclaimed a slot")
        repeat(Loopback.SLOTS) { caller.command(iface, other, bytes(it), null) }
        assertThrows<SendError.Busy> { caller.command(iface, other, bytes(99), null) }
    }

    @Test
    fun `a withdrawn call is never presented to a handler that serves the member later`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)

        val withdrawn = caller.command(iface, ord, bytes(1), null)
        val kept = caller.command(iface, ord, bytes(2), null)
        caller.forget(withdrawn)

        handler.serve(iface, listOf(ord))
        assertEquals(1, count.wakes, "the call not forgotten is waiting")
        val buf = out()
        val claim = handler.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(2), buf.written(), "the withdrawn call is skipped")
        handler.settle(claim.id, ok())
        assertEquals(Result.success(Unit), caller.ack(kept))
        assertNull(handler.nextClaim(out()), "the withdrawn call is never presented")
    }

    @Test
    fun `a withdrawal from the middle of the queue leaves the calls around it in order`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        caller.command(iface, ord, bytes(1), null)
        val middle = caller.command(iface, ord, bytes(2), null)
        caller.command(iface, ord, bytes(3), null)
        caller.forget(middle)

        handler.serve(iface, listOf(ord))
        for (expected in listOf(1, 3)) {
            val buf = out()
            handler.nextClaim(buf)!!
            assertArrayEquals(byteArrayOf(expected.toByte()), buf.written(), "send order, less the middle")
        }
        assertNull(handler.nextClaim(out()))
    }

    @Test
    fun `forgetting a claimed call wakes its outcome waiter`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val c = caller.command(iface, ord, bytes(1), null)
        handler.nextClaim(out())!!
        val count = Count()
        caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes, "the claimed call is in flight")

        caller.forget(c)
        assertEquals(1, count.wakes, "no outcome will be readable for a forgotten call")
    }

    @Test
    fun `a stale correlation does not withdraw the call now in its slot`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val old = caller.command(iface, ord, bytes(1), null)
        handler.settle(handler.nextClaim(out())!!.id, ok())
        caller.forget(old)

        val new = caller.command(iface, ord, bytes(2), null)
        assertNotEquals(old, new, "the slot is reused under a new generation")
        caller.forget(old)
        val buf = out()
        val claim = checkNotNull(handler.nextClaim(buf)) { "the stale correlation withdrew nothing" }
        assertArrayEquals(byteArrayOf(2), buf.written())
        handler.settle(claim.id, ok())
        assertEquals(Result.success(Unit), caller.ack(new))
    }

    @Test
    fun `a call forgotten while claimed is withdrawn when its handler is dropped`() {
        // Sixteen rounds of claim and forget, each call held by its own
        // handler, then every handler closed. No other handler serves the
        // member, so a call returned to the waiting calls would hold its slot
        // for the life of the runtime.
        val rt = runtime()
        val caller = rt.caller()
        val handlers = List(Loopback.SLOTS) { n ->
            val c = caller.command(iface, ord, bytes(n), null)
            rt.handler().also { handler ->
                handler.serve(iface, listOf(ord))
                handler.nextClaim(out())!!
                caller.forget(c)
            }
        }
        assertThrows<SendError.Busy>("a claimed call keeps its slot after its forget") { caller.command(iface, ord, bytes(99), null) }
        val count = Count()
        caller.wakeOn(Interest.Slot, count)
        assertEquals(0, count.wakes, "no slot is free: the waker is stored")

        handlers.forEach { it.close() }
        assertEquals(1, count.wakes, "the closes reclaimed the slots")
        repeat(Loopback.SLOTS) { caller.command(iface, ord, bytes(100 + it), null) }
        assertThrows<SendError.Busy> { caller.command(iface, ord, bytes(99), null) }
        val later = rt.handler()
        later.serve(iface, listOf(ord))
        repeat(Loopback.SLOTS) { n ->
            val buf = out()
            later.nextClaim(buf)!!
            assertArrayEquals(byteArrayOf((100 + n).toByte()), buf.written(), "no withdrawn call is presented again")
        }
        assertNull(later.nextClaim(out()))
    }

    @Test
    fun `a forget withdrawal wakes no claim waiter of a handler serving another member`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        handler.serve(iface, listOf(other))
        val c = caller.command(iface, ord, bytes(1), null)
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes, "no call it serves waits: stored")

        caller.forget(c)
        assertEquals(0, count.wakes, "a withdrawal adds no call to claim, so it wakes no claim waiter")
        caller.command(iface, other, bytes(2), null)
        assertEquals(1, count.wakes, "the waker was still stored")
    }

    @Test
    fun `a dropped handler withdraws only its forgotten claim and returns the others`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        first.serve(iface, listOf(ord))
        val sent = (1..3).map { caller.command(iface, ord, bytes(it), null) }
        repeat(sent.size) { first.nextClaim(out())!! }
        for (n in 3 until Loopback.SLOTS) caller.command(iface, other, bytes(n), null)
        caller.forget(sent[1])
        assertThrows<SendError.Busy>("the claimed call keeps its slot after its forget") { caller.command(iface, other, bytes(99), null) }

        first.close()
        caller.command(iface, other, bytes(99), null)
        assertThrows<SendError.Busy>("exactly one slot came back") { caller.command(iface, other, bytes(100), null) }
        val later = rt.handler()
        later.serve(iface, listOf(ord))
        for (expected in listOf(1, 3)) {
            val buf = out()
            later.nextClaim(buf)!!
            assertArrayEquals(byteArrayOf(expected.toByte()), buf.written(), "send order, less 2")
        }
        assertNull(later.nextClaim(out()))
    }

    @Test
    fun `a dropped callers claimed call is withdrawn when its handler is dropped`() {
        val rt = runtime()
        val caller = rt.caller()
        val other = rt.caller()
        val first = rt.handler()
        first.serve(iface, listOf(ord))
        caller.command(iface, ord, bytes(1), null)
        first.nextClaim(out())!!

        caller.close()
        first.close()
        repeat(Loopback.SLOTS) { other.command(iface, ord, bytes(100 + it), null) }
        val later = rt.handler()
        later.serve(iface, listOf(ord))
        val buf = out()
        later.nextClaim(buf)!!
        assertArrayEquals(byteArrayOf(100), buf.written(), "the closed caller's call is not presented again")
    }

    @Test
    fun `a withdrawal at a handlers drop wakes no claim waiter`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(ord))
        val c = caller.command(iface, ord, bytes(1), null)
        first.nextClaim(out())!!
        val count = Count()
        second.wakeOn(Interest.Claim(iface), count)
        assertEquals(0, count.wakes, "nothing is waiting: the waker is stored")

        caller.forget(c)
        first.close()
        assertEquals(0, count.wakes, "a withdrawal adds no call to claim, so it wakes no claim waiter")
        assertNull(second.nextClaim(out()))
    }

    @Test
    fun `a slot registration while a slot is free is woken at once`() {
        val rt = runtime()
        val caller = rt.caller()
        val calls = fill(caller)
        val handler = rt.handler()
        handler.settle(handler.nextClaim(out())!!.id, ok())
        caller.forget(calls[0])

        val count = Count()
        caller.wakeOn(Interest.Slot, count)
        assertEquals(1, count.wakes, "one slot is free")
        caller.command(iface, ord, bytes(99), null)
        caller.wakeOn(Interest.Slot, count)
        assertEquals(1, count.wakes, "the table is full again: stored")
    }

    @Test
    fun `a dropped caller leaves no slot waiter behind`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val calls = fill(caller)

        val other = rt.caller()
        val count = Count()
        other.wakeOn(Interest.Slot, count)
        other.close()

        handler.settle(handler.nextClaim(out())!!.id, ok())
        caller.forget(calls[0])
        assertEquals(0, count.wakes)
    }

    @Test
    fun `a dropped caller forgets its calls and their slots come back`() {
        val rt = runtime()
        val caller = rt.caller()
        val other = rt.caller()
        val handler = rt.handler()
        fill(caller)
        repeat(8) { handler.settle(handler.nextClaim(out())!!.id, ok()) }
        val held = List(8) { handler.nextClaim(out())!! }
        assertThrows<SendError.Busy> { other.command(iface, ord, bytes(99), null) }
        val count = Count()
        other.wakeOn(Interest.Slot, count)

        caller.close()
        assertEquals(1, count.wakes, "the close reclaimed the settled calls' slots")
        repeat(8) { other.command(iface, ord, bytes(it), null) }
        assertThrows<SendError.Busy>("the closed caller's claimed calls keep their slots until settled") {
            other.command(iface, ord, bytes(99), null)
        }

        // The provider still settles the eight claims it holds; each
        // settlement reclaims a slot, because the close forgot the call.
        held.forEach { handler.settle(it.id, ok()) }
        repeat(8) { other.command(iface, ord, bytes(it), null) }
        assertThrows<SendError.Busy> { other.command(iface, ord, bytes(99), null) }
    }

    @Test
    fun `a dropped callers unclaimed calls are withdrawn`() {
        val rt = runtime()
        val caller = rt.caller()
        val other = rt.caller()
        val handler = rt.handler()
        fill(caller)
        val count = Count()
        other.wakeOn(Interest.Slot, count)

        caller.close()
        assertEquals(1, count.wakes, "the close reclaimed the unclaimed calls")
        repeat(Loopback.SLOTS) { other.command(iface, ord, bytes(100 + it), null) }
        assertThrows<SendError.Busy> { other.command(iface, ord, bytes(99), null) }

        repeat(Loopback.SLOTS) { n ->
            val buf = out()
            handler.nextClaim(buf)!!
            assertArrayEquals(byteArrayOf((100 + n).toByte()), buf.written(), "only the other caller's calls are presented")
        }
        assertNull(handler.nextClaim(out()))
    }

    @Test
    fun `a dropped caller forgets only its own calls`() {
        val rt = runtime()
        val first = rt.caller()
        val second = rt.caller()
        val handler = rt.handler()
        first.command(iface, ord, bytes(1), null)
        val theirs = second.command(iface, ord, bytes(2), null)
        while (true) handler.settle((handler.nextClaim(out()) ?: break).id, ok())

        first.close()
        assertEquals(Result.success(Unit), second.ack(theirs), "the other caller's settled call is still readable")
    }

    @Test
    fun `a dropped callers call in flight wakes its outcome waiter`() {
        val rt = runtime()
        val caller = rt.caller()
        val c = caller.command(iface, ord, bytes(1), null)
        val count = Count()
        caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes, "the call is in flight")

        caller.close()
        assertEquals(1, count.wakes, "the close forgot the call, so no outcome will be readable for it")
    }

    @Test
    fun `a dropped callers own slot waiter is not woken by its drop`() {
        val rt = runtime()
        val caller = rt.caller()
        val other = rt.caller()
        val handler = rt.handler()
        fill(caller)
        while (true) handler.settle((handler.nextClaim(out()) ?: break).id, ok())
        val own = Count()
        val theirs = Count()
        caller.wakeOn(Interest.Slot, own)
        other.wakeOn(Interest.Slot, theirs)

        caller.close()
        assertEquals(0 to 1, own.wakes to theirs.wakes, "the closed caller's waiters leave before its calls are reclaimed")
    }

    @Test
    fun `a serve with no call waiting keeps the handlers claim waker`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val count = Count()
        handler.wakeOn(Interest.Claim(iface), count)
        handler.serve(iface, listOf(ord))
        assertEquals(0, count.wakes, "nothing is waiting")

        caller.command(iface, ord, bytes(1), null)
        assertEquals(1, count.wakes, "the waker is still stored, so the send wakes it")
    }

    @Test
    fun `the aggregate routes each key to the handle that observes it`() {
        val rt = runtime()

        val c = rt.command(iface, ord, bytes(1), null)
        val outcome = Count()
        rt.wakeOn(Interest.Outcome(c), outcome)
        assertEquals(0, outcome.wakes, "stored, not woken at once")
        rt.settle(rt.nextClaim(out())!!.id, ok())
        assertEquals(1, outcome.wakes, "woken by the settlement")

        rt.subscribe(iface, listOf(ord))
        val event = Count()
        rt.wakeOn(Interest.Event(iface), event)
        assertEquals(0, event.wakes, "stored, not woken at once")
        rt.raise(iface, ord, bytes(1), null)
        assertEquals(1, event.wakes, "woken by the raise")

        val claim = Count()
        rt.wakeOn(Interest.Claim(iface), claim)
        assertEquals(0, claim.wakes, "stored, not woken at once")
        rt.caller().command(iface, ord, bytes(1), null)
        assertEquals(1, claim.wakes, "woken by the send")

        val slot = Count()
        rt.wakeOn(Interest.Slot, slot)
        assertEquals(1, slot.wakes, "the caller wakes it at once: a slot is free")
    }

    /**
     * A waker that, when woken, reads the clock from another thread and
     * reports whether that read finished within a second. Under the store's
     * monitor, the read could not finish until the wake returned.
     */
    private fun lockProbe(rt: Loopback): Pair<Waker, LinkedBlockingQueue<Boolean>> {
        val reader = rt.reader()
        val done = LinkedBlockingQueue<Boolean>()
        val waker = Waker {
            val read = CompletableFuture.supplyAsync { reader.now() }
            done.offer(runCatching { read.get(1, TimeUnit.SECONDS) }.isSuccess)
        }
        return waker to done
    }

    private fun assertReleased(done: LinkedBlockingQueue<Boolean>, path: String) {
        assertTrue(done.poll(5, TimeUnit.SECONDS) == true, "$path: the waker ran with the store's monitor released")
    }

    @Test
    fun `every wake is run with the lock released`() {
        val rt = runtime()
        val sink = rt.sink()
        val source = rt.source()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        source.subscribe(iface, listOf(ord))
        first.serve(iface, listOf(ord))
        second.serve(iface, listOf(other))

        lockProbe(rt).let { (w, d) -> source.wakeOn(Interest.Event(iface), w); sink.raise(iface, ord, bytes(1), null); assertReleased(d, "a raise") }
        lockProbe(rt).let { (w, d) -> source.wakeOn(Interest.Event(iface), w); assertReleased(d, "a registration whose key already holds") }
        lockProbe(rt).let { (w, d) -> first.wakeOn(Interest.Claim(iface), w); caller.command(iface, ord, bytes(1), null); assertReleased(d, "a command") }

        first.nextClaim(out())!!
        val c = lockProbe(rt).let { (w, d) ->
            first.wakeOn(Interest.Claim(iface), w)
            caller.query(iface, ord, bytes(1), null).also { assertReleased(d, "a query") }
        }
        lockProbe(rt).let { (w, d) -> caller.wakeOn(Interest.Outcome(c), w); caller.forget(c); assertReleased(d, "a forget") }

        // No handler had claimed the query, so the forget withdrew it. Another
        // query waits for the second handler's serve.
        caller.query(iface, ord, bytes(1), null)
        lockProbe(rt).let { (w, d) -> second.wakeOn(Interest.Claim(iface), w); second.serve(iface, listOf(ord)); assertReleased(d, "a serve") }

        // The second handler takes the query, and its close returns it to the first.
        second.nextClaim(out())!!
        lockProbe(rt).let { (w, d) ->
            first.wakeOn(Interest.Claim(iface), w)
            assertNull(first.nextClaim(out()))
            second.close()
            assertReleased(d, "a handler's close")
        }

        // Fill the table, then reclaim a slot three ways: a forget of a settled
        // call, the settlement of a claimed call that was forgotten, and a
        // forget that withdraws a waiting call.
        first.settle(checkNotNull(first.nextClaim(out())) { "the returned query" }.id, ok())
        val sent = mutableListOf<Correlation>()
        while (true) sent += try { caller.command(iface, ord, bytes(2), null) } catch (_: SendError.Busy) { break }
        first.settle(checkNotNull(first.nextClaim(out())) { "the first command" }.id, ok())

        lockProbe(rt).let { (w, d) -> caller.wakeOn(Interest.Slot, w); caller.forget(sent[0]); assertReleased(d, "a forget that reclaims a slot") }

        caller.command(iface, ord, bytes(3), null)
        val claim = checkNotNull(first.nextClaim(out())) { "the second command" }
        caller.forget(sent[1])
        lockProbe(rt).let { (w, d) -> caller.wakeOn(Interest.Slot, w); first.settle(claim.id, ok()); assertReleased(d, "a settlement that reclaims a slot") }

        caller.command(iface, ord, bytes(4), null)
        lockProbe(rt).let { (w, d) -> caller.wakeOn(Interest.Slot, w); caller.forget(sent[2]); assertReleased(d, "a forget that withdraws an unclaimed call") }
    }
}
