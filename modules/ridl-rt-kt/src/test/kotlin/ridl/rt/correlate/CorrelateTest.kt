package ridl.rt.correlate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.StreamNo
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.port.ClaimId
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.port.REPLY_STREAM
import ridl.rt.task.Waker
import java.util.concurrent.atomic.AtomicInteger

/**
 * `correlate.Table` and `correlate.Waiters`: the caller-side call table and
 * the per-handle waiter registry (ADR-0021 decision 15).
 * `crates/ridl-rt/tests/correlate.rs` of ridl `main` (story E11.18, eb41a7a),
 * case for case, except that the JVM has no reference count to show a waker
 * left the slot and no const context to build a table in.
 */
class CorrelateTest {
    /** A waker that counts its wakes. */
    private class Count : Waker {
        private val count = AtomicInteger()

        val wakes: Int get() = count.get()

        override fun wake() {
            count.incrementAndGet()
        }
    }

    /** Wakes what a table or registry operation handed back, as a runtime does after releasing its lock. */
    private fun wake(waker: Waker?) {
        waker?.wake()
    }

    private fun wake(wakers: List<Waker>) {
        wakers.forEach(Waker::wake)
    }

    private val refused = Result.failure<Unit>(Contract.PreconditionFailed)
    private val ok = Result.success(Unit)

    // -- Slots and generations. ---------------------------------------------

    @Test
    fun `a correlation is the generation above the slot index`() {
        val table = Table(4, null)
        val first = table.insert(0u)!!
        val second = table.insert(0u)!!
        assertEquals(0, Table.slot(first))
        assertEquals(1, Table.slot(second))
        assertEquals(first.value shr 16, second.value shr 16, "both slots are fresh")
    }

    @Test
    fun `a forgotten slot is reused under a new generation`() {
        val table = Table(1, null)
        val old = table.insert(0u)!!
        assertEquals(Settled.Recorded(null), table.settle(old, ok))
        assertEquals(Forgotten.Reclaimed, table.forget(old))

        val new = table.insert(0u)!!
        assertEquals(Table.slot(old), Table.slot(new), "same slot")
        assertNotEquals(old, new, "a new generation, so a new correlation")
    }

    @Test
    fun `a slot reclaimed twice does not accept a correlation from two reclaims ago`() {
        val table = Table(1, null)
        val issued = mutableListOf<Correlation>()
        repeat(3) {
            val c = table.insert(0u)!!
            issued += c
            table.settle(c, ok)
            if (issued.size < 3) table.forget(c)
        }
        assertNotEquals(issued[0], issued[2], "three generations of one slot")
        assertNull(table.outcome(issued[0]))
        assertNull(table.outcome(issued[1]))
        assertEquals(ok, table.outcome(issued[2]))
    }

    @Test
    fun `a generation mismatch answers none to the old correlation`() {
        val table = Table(1, null)
        val old = table.insert(0u)!!
        table.settle(old, refused)
        table.forget(old)
        val new = table.insert(0u)!!
        table.settle(new, ok)

        assertEquals(ok, table.outcome(new))
        assertNull(table.outcome(old), "the old generation is gone")
        assertEquals(Settled.Unknown, table.settle(old, refused), "a settlement under the old generation changes nothing")
        assertEquals(ok, table.outcome(new))
        assertEquals(Forgotten.Unknown, table.forget(old))
        assertEquals(ok, table.outcome(new), "nor does a forget")
    }

    @Test
    fun `a correlation the table never issued is unknown`() {
        val table = Table(2, null)
        val never = Correlation(1)
        assertNull(table.outcome(never))
        assertEquals(Settled.Unknown, table.settle(never, ok))
        assertEquals(Forgotten.Unknown, table.forget(never))
        assertNull(table.outcome(Correlation(7)), "a slot index past N")
        assertNotNull(table.insert(0u), "and both slots are still free")
        assertNotNull(table.insert(0u))
    }

    @Test
    fun `insert is none with n calls in flight and some after one is forgotten`() {
        val table = Table(3, null)
        val calls = List(3) { table.insert(0u)!! }
        assertNull(table.insert(0u), "every slot is in flight")

        table.settle(calls[1], ok)
        assertNull(table.insert(0u), "a settled slot is not free")
        assertEquals(Forgotten.Reclaimed, table.forget(calls[1]))
        assertEquals(1, Table.slot(table.insert(0u)!!))
    }

