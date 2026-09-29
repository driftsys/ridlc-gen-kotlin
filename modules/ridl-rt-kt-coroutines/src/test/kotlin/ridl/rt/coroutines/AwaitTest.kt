package ridl.rt.coroutines

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.contract.InterfaceNo
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.port.Wakeable
import ridl.rt.task.Waker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A [Wakeable] a test drives by hand: one waker per key, as the contract
 * states, and a log of every registration and read in the order they ran.
 */
private class Hand : Wakeable {
    val stored = ConcurrentHashMap<Interest, Waker>()
    val log = CopyOnWriteArrayList<String>()
    val registered = CopyOnWriteArrayList<Waker>()

    override fun wakeOn(what: Interest, waker: Waker) {
        log += "register $what"
        registered += waker
        stored[what] = waker
    }

    /** A change of [key]: wakes its stored waker once and clears it. */
    fun change(key: Interest) {
        stored.remove(key)?.wake()
    }
}

class AwaitTest {
    private val outcome = Interest.Outcome(Correlation(4))

    @Test
    fun `a value already known is returned after one registration and one read`() = runBlocking {
        val hand = Hand()
        assertEquals(7, await(hand, outcome) { hand.log += "read"; 7 })
        assertEquals(listOf("register $outcome", "read"), hand.log)
    }

    @Test
    fun `it registers under the interest it is given, before it reads`() = runBlocking {
        val hand = Hand()
        val event = Interest.Event(InterfaceNo(3u))
        val value = AtomicReference<Int?>(null)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            await(hand, event) { hand.log += "read"; value.get() }
        }
        value.set(1)
        hand.change(event)
        assertEquals(1, waiting.await())
        assertEquals(listOf("register $event", "read", "register $event", "read"), hand.log)
    }

    @Test
    fun `it suspends until a change of its key makes the read answer`() = runBlocking {
        val hand = Hand()
        val value = AtomicReference<Int?>(null)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { await(hand, outcome) { value.get() } }
        hand.change(Interest.Slot)
        yield()
        assertTrue(waiting.isActive, "a change of another key does not wake it")
        hand.change(outcome)
        yield()
        assertTrue(waiting.isActive, "a change with nothing new keeps it waiting")
        value.set(3)
        hand.change(outcome)
        assertEquals(3, waiting.await())
    }

    @Test
    fun `it registers the same waker on every poll, so a port sees one task`() = runBlocking {
        val hand = Hand()
        val reads = AtomicReference(0)
        await(hand, outcome) {
            reads.set(reads.get() + 1)
            if (reads.get() < 3) null.also { hand.change(outcome) } else "done"
        }
        assertEquals(3, hand.registered.size)
        assertTrue(hand.registered.all { it === hand.registered[0] }, "one waker for the whole wait")
    }

    @Test
    fun `a change reported on another thread wakes it`() = runBlocking {
        val hand = Hand()
        val value = AtomicReference<String?>(null)
        val waiting = async(Dispatchers.Default) { await(hand, outcome) { value.get() } }
        while (hand.stored[outcome] == null) yield()
        Thread {
            value.set("from another thread")
            hand.change(outcome)
        }.start()
        assertEquals("from another thread", withTimeout(5_000) { waiting.await() })
    }
}
