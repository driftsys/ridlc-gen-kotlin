// The waker and the blocking wait: the spelling of `core::task::Waker` and of
// `ridl_rt::task` (story E11.17), which Rust puts behind the `std` feature and
// the JVM always has.
//
// A Rust future is polled with a waker and answers `Pending` or `Ready`; its
// Kotlin spelling is a function that takes the waker and answers `null` or the
// value, which is what a polling read of a face already is.
package ridl.rt.task

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What wakes a task that found nothing: `core::task::Waker`. [wake] must not
 * block, and may be called from any thread, any number of times.
 *
 * Two wakers are the same task when they are the same object: that is
 * `Waker::will_wake`, which a `Wakeable` port reads to tell a task
 * registering again from another task.
 */
public fun interface Waker {
    /** Makes the task poll again. */
    public fun wake()
}

/**
 * A waker whose wake does nothing: `ridl_rt::task::noop_waker`. Each call is
 * a new object, so two are two tasks; a lambda that captures nothing would be
 * one shared instance.
 */
public fun noopWaker(): Waker = object : Waker {
    override fun wake() {}
}

/**
 * Runs [poll] on the current thread until it answers a value, parking the
 * thread between polls, and gives up at [deadline]:
 * `ridl_rt::task::block_on(fut, deadline)`, with the future last so it can
 * be a trailing lambda.
 *
 * [poll] is called once at once, then once more after every wake of the
 * waker it was given, which unparks this thread. Between polls the thread is
 * parked, so the wait costs no CPU. An unpark that arrives while the thread
 * is not parked is kept as a permit that ends the next park at once, so a
 * wake between a poll and the park after it is not lost. An unpark that is
 * not from the waker, or a park that ends on its own, costs one extra poll.
 *
 * - [deadline] `null`: waits until [poll] answers, however long that takes.
 * - Otherwise: returns the value of the first poll that answers one, whenever
 *   that poll runs, even after a park that ended late; returns `null` once a
 *   poll answers `null` at or after [deadline]. [poll] runs at least once,
 *   even when [deadline] has passed on entry. Each park is given at most the
 *   time left.
 *
 * The waker goes inert when the wait returns: a port may keep it and wake it
 * later, and that wake must not end an unrelated park of this thread.
 */
public fun <T : Any> blockOn(deadline: TimeSource.Monotonic.ValueTimeMark?, poll: (Waker) -> T?): T? {
    val thread = Thread.currentThread()
    val waiting = AtomicBoolean(true)
    val waker = Waker { if (waiting.get()) LockSupport.unpark(thread) }
    try {
        while (true) {
            poll(waker)?.let { return it }
            if (deadline == null) {
                LockSupport.park()
            } else {
                val left = -deadline.elapsedNow()
                if (left <= Duration.ZERO) return null
                LockSupport.parkNanos(left.inWholeNanoseconds)
            }
        }
    } finally {
        waiting.set(false)
    }
}
