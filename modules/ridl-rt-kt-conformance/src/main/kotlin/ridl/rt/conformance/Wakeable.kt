// `Wakeable`: which change wakes which stored waker, and when.
// `crates/ridl-rt-conformance/src/wakeable.rs` (story E11.20, second half, at
// ridl `main` 44e59fa). A runtime may omit the extension, so these tests run
// only through [wakeableSuite].
//
// The contract is the interface's own documentation and ADR-0021 decision 13:
// a handle stores one waker per kind of key — `Slot`, `Event`, `Claim` — and
// an `Outcome` waker with its call; a change to any key of that kind wakes the
// stored waker, which is cleared when woken; a registration by the same task
// is a refresh and wakes nothing; a registration by another task displaces
// the stored waker and wakes it. Every `Slot` waker is woken when a slot is
// reclaimed (decision 15).
//
// A spurious wake is allowed, so no test asserts that a change leaves a waker
// unwoken, except the one change the contract rules out, a refresh. A test
// asserts that a waker is not yet woken only right after its registration,
// with no change made since, or after it was woken once and cleared. Whether a
// registration whose key already holds is woken at once is the runtime's too.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import ridl.rt.contract.InterfaceNo
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.Wakeable
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KFunction0

/** The tests of `Wakeable`. */
public class WakeableContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : Wakeable {
    override val tests: List<KFunction0<Unit>> = listOf(
        ::`an outcome waker is kept with its call and woken once by the settlement`,
        ::`every slot waker is woken by a reclaim and the send that follows succeeds`,
        ::`every subscribed source is woken once by a raise`,
        ::`a call to a served member wakes the handler once`,
        ::`another tasks registration displaces the stored waker and wakes it`,
        ::`the same tasks registration is a refresh that wakes nothing`,
        ::`one waker per kind is woken by a change on any interface of that kind`,
        ::`every wake runs with the runtime lock released`,
    )

    /** A second interface, for the tests of a key's interface. */
    private val iface2 = InterfaceNo(2u)

    /** A waker that counts its wakes. Each one stands for a task; the same object is the same task. */
    private class Count : Waker {
        private val count = AtomicInteger()

        val wakes: Int get() = count.get()

        override fun wake() {
            count.incrementAndGet()
        }
    }

    private fun ok(vararg values: Int): Result<ByteBuffer> = Result.success(bytes(*values))

    // The factory's handles as `Wakeable`: Rust bounds them at compile time,
    // and a Kotlin factory returns the port interfaces, so this is checked here.
    private fun <T> wakeable(handle: T, role: String): T = handle.also {
        check(it is Wakeable) { "the $role this factory makes is not Wakeable, which the wakeable suite requires" }
    }

    private fun caller(rt: R): Caller = wakeable(factory.caller(rt), "caller")

    private fun source(rt: R): EventSource = wakeable(factory.source(rt), "source")

    private fun handler(rt: R): Handler = wakeable(factory.handler(rt), "handler")

    private fun Any.wakeOn(what: Interest, waker: Waker) = (this as Wakeable).wakeOn(what, waker)

