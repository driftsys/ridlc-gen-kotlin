// The cabin round trips again, suspended rather than polled: the consumer
// on the calling coroutine, the provider on another thread with a handler
// handle of its own, each waiting through ridl-rt-kt-coroutines' `await` for
// what the loopback reports as changed (docs/design.md §5, O-K3).
//
// Every wait is `await(port, <interest>) { <a polling read of the face> }` at
// the call site, woken by the handle the read goes through under the key its
// answer changes under: the face stays the polling one (D-K6), and a call's
// suspending form is its send and one `await`, with no extension written or
// generated for it.
package ridl.sample.cabin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ridl.rt.coroutines.await
import ridl.rt.loopback.Loopback
import ridl.rt.port.Interest
import veh.cabin.Cabin
import veh.cabin.CabinPollClient
import veh.cabin.CabinPublisher
import veh.cabin.Health
import veh.cabin.Level
import veh.cabin.Warning
import veh.cabin.Window
import java.nio.ByteBuffer
import java.util.Collections

/** The three round trips a consumer waits on, one line each. */
fun coroutineDemo(): List<String> = runBlocking {
    withTimeout(10_000) {
        val port = Loopback(Cabin.catalog)
        val client = CabinPollClient(port)
        val service = CabinService(average = 7)
        val levels = Collections.synchronizedList(mutableListOf<Long>())

        // The provider: one dispatch pass per change, on another thread.
        val provider = launch(Dispatchers.Default) {
            val handler = port.handler()
            val buffer = ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE)
            while (isActive) {
                await(handler, Interest.Claim(Cabin.number)) {
                    Cabin.dispatch(handler, service, buffer).takeIf { it > 0 }
                }
                levels.addAll(service.levels.drain())
            }
        }

        val lines = mutableListOf<String>()

        val average = client.average(Window.of(10))
        lines += "coroutine query ok ${await(port, Interest.Outcome(average.correlation)) { client.averageReply(average) }.getOrThrow().value}"

        // A command's acknowledgment is a delivery acknowledgment, settled
        // before the provider's method runs (ridl §6.1), so it says nothing
        // about whether the method has run. What the method did is read once
        // the provider has stopped: cancellation takes effect at its next
        // `await`, after the dispatch pass that called the method.
        val set = client.setLevel(Level.of(42))
        await(port, Interest.Outcome(set.correlation)) { client.setLevelAck(set) }.getOrThrow()

        client.subscribeWarning()
        CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
        val event = await(port, Interest.Event(Cabin.number)) { client.nextEvent() } as Cabin.Event.Warning

        provider.cancelAndJoin()
        lines += "coroutine command ok ${levels.single()}"
        lines += "coroutine event ok ${event.occurrence.payload.getOrThrow().code.value}"
        lines
    }
}

/** The recorded levels, removed. */
private fun MutableList<Long>.drain(): List<Long> = synchronized(this) { toList().also { clear() } }
