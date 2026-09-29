package ridl.rt.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * `blockOn` and `noopWaker`, the Kotlin spelling of `ridl_rt::task` (story
 * E11.17): the cases of `crates/ridl-rt/tests/task.rs`. A wait parks the
 * thread between polls, so a lost wake parks it forever; every wait with no
 * deadline runs under a timeout that turns that into a failure.
 */
class TaskTest {
    /** Pending until [done] is set; keeps the waker of its last poll so another thread can wake it, and counts polls. */
    private class Pending<T : Any>(private val value: T) {
        val done = AtomicBoolean(false)
        val waker = AtomicReference<Waker?>(null)
        val polls = AtomicInteger()

        fun poll(w: Waker): T? {
            polls.incrementAndGet()
            waker.set(w)
            return if (done.get()) value else null
        }

        /** From another thread, after [delayMs]: completes the future and wakes the waker of its last poll. */
        fun completeLater(delayMs: Long): Thread = Thread {
            Thread.sleep(delayMs)
            done.set(true)
            waker.get()!!.wake()
        }.also { it.start() }
    }

    private val limit = 5.seconds.toJavaDuration()

    @Test
    fun `blockOn returns the output when another thread wakes the future`() {
        val future = Pending(7)
        val result = assertTimeoutPreemptively(limit) {
            future.completeLater(50)
            blockOn(null, future::poll)
        }
        assertEquals(7, result)
        assertTrue(future.polls.get() <= 8, "a parked wait polls on a wake, not in a loop: ${future.polls.get()}")
    }

    @Test
    fun `blockOn with a far deadline returns the output when woken before it`() {
        val future = Pending("ready")
        future.completeLater(20)
        assertEquals("ready", blockOn(TimeSource.Monotonic.markNow() + 5.seconds, future::poll))
    }

    @Test
    fun `a wake during a poll is not lost`() {
        val polls = AtomicInteger()
        val result = assertTimeoutPreemptively(limit) {
            blockOn(null) { waker ->
                if (polls.incrementAndGet() == 1) {
                    waker.wake()
                    null
                } else {
                    "second"
                }
            }
        }
        assertEquals("second", result)
        assertEquals(2, polls.get())
    }

    @Test
    fun `a wake between a poll and the park is not lost without a deadline`() {
        val polls = AtomicInteger()
        val result = assertTimeoutPreemptively(limit) {
            blockOn(null) { waker ->
                if (polls.incrementAndGet() == 1) {
                    // The wake comes from another thread and is over before the poll returns.
                    Thread { waker.wake() }.apply { start() }.join()
                    null
                } else {
                    "woken"
                }
            }
        }
        assertEquals("woken", result)
    }

    @Test
    fun `blockOn returns null when the deadline passes`() {
        val start = TimeSource.Monotonic.markNow()
        val polls = AtomicInteger()
        val result = blockOn<Int>(start + 50.milliseconds) {
            polls.incrementAndGet()
            null
        }
        assertNull(result)
        val elapsed = start.elapsedNow()
        assertTrue(elapsed >= 50.milliseconds, "not before the deadline: $elapsed")
        assertTrue(elapsed < 550.milliseconds, "each park is bounded by the time left: $elapsed")
        assertTrue(polls.get() <= 8, "a timed wait parks rather than spinning: ${polls.get()}")
    }

    @Test
    fun `blockOn polls once even when the deadline has already passed`() {
        val passed = TimeSource.Monotonic.markNow() - 1.seconds
        val polls = AtomicInteger()
        assertEquals(3, blockOn(passed) { polls.incrementAndGet(); 3 })
        assertEquals(1, polls.get())
        assertNull(blockOn<Int>(passed) { polls.incrementAndGet(); null })
        assertEquals(2, polls.get(), "a pending poll past the deadline gives up without another")
    }

    @Test
    fun `blockOn returns the output of a ready poll after the deadline`() {
        val deadline = TimeSource.Monotonic.markNow() + 30.milliseconds
        // Ready only once the deadline is reached: the first poll is pending, the one after the park is ready.
        assertEquals("late", blockOn(deadline) { if (deadline.hasPassedNow()) "late" else null })
    }

    @Test
    fun `a spurious unpark does not end the wait early`() {
        val start = TimeSource.Monotonic.markNow()
        val polls = AtomicInteger()
        val waiter = Thread.currentThread()
        val stop = AtomicBoolean(false)
        val nagger = Thread {
            while (!stop.get()) {
                LockSupport.unpark(waiter)
                Thread.sleep(5)
            }
        }.apply { start() }
        try {
            assertNull(blockOn<Int>(start + 100.milliseconds) { polls.incrementAndGet(); null })
        } finally {
            stop.set(true)
            nagger.join()
        }
        assertTrue(start.elapsedNow() >= 100.milliseconds, "the wait lasts until the deadline")
        assertTrue(polls.get() >= 3, "an unpark from elsewhere costs a poll: ${polls.get()}")
    }

    @Test
    fun `a noop waker can be woken without effect and is not another noop waker`() {
        val waker = noopWaker()
        waker.wake()
        waker.wake()
        assertNotSame(waker, noopWaker(), "two noop wakers are two tasks, as `will_wake` tells them apart")
    }

    @Test
    fun `waking a noop waker from another thread does not poll the future`() {
        val noop = noopWaker()
        val stop = AtomicBoolean(false)
        val waker = Thread {
            while (!stop.get()) {
                noop.wake()
                Thread.sleep(5)
            }
        }.apply { start() }
        val polls = AtomicInteger()
        try {
            assertNull(blockOn<Int>(TimeSource.Monotonic.markNow() + 100.milliseconds) { polls.incrementAndGet(); null })
        } finally {
            stop.set(true)
            waker.join()
        }
        assertTrue(polls.get() <= 3, "only the first poll and the deadline's: ${polls.get()}")
    }

    // JVM only: Rust drops a waker with its task, while a port may keep the
    // waker of a finished wait and wake it later.
    @Test
    fun `a waker woken after its wait has returned leaves no permit on the thread`() {
        val kept = AtomicReference<Waker?>(null)
        assertEquals(1, blockOn(null) { w -> kept.set(w); 1 })
        kept.get()!!.wake()
        val start = TimeSource.Monotonic.markNow()
        LockSupport.parkNanos(200.milliseconds.inWholeNanoseconds)
        assertTrue(start.elapsedNow() >= 150.milliseconds, "a stale wake ended an unrelated park at once: ${start.elapsedNow()}")
    }
}