    /**
     * An `Outcome` waker is kept with its call: two calls hold two wakers, and
     * neither registration displaces the other. A settlement wakes its call's
     * waker once, after which the outcome reads; the waker is cleared when
     * woken. A waker registered before a handler claims the call has been
     * woken once by the time the settlement returns. A registration after the
     * settlement leaves the outcome readable; whether it also wakes at once is
     * the runtime's. A runtime whose settlement does not wake the waiter fails
     * this test (note F-14).
     */
    public fun `an outcome waker is kept with its call and woken once by the settlement`() {
        val rt = runtime()
        rt.serve(IFACE, listOf(ORD))
        val first = rt.command(IFACE, ORD, bytes(1))
        val second = rt.query(IFACE, ORD, bytes(2))
        // Both calls are claimed before the wakers are registered, so nothing
        // changes between a registration and the settlement that wakes it.
        val claims = arrayOfNulls<ridl.rt.port.ClaimId>(2)
        repeat(2) {
            val buf = out(8)
            val claim = checkNotNull(rt.nextClaim(buf)) { "waiting" }
            claims[buf.get(0) - 1] = claim.id
        }
        val (firstClaim, secondClaim) = claims.map { checkNotNull(it) { "each call was presented once" } }

        val a = Count()
        val b = Count()
        rt.wakeOn(Interest.Outcome(first), a)
        rt.wakeOn(Interest.Outcome(second), b)
        assertEquals(0 to 0, a.wakes to b.wakes, "two calls hold two wakers, and neither displaced the other")

        rt.settle(secondClaim, ok(7))
        assertEquals(1, b.wakes, "the settlement wakes its call's waker")
        assertEquals(Result.success(1), rt.reply(second, out(8)))
        rt.settle(firstClaim, ok())
        assertEquals(1, a.wakes, "the settlement wakes its call's waker")
        assertEquals(Result.success(Unit), rt.ack(first))

        // Another task registers on the settled call. Had the settlement left
        // the first waker stored, this registration would displace and wake it.
        rt.wakeOn(Interest.Outcome(first), Count())
        assertEquals(1, a.wakes, "a woken waker is cleared")
        assertEquals(Result.success(Unit), rt.ack(first), "a registration after the settlement leaves the outcome readable")

        // A third call, registered on before it is claimed. The claim is a
        // change a runtime may wake on spuriously, so nothing is checked
        // between the claim and the settlement.
        val third = rt.command(IFACE, ORD, bytes(3))
        val c = Count()
        rt.wakeOn(Interest.Outcome(third), c)
        assertEquals(0, c.wakes, "nothing has changed since the registration")
        rt.settle(checkNotNull(rt.nextClaim(out(8))).id, ok())
        assertEquals(1, c.wakes, "a waker registered before the claim has been woken once when the settlement returns")
        assertEquals(Result.success(Unit), rt.ack(third))
        assertEquals(1 to 1, a.wakes to b.wakes, "a waker is woken at most once for one registration")
    }

    /**
     * With every slot taken, each caller's `Slot` waker is woken when a slot is
     * reclaimed, and the send that follows succeeds. A slot is reclaimed three
     * ways: a forget of a settled call; the settlement of a claimed call that
     * was forgotten; and a forget of a call no handler has claimed, which a
     * runtime may withdraw at once or hold until it is presented and settled.
     */
    public fun `every slot waker is woken by a reclaim and the send that follows succeeds`() {
        val rt = runtime()
        val second = caller(rt)
        rt.serve(IFACE, listOf(ORD))
        val calls = fill(rt)

        // A forget of a settled call.
        var mine = Count()
        var theirs = Count()
        rt.wakeOn(Interest.Slot, mine)
        second.wakeOn(Interest.Slot, theirs)
        assertEquals(0 to 0, mine.wakes to theirs.wakes, "no slot is free, and nothing has changed")
        rt.forget(calls[0])
        assertEquals(1 to 1, mine.wakes to theirs.wakes, "the reclaim wakes every caller's slot waker")
        val taken = second.command(IFACE, ORD, bytes(1))

        // The settlement of a claimed call that was forgotten.
        mine = Count()
        theirs = Count()
        rt.wakeOn(Interest.Slot, mine)
        second.wakeOn(Interest.Slot, theirs)
        assertEquals(0 to 0, mine.wakes to theirs.wakes, "the table is full again")
        val claim = checkNotNull(rt.nextClaim(out(8))) { "the call the second caller sent" }
        second.forget(taken)
        rt.settle(claim.id, ok())
        assertEquals(1 to 1, mine.wakes to theirs.wakes, "the reclaim wakes every caller's slot waker")
        val unclaimed = rt.command(IFACE, ORD, bytes(2))

        // A forget of a call no handler has claimed: withdrawn, or presented and settled.
        mine = Count()
        theirs = Count()
        rt.wakeOn(Interest.Slot, mine)
        second.wakeOn(Interest.Slot, theirs)
        assertEquals(0 to 0, mine.wakes to theirs.wakes, "the table is full again")
        rt.forget(unclaimed)
        val buf = out(8)
        rt.nextClaim(buf)?.let { held ->
            assertArrayEquals(array(2), buf.written(), "the forgotten call, held")
            rt.settle(held.id, ok())
        }
        assertEquals(1 to 1, mine.wakes to theirs.wakes, "the reclaim wakes every caller's slot waker")
        second.command(IFACE, ORD, bytes(3))
    }

