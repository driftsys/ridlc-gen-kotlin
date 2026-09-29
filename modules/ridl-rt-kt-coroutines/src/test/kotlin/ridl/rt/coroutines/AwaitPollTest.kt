package ridl.rt.coroutines

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.task.Waker
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AwaitPollTest {
    @Test
    fun `a value the first poll answers is returned at once`() = runBlocking {
        assertEquals(7, awaitPoll({ error("not cancelled") }) { 7 })
    }

    @Test
    fun `it polls again when the waker is woken, with the same waker`() = runBlocking {
        val wakers = mutableListOf<Waker>()
        val value = AtomicReference<Int?>(null)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPoll({}) { waker -> wakers += waker; value.get() }
        }
        value.set(3)
        wakers.last().wake()
        assertEquals(3, waiting.await())
        assertEquals(2, wakers.size)
        assertTrue(wakers[0] === wakers[1], "one waker for the whole wait")
    }

    @Test
    fun `a cancelled wait calls cancel once and ends`() = runBlocking {
        val cancels = AtomicInteger()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitPoll<Int>({ cancels.incrementAndGet() }) { null } }
        yield()
        waiting.cancelAndJoin()
        assertEquals(1, cancels.get())
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun `an exception the poll throws is thrown and cancel is not called`() = runBlocking {
        val cancels = AtomicInteger()
        assertThrows<IllegalStateException> { runBlocking { awaitPoll<Int>({ cancels.incrementAndGet() }) { error("boom") } } }
        assertEquals(0, cancels.get())
    }

    /**
     * driftsys/ridl#568: a poll that wakes itself on every call, as a
     * bounded serve pass does, still lets the dispatcher run another
     * coroutine and is cancellable, on a single thread. Run on a thread of its
     * own, so a poll that never yields fails the join rather than hanging.
     */
    @Test
    fun `a poll that wakes itself yields its thread and can be cancelled`() {
        val ran = AtomicInteger()
        val polls = AtomicInteger()
        val runner = Thread {
            runBlocking {
                val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                    awaitPoll<Int>({}) { waker -> polls.incrementAndGet(); waker.wake(); null }
                }
                async { ran.incrementAndGet() }.await()
                waiting.cancelAndJoin()
            }
        }.apply { isDaemon = true; start() }
        runner.join(5_000)
        assertTrue(!runner.isAlive, "the self-waking poll held the thread")
        assertEquals(1, ran.get(), "the other coroutine ran")
        assertTrue(polls.get() >= 1)
    }
}