    @Test
    fun `outcome does not reclaim`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        assertNull(table.outcome(c), "in flight")
        table.settle(c, Result.failure(Transport.Busy))
        repeat(3) { assertEquals(Result.failure<Unit>(Transport.Busy), table.outcome(c)) }
        assertNull(table.insert(0u), "reading the outcome freed nothing")
    }

    @Test
    fun `the first settlement is the outcome and a second is unknown`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        assertEquals(Settled.Recorded(null), table.settle(c, ok))
        assertEquals(Settled.Unknown, table.settle(c, refused))
        assertEquals(ok, table.outcome(c))
    }

    @Test
    fun `a table holds at most 65536 slots`() {
        assertThrows<IllegalArgumentException> { Table(65_537, null) }
        Table(65_536, null)
    }

    // -- The byte budget. ---------------------------------------------------

    @Test
    fun `the budget refuses a reservation that does not fit and accepts it after a reclaim`() {
        val table = Table(4, 100u)
        val big = table.insert(60u)!!
        assertNull(table.insert(50u), "40 left, a slot free, no budget")
        val small = table.insert(40u)!!
        assertNull(table.insert(1u), "nothing left")

        table.settle(big, ok)
        assertNull(table.insert(50u), "a settlement credits nothing")
        table.forget(big)
        assertNotNull(table.insert(50u), "the reclaim credited 60")
        assertEquals(Forgotten.Marked(null), table.forget(small))
        assertNull(table.insert(11u), "10 left: a call in flight credits nothing when it is marked")
        assertEquals(Settled.Reclaimed, table.settle(small, ok))
        assertNotNull(table.insert(50u), "the settlement of the marked call credited its 40")
    }

    @Test
    fun `a reservation the budget refuses leaves its slot free`() {
        val table = Table(1, 10u)
        assertNull(table.insert(20u), "more than the whole budget")
        assertNotNull(table.insert(10u), "the one slot was not taken by the refused insert")
    }

    @Test
    fun `an insert refused for want of a slot debits no budget`() {
        val table = Table(1, 10u)
        val c = table.insert(4u)!!
        assertNull(table.insert(4u), "no slot is free")
        table.settle(c, ok)
        table.forget(c)
        assertNotNull(table.insert(10u), "the whole budget is free again: the refused insert debited nothing")
    }

    @Test
    fun `no budget admits any reservation`() {
        val table = Table(2, null)
        assertNotNull(table.insert(ULong.MAX_VALUE))
        assertNotNull(table.insert(ULong.MAX_VALUE))
    }

    // -- Reclaim: `forget` alone reclaims. ----------------------------------

    @Test
    fun `forget of a settled call reclaims at once`() {
        val table = Table(1, 10u)
        val c = table.insert(10u)!!
        table.settle(c, ok)
        assertEquals(Forgotten.Reclaimed, table.forget(c))
        assertNull(table.outcome(c))
        assertNotNull(table.insert(10u), "the slot and its bytes are free")
    }

    @Test
    fun `forget of a call in flight marks it and the settlement then reclaims`() {
        val table = Table(1, 10u)
        val c = table.insert(10u)!!
        assertEquals(Forgotten.Marked(null), table.forget(c))
        assertNull(table.outcome(c), "a forgotten call has no outcome")
        assertNull(table.insert(1u), "the slot is still held")
        assertEquals(Forgotten.Unknown, table.forget(c), "a second forget finds nothing to release")

        assertEquals(Settled.Reclaimed, table.settle(c, ok))
        assertNull(table.outcome(c), "the settlement is not readable")
        assertNotNull(table.insert(10u), "the slot and its bytes are free")
    }

    // -- The `Outcome` waker kept in the slot. -------------------------------

    @Test
    fun `settle returns the stored waker once`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val count = Count()
        assertNull(table.wakeOn(c, count), "nothing to displace")

        val settled = assertInstanceOf(Settled.Recorded::class.java, table.settle(c, ok))
        wake(settled.waker)
        assertEquals(1, count.wakes)
        assertEquals(Settled.Unknown, table.settle(c, ok), "the waker was handed back once and is no longer stored")
        table.forget(c)
        assertEquals(1, count.wakes)
    }

    @Test
    fun `wake on returns the displaced waker of another task`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val first = Count()
        val second = Count()
        assertNull(table.wakeOn(c, first))
        wake(table.wakeOn(c, second))
        assertEquals(1, first.wakes, "the displaced waker is handed back")

        wake((table.settle(c, ok) as Settled.Recorded).waker)
        assertEquals(1 to 1, first.wakes to second.wakes, "the new one is kept")
    }

    @Test
    fun `wake on of the same task is a refresh and returns nothing`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val count = Count()
        assertNull(table.wakeOn(c, count))
        assertNull(table.wakeOn(c, count), "the same waker displaces nothing")
        wake((table.settle(c, ok) as Settled.Recorded).waker)
        assertEquals(1, count.wakes, "the refreshed waker is still woken")
    }

    @Test
    fun `wake on hands the waker back at once when no outcome is still to come`() {
        val table = Table(2, null)
        val count = Count()

        val settled = table.insert(0u)!!
        table.settle(settled, ok)
        wake(table.wakeOn(settled, count))
        assertEquals(1, count.wakes, "the outcome is already known")

        val forgotten = table.insert(0u)!!
        table.forget(forgotten)
        wake(table.wakeOn(forgotten, count))
        assertEquals(2, count.wakes, "a forgotten call's outcome is never read")

        wake(table.wakeOn(Correlation(1L shl 16), count))
        assertEquals(3, count.wakes, "a correlation the table does not hold")

        // None of the three was stored: a settlement and a reclaim hand back no waker.
        assertEquals(Settled.Reclaimed, table.settle(forgotten, ok))
        assertEquals(Forgotten.Reclaimed, table.forget(settled))
        assertEquals(3, count.wakes)
    }

    @Test
    fun `forget of a call in flight hands back its waker`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val count = Count()
        table.wakeOn(c, count)
        val marked = assertInstanceOf(Forgotten.Marked::class.java, table.forget(c))
        wake(marked.waker)
        assertEquals(1, count.wakes, "no outcome will ever be readable for it")
        assertEquals(Settled.Reclaimed, table.settle(c, ok))
        assertEquals(1, count.wakes, "the reclaim hands back nothing more")
    }

    // -- The `Stream` wakers kept in the slot. -------------------------------

    private val param = StreamNo(1u)

    @Test
    fun `a stream waker is kept per slot and stream and handed back by settle`() {
        val table = Table(2, null)
        val c = table.insert(0u)!!
        val reply = Count()
        val paramCount = Count()
        assertNull(table.wakeOnStream(c, REPLY_STREAM, reply))
        assertNull(
            table.wakeOnStream(c, param, paramCount),
            "another stream of the call is another key: nothing is displaced",
        )

        assertEquals(Settled.Recorded(null), table.settle(c, ok))
        wake(table.takeStreams(Table.slot(c)))
        assertEquals(1 to 1, reply.wakes to paramCount.wakes)
        assertEquals(0, table.takeStreams(Table.slot(c)).size)
    }

    @Test
    fun `take stream takes one streams waker and leaves the others`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val reply = Count()
        val paramCount = Count()
        table.wakeOnStream(c, REPLY_STREAM, reply)
        table.wakeOnStream(c, param, paramCount)

        wake(table.takeStream(c, param))
        assertEquals(0 to 1, reply.wakes to paramCount.wakes)
        assertNull(table.takeStream(c, param), "taking clears it")
        wake(table.takeStream(c, REPLY_STREAM))
        assertEquals(1 to 1, reply.wakes to paramCount.wakes)
    }

    @Test
    fun `a stream waker is handed back by forget of a call in flight`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val count = Count()
        table.wakeOnStream(c, REPLY_STREAM, count)

        assertEquals(Forgotten.Marked(null), table.forget(c))
        wake(table.takeStreams(Table.slot(c)))
        assertEquals(1, count.wakes, "every stream of a forgotten call stops")

        wake(table.wakeOnStream(c, REPLY_STREAM, count))
        assertEquals(2, count.wakes, "a registration on a forgotten call is handed back, not stored")
        assertEquals(Settled.Reclaimed, table.settle(c, ok))
        assertEquals(0, table.takeStreams(Table.slot(c)).size)
    }

    @Test
    fun `a stream waker is handed back by the forget that reclaims a settled call`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        assertEquals(Settled.Recorded(null), table.settle(c, ok))
        val count = Count()
        assertNull(
            table.wakeOnStream(c, param, count),
            "a settled call's streams go on: a command's parameter stream outlives its acknowledgment",
        )

        assertEquals(Forgotten.Reclaimed, table.forget(c))
        wake(table.takeStreams(Table.slot(c)))
        assertEquals(1, count.wakes)
    }

    @Test
    fun `a reclaimed slot holds no stream waker of its previous call`() {
        val table = Table(1, null)
        val old = table.insert(0u)!!
        val count = Count()
        table.settle(old, ok)
        table.wakeOnStream(old, REPLY_STREAM, count)
        // The runtime does not take the reclaimed slot's stream wakers here.
        assertEquals(Forgotten.Reclaimed, table.forget(old))

        val new = table.insert(0u)!!
        assertEquals(Table.slot(old), Table.slot(new))
        assertNull(table.takeStream(new, REPLY_STREAM))
        assertEquals(0, table.takeStreams(Table.slot(new)).size)
        assertNull(table.takeStream(old, REPLY_STREAM), "the old correlation is unknown")
    }

    @Test
    fun `take stream on a stale correlation leaves the new calls waker in place`() {
        val table = Table(1, null)
        val old = table.insert(0u)!!
        table.settle(old, ok)
        assertEquals(Forgotten.Reclaimed, table.forget(old))
        val new = table.insert(0u)!!
        assertNull(table.wakeOnStream(new, REPLY_STREAM, Count()))
        assertNull(table.takeStream(old, REPLY_STREAM), "a stale correlation takes nothing from the slot it once had")
        assertNotNull(table.takeStream(new, REPLY_STREAM), "the new call's waker is still stored")
    }

    @Test
    fun `take streams of a slot the table does not have yields nothing`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        assertNull(table.wakeOnStream(c, REPLY_STREAM, Count()))
        assertEquals(0, table.takeStreams(1).size)
        assertEquals(0, table.takeStreams(Int.MAX_VALUE).size)
        assertEquals(1, table.takeStreams(Table.slot(c)).size, "the slot the table has still holds its waker")
    }

    @Test
    fun `registering the same task on a stream is a refresh that hands back nothing`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val count = Count()
        assertNull(table.wakeOnStream(c, REPLY_STREAM, count))
        assertNull(table.wakeOnStream(c, REPLY_STREAM, count))
        assertNull(table.wakeOnStream(c, REPLY_STREAM, count), "the same object wakes the same task")
        assertEquals(0, count.wakes)

        val otherCount = Count()
        wake(table.wakeOnStream(c, REPLY_STREAM, otherCount))
        assertEquals(
            1 to 0,
            count.wakes to otherCount.wakes,
            "another task displaces the stored waker, which is handed back",
        )
    }

    @Test
    fun `a stream registration on an unknown call is handed back`() {
        val table = Table(1, null)
        val count = Count()
        wake(table.wakeOnStream(Correlation(0), REPLY_STREAM, count))
        assertEquals(1, count.wakes)
        assertNull(table.takeStream(Correlation(0), REPLY_STREAM))
    }

    @Test
    fun `a stream past the slots capacity is handed back unstored`() {
        val table = Table(1, null)
        val c = table.insert(0u)!!
        val count = Count()
        for (no in 0 until STREAM_WAKERS) {
            assertNull(table.wakeOnStream(c, StreamNo(no.toUByte()), count))
        }
        val past = StreamNo(STREAM_WAKERS.toUByte())
        wake(table.wakeOnStream(c, past, count))
        assertEquals(1, count.wakes, "with no cell free the waker is woken at once, as a spurious wake")
        assertNull(table.takeStream(c, past))
        wake(table.takeStream(c, REPLY_STREAM))
        assertNull(table.wakeOnStream(c, past, count), "a cell freed by a take is reused")
    }

    // -- `Waiters`. ----------------------------------------------------------

    private val iface = InterfaceNo(1u)
    private val other = InterfaceNo(2u)

    @Test
    fun `waiters hold one waker per kind`() {
        val waiters = Waiters()
        val slot = Count()
        val event = Count()
        val claim = Count()
        assertNull(waiters.register(Interest.Slot, slot))
        assertNull(waiters.register(Interest.Event(iface), event))
        assertNull(waiters.register(Interest.Claim(iface), claim))
        assertEquals(listOf(0, 0, 0), listOf(slot, event, claim).map { it.wakes }, "three kinds, three wakers")

        wake(waiters.take(Interest.Event(iface)))
        assertEquals(listOf(0, 1, 0), listOf(slot, event, claim).map { it.wakes })
        wake(waiters.take(Interest.Slot))
        assertEquals(listOf(1, 1, 0), listOf(slot, event, claim).map { it.wakes })
        wake(waiters.take(Interest.Claim(iface)))
        assertEquals(listOf(1, 1, 1), listOf(slot, event, claim).map { it.wakes })
    }

    @Test
    fun `any key of the kind takes the waker`() {
        val waiters = Waiters()
        val count = Count()
        waiters.register(Interest.Event(iface), count)
        wake(waiters.take(Interest.Event(other)))
        assertEquals(1, count.wakes, "registered under 1, taken under 2")

        waiters.register(Interest.Claim(other), count)
        wake(waiters.take(Interest.Claim(iface)))
        assertEquals(2, count.wakes)
    }

    @Test
    fun `a registration under another key of the kind by the same task is a refresh`() {
        val waiters = Waiters()
        val count = Count()
        waiters.register(Interest.Event(iface), count)
        assertNull(waiters.register(Interest.Event(other), count), "one waker per kind, and the same task")
        wake(waiters.take(Interest.Event(iface)))
        assertEquals(1, count.wakes, "the refreshed waker is still stored")
    }

    @Test
    fun `register returns the displaced waker of another task`() {
        val waiters = Waiters()
        val first = Count()
        val second = Count()
        waiters.register(Interest.Claim(iface), first)
        wake(waiters.register(Interest.Claim(other), second))
        assertEquals(1 to 0, first.wakes to second.wakes)
        wake(waiters.take(Interest.Claim(iface)))
        assertEquals(1 to 1, first.wakes to second.wakes, "the new one is kept")
    }

    @Test
    fun `take clears the kind`() {
        val waiters = Waiters()
        val count = Count()
        waiters.register(Interest.Slot, count)
        assertNotNull(waiters.take(Interest.Slot))
        assertNull(waiters.take(Interest.Slot), "taken once")
        assertNull(waiters.register(Interest.Slot, count), "nothing is stored to displace")
        assertEquals(0, count.wakes)
    }

    @Test
    fun `an outcome key is not stored and is handed back to be woken`() {
        val waiters = Waiters()
        val count = Count()
        wake(waiters.register(Interest.Outcome(Correlation(0)), count))
        assertEquals(1, count.wakes, "the table holds `Outcome`, not `Waiters`")
        assertNull(waiters.take(Interest.Outcome(Correlation(0))))
        assertEquals(0, waiters.takeAll().size, "nothing was stored")
    }

    @Test
    fun `a stream key is not stored and is handed back to be woken`() {
        val waiters = Waiters()
        val count = Count()
        wake(waiters.register(Interest.Stream(Correlation(0), REPLY_STREAM), count))
        wake(waiters.register(Interest.ClaimStream(ClaimId(0), REPLY_STREAM), count))
        assertEquals(2, count.wakes, "a stream's waker is kept with its call, not in `Waiters`")
        assertNull(waiters.take(Interest.Stream(Correlation(0), REPLY_STREAM)))
        assertEquals(0, waiters.takeAll().size, "nothing was stored")
    }

    @Test
    fun `take all takes every kind for an unkeyed runtime`() {
        val waiters = Waiters()
        val count = Count()
        val otherCount = Count()
        waiters.register(Interest.Slot, count)
        waiters.register(Interest.Event(iface), otherCount)
        waiters.register(Interest.Claim(iface), count)
        waiters.takeAll().forEach(Waker::wake)
        assertEquals(2 to 1, count.wakes to otherCount.wakes)
        assertEquals(0, waiters.takeAll().size, "every kind is cleared")
    }

    @Test
    fun `take all clears every kind when called even if its result is dropped`() {
        val waiters = Waiters()
        val count = Count()
        val otherCount = Count()
        waiters.register(Interest.Slot, count)
        waiters.register(Interest.Claim(iface), count)
        waiters.takeAll()
        assertNull(waiters.register(Interest.Slot, otherCount), "nothing was left to displace")
        assertNull(waiters.register(Interest.Claim(iface), otherCount))
        assertEquals(0 to 0, count.wakes to otherCount.wakes)
    }
}