    /**
     * A raise wakes the `Event` waker of every source subscribed to it, once:
     * a second task waiting for the same events holds a second handle, and
     * each handle's waiter is woken. The occurrence reads after the wake, and
     * a woken waker is cleared, so a second raise does not wake it again.
     */
    public fun `every subscribed source is woken once by a raise`() {
        val rt = runtime()
        val second = source(rt)
        rt.subscribe(IFACE, listOf(ORD))
        second.subscribe(IFACE, listOf(ORD))

        val a = Count()
        val b = Count()
        rt.wakeOn(Interest.Event(IFACE), a)
        second.wakeOn(Interest.Event(IFACE), b)
        assertEquals(0 to 0, a.wakes to b.wakes, "nothing has been raised")

        rt.raise(IFACE, ORD, bytes(1))
        assertEquals(1 to 1, a.wakes to b.wakes, "each subscribed source's waker is woken")
        assertNotNull(rt.next(out(8)), "and it reads")
        assertNotNull(second.next(out(8)))

        rt.raise(IFACE, ORD, bytes(2))
        assertEquals(1 to 1, a.wakes to b.wakes, "a woken waker is cleared, so a second raise does not reach it")
    }

    /**
     * A command or a query to a member a handler serves wakes that handler's
     * `Claim` waker, once, and the claim reads after the wake. A woken waker
     * is cleared, so a second call does not wake it again.
     */
    public fun `a call to a served member wakes the handler once`() {
        val rt = runtime()
        val handler = handler(rt)
        handler.serve(IFACE, listOf(ORD))

        for (query in listOf(false, true)) {
            val count = Count()
            handler.wakeOn(Interest.Claim(IFACE), count)
            assertEquals(0, count.wakes, "no call is waiting")
            if (query) rt.query(IFACE, ORD, bytes(1)) else rt.command(IFACE, ORD, bytes(1))
            assertEquals(1, count.wakes, "the call wakes the serving handler")
            rt.command(IFACE, ORD, bytes(2))
            assertEquals(1, count.wakes, "a woken waker is cleared, so a second call does not reach it")
            repeat(2) { handler.settle(checkNotNull(handler.nextClaim(out(8))) { "the claim reads after the wake" }.id, ok()) }
        }
    }

    /**
     * A registration by another task displaces the stored waker of its kind
     * and wakes it; the change then wakes the waker that displaced it. `Slot`,
     * `Event` and `Claim` hold one waker per kind, so a registration under
     * another interface of the same kind displaces too. An `Outcome` waker is
     * kept with its call, so a registration on the same call displaces.
     */
    public fun `another tasks registration displaces the stored waker and wakes it`() {
        var rt = runtime()
        var caller = caller(rt)
        val source = source(rt)
        val handler = handler(rt)
        // Only `handler` serves the member, so the claim cases ask only that a
        // handler serving it is woken, not every one.
        handler.serve(IFACE, listOf(ORD))
        source.subscribe(IFACE, listOf(ORD))

        // An outcome.
        val c = caller.command(IFACE, ORD, bytes(1))
        val claim = checkNotNull(handler.nextClaim(out(8)))
        var a = Count()
        var b = Count()
        caller.wakeOn(Interest.Outcome(c), a)
        caller.wakeOn(Interest.Outcome(c), b)
        assertEquals(1 to 0, a.wakes to b.wakes, "`Outcome`: B displaces A")
        handler.settle(claim.id, ok())
        assertEquals(1 to 1, a.wakes to b.wakes, "`Outcome`: the settlement wakes B")
        caller.forget(c)

        // An event, under one interface and then under another.
        a = Count()
        b = Count()
        var third = Count()
        source.wakeOn(Interest.Event(IFACE), a)
        source.wakeOn(Interest.Event(IFACE), b)
        assertEquals(1 to 0, a.wakes to b.wakes, "`Event`: B displaces A under the same interface")
        source.wakeOn(Interest.Event(iface2), third)
        assertEquals(1 to 0, b.wakes to third.wakes, "`Event`: C displaces B under another interface of the kind")
        rt.raise(IFACE, ORD, bytes(1))
        assertEquals(listOf(1, 1, 1), listOf(a, b, third).map { it.wakes }, "`Event`: the raise wakes C, the one waker stored")

        // A claim, under one interface and then under another.
        a = Count()
        b = Count()
        third = Count()
        handler.wakeOn(Interest.Claim(IFACE), a)
        handler.wakeOn(Interest.Claim(IFACE), b)
        assertEquals(1 to 0, a.wakes to b.wakes, "`Claim`: B displaces A under the same interface")
        handler.wakeOn(Interest.Claim(iface2), third)
        assertEquals(1 to 0, b.wakes to third.wakes, "`Claim`: C displaces B under another interface of the kind")
        caller.command(IFACE, ORD, bytes(2))
        assertEquals(listOf(1, 1, 1), listOf(a, b, third).map { it.wakes }, "`Claim`: the call wakes C, the one waker stored")

        // A slot, on a new runtime whose table is full.
        rt = runtime()
        caller = caller(rt)
        rt.serve(IFACE, listOf(ORD))
        val calls = fill(rt)
        a = Count()
        b = Count()
        caller.wakeOn(Interest.Slot, a)
        caller.wakeOn(Interest.Slot, b)
        assertEquals(1 to 0, a.wakes to b.wakes, "`Slot`: B displaces A")
        rt.forget(calls[0])
        assertEquals(1 to 1, a.wakes to b.wakes, "`Slot`: the reclaim wakes B")
        caller.command(IFACE, ORD, bytes(1))
    }

