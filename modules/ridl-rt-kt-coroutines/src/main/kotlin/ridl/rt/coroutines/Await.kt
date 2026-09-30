// The coroutine adapter over the polling face (docs/design.md §5, O-K3; D-K6):
// the face stays the polling one — a send returns a correlation, an outcome
// is `null` until it is known — and suspension is this one function over the
// `Wakeable` extension of ridl-rt-kt, not a second face.
package ridl.rt.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield
import ridl.rt.port.Interest
import ridl.rt.port.Wakeable
import ridl.rt.task.Waker

/**
 * Suspends until [poll] answers a value, and returns it, woken by [port]
 * under [interest].
 *
 * [poll] is any polling read of a face, with the key its answer changes
 * under: `client.averageReply(c)` under `Interest.Outcome(c.correlation)`,
 * `client.nextEvent()` under `Interest.Event(Cabin.number)`, or a provider's
 * `Cabin.dispatch(...)` mapped to `null` when it settled nothing, under
 * `Interest.Claim(Cabin.number)`. It must not block.
 *
 * Each round registers, then reads, as the `Wakeable` contract requires, so
 * an outcome that lands between the read and the suspension still wakes it.
 * One waker serves the whole wait, so the port sees one task registering
 * again. Bound the wait with `withTimeout`: nothing here times out, as
 * nothing in a port does. It is [awaitPoll] with a poll that registers
 * [interest] first.
 */
public suspend fun <T : Any> await(port: Wakeable, interest: Interest, poll: () -> T?): T =
    awaitPoll({}) { waker ->
        port.wakeOn(interest, waker)
        poll()
    }

/**
 * Suspends until [poll] answers a value, and returns it: the suspending twin
 * of `ridl.rt.task.blockOn`. [poll] is called once at once, then again each
 * time the waker it was given is woken; one waker serves the whole wait, so a
 * port sees one task registering again. [poll] registers its own interest
 * before it reads, as the `Wakeable` contract requires. A wake that lands
 * during a poll yields the coroutine once before the next poll, whoever woke
 * it: a poll that wakes itself, as `serveAsync` does after a bounded pass,
 * never holds its dispatcher, and a client whose outcome or event lands while
 * its own poll runs reads it one dispatch later, after the yield. An exception
 * [poll] throws ends the wait. When the coroutine is cancelled while waiting,
 * [cancel] runs once and the cancellation is rethrown.
 */
public suspend fun <T : Any> awaitPoll(cancel: () -> Unit, poll: (Waker) -> T?): T {
    val woken = Channel<Unit>(Channel.CONFLATED)
    val waker = Waker { woken.trySend(Unit) }
    while (true) {
        poll(waker)?.let { return it }
        try {
            // A wake during the poll, from a poll that stopped at a bound and
            // woke itself or from a value landing while a client polled,
            // yields once rather than polling again at once: the dispatcher's
            // other coroutines run, and cancellation is seen.
            if (woken.tryReceive().isSuccess) yield() else woken.receive()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }
}