    /**
     * A registration with the waker already stored, which a task makes on
     * every poll, is a refresh: it wakes nothing, whatever interface either key
     * names, and the change still wakes the task once. A refresh that woke the
     * task would schedule its next poll from every poll.
     */
    public fun `the same tasks registration is a refresh that wakes nothing`() {
        var rt = runtime()
        var caller = caller(rt)
        val source = source(rt)
        val handler = handler(rt)
        handler.serve(IFACE, listOf(ORD))
        source.subscribe(IFACE, listOf(ORD))

        // An outcome.
        val c = caller.command(IFACE, ORD, bytes(1))
        val claim = checkNotNull(handler.nextClaim(out(8)))
        var count = Count()
        caller.wakeOn(Interest.Outcome(c), count)
        caller.wakeOn(Interest.Outcome(c), count)
        assertEquals(0, count.wakes, "`Outcome`: a refresh wakes nothing")
        handler.settle(claim.id, ok())
        assertEquals(1, count.wakes, "`Outcome`: the settlement wakes the task")
        caller.forget(c)

        // An event, refreshed under another interface of the kind.
        count = Count()
        source.wakeOn(Interest.Event(iface2), count)
        source.wakeOn(Interest.Event(IFACE), count)
        assertEquals(0, count.wakes, "`Event`: a refresh wakes nothing")
        rt.raise(IFACE, ORD, bytes(1))
        assertEquals(1, count.wakes, "`Event`: the raise wakes the task")

        // A claim, refreshed under another interface of the kind.
        count = Count()
        handler.wakeOn(Interest.Claim(iface2), count)
        handler.wakeOn(Interest.Claim(IFACE), count)
        assertEquals(0, count.wakes, "`Claim`: a refresh wakes nothing")
        caller.command(IFACE, ORD, bytes(2))
        assertEquals(1, count.wakes, "`Claim`: the call wakes the task")

        // A slot, on a new runtime whose table is full.
        rt = runtime()
        caller = caller(rt)
        rt.serve(IFACE, listOf(ORD))
        val calls = fill(rt)
        count = Count()
        caller.wakeOn(Interest.Slot, count)
        caller.wakeOn(Interest.Slot, count)
        assertEquals(0, count.wakes, "`Slot`: a refresh wakes nothing")
        rt.forget(calls[0])
        assertEquals(1, count.wakes, "`Slot`: the reclaim wakes the task")
        caller.command(IFACE, ORD, bytes(1))
    }

    /**
     * A handle keeps one `Event` waker and one `Claim` waker, not one per
     * interface, so a change on any interface the handle observes wakes the
     * waker registered under another. A runtime that keeps a waker per key,
     * and wakes it only for that key, leaves both tasks here waiting.
     */
    public fun `one waker per kind is woken by a change on any interface of that kind`() {
        val rt = runtime()
        val source = source(rt)
        val handler = handler(rt)
        source.subscribe(iface2, listOf(ORD))
        handler.serve(iface2, listOf(ORD))

        val event = Count()
        source.wakeOn(Interest.Event(IFACE), event)
        assertEquals(0, event.wakes, "nothing has been raised")
        rt.raise(iface2, ORD, bytes(1))
        assertEquals(1, event.wakes, "an occurrence on interface 2 wakes it")

        val claim = Count()
        handler.wakeOn(Interest.Claim(IFACE), claim)
        assertEquals(0, claim.wakes, "no call is waiting")
        rt.command(iface2, ORD, bytes(1))
        assertEquals(1, claim.wakes, "a call on interface 2 wakes it")
    }

    /**
     * A waker that, when woken, calls back into the runtime through [probe]
     * and counts the wakes in which the call-back returned. Rust calls back on
     * the waking thread, where a runtime holding its lock deadlocks; a JVM
     * monitor is reentrant, so this call-back runs on another thread, which a
     * runtime holding its monitor blocks, and gives up after a second. It also
     * counts only a wake on [owner], the thread that made the change: a
     * runtime that wakes a waiter on a thread of its own fails the count.
     */
    private class CallsBack(private val owner: Thread, private val probe: () -> Unit) : Waker {
        private val count = AtomicInteger()

        val calledBack: Int get() = count.get()

        override fun wake() {
            if (Thread.currentThread() !== owner) return
            val call = CompletableFuture.runAsync(probe)
            if (runCatching { call.get(1, TimeUnit.SECONDS) }.isSuccess) count.incrementAndGet()
        }
    }

    /**
     * Reads through a new caller, a new source and a new handler of [rt]. The
     * handler serves an interface nothing is sent to, and the source
     * subscribes to nothing, so the reads find nothing and change nothing.
     */
    private fun probeOf(rt: R): () -> Unit {
        val caller = factory.caller(rt)
        val source = factory.source(rt)
        val handler = factory.handler(rt)
        handler.serve(InterfaceNo(99u), listOf(ORD))
        return {
            caller.ack(Correlation(0))
            source.next(out(8))
            handler.nextClaim(out(8))
        }
    }

    private fun assertCalledBack(count: CallsBack, path: String) {
        assertEquals(1, count.calledBack, "$path: the woken waker called back into the port")
    }

    /**
     * Every wake runs with the runtime's lock released: a waker that calls
     * back into the port when woken returns. Each wake path the tests above
     * use is taken once: a settlement, a raise, a command, a query, a
     * displacement, a forget that reclaims a slot, and the reclaim of a
     * forgotten call in flight.
     */
    public fun `every wake runs with the runtime lock released`() {
        val thread = Thread.currentThread()
        var rt = runtime()
        var caller = caller(rt)
        val source = source(rt)
        val handler = handler(rt)
        source.subscribe(IFACE, listOf(ORD))
        handler.serve(IFACE, listOf(ORD))
        var probe = probeOf(rt)

        val c = caller.command(IFACE, ORD, bytes(1))
        val claim = checkNotNull(handler.nextClaim(out(8)))
        var count = CallsBack(thread, probe)
        caller.wakeOn(Interest.Outcome(c), count)
        handler.settle(claim.id, ok())
        assertCalledBack(count, "a settlement")
        caller.forget(c)

        count = CallsBack(thread, probe)
        source.wakeOn(Interest.Event(IFACE), count)
        rt.raise(IFACE, ORD, bytes(1))
        assertCalledBack(count, "a raise")

        // Each call is claimed and settled before the next registration, so no
        // call is waiting when the handler registers.
        for (query in listOf(false, true)) {
            count = CallsBack(thread, probe)
            handler.wakeOn(Interest.Claim(IFACE), count)
            val sent = if (query) caller.query(IFACE, ORD, bytes(1)) else caller.command(IFACE, ORD, bytes(1))
            assertCalledBack(count, if (query) "a query" else "a command")
            handler.settle(checkNotNull(handler.nextClaim(out(8))).id, ok())
            caller.forget(sent)
        }

        count = CallsBack(thread, probe)
        handler.wakeOn(Interest.Claim(IFACE), count)
        handler.wakeOn(Interest.Claim(IFACE), Count())
        assertCalledBack(count, "a displacement")

        // A slot, on a new runtime whose table is full, with a probe of its own.
        rt = runtime()
        caller = caller(rt)
        probe = probeOf(rt)
        rt.serve(IFACE, listOf(ORD))
        val calls = fill(rt)

        count = CallsBack(thread, probe)
        caller.wakeOn(Interest.Slot, count)
        rt.forget(calls[0])
        assertCalledBack(count, "a forget that reclaims a slot")

        val taken = caller.command(IFACE, ORD, bytes(1))
        val held = checkNotNull(rt.nextClaim(out(8))) { "the call just sent" }
        // Registered before the forget, so the waker is woken by whichever of
        // the forget and the settlement reclaims the slot.
        count = CallsBack(thread, probe)
        caller.wakeOn(Interest.Slot, count)
        caller.forget(taken)
        rt.settle(held.id, ok())
        assertCalledBack(count, "a reclaim of a forgotten call in flight")
    }
}
