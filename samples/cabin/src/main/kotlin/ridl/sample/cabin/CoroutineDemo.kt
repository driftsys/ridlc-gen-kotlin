// The cabin round trips again, suspended rather than blocked: the consumer
// on the calling coroutine, the provider on another thread with a handler
// handle of its own.
//
// Every wait is a call of `CabinAsyncClient`; the provider is
// `Cabin.serveAsync`.
package ridl.sample.cabin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ridl.rt.loopback.Loopback
import veh.cabin.Cabin
import veh.cabin.CabinAsyncClient
import veh.cabin.CabinPublisher
import veh.cabin.Health
import veh.cabin.Level
import veh.cabin.Warning
import veh.cabin.Window

/** The three round trips a consumer waits on, one line each. */
fun coroutineDemo(): List<String> = runBlocking {
    withTimeout(10_000) {
        val port = Loopback(Cabin.catalog)
        val client = CabinAsyncClient(port)
        val service = CabinService(average = 7)
        val handler = port.handler()
        val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, service) }

        val lines = mutableListOf<String>()

        lines += "coroutine query ok ${client.average(Window.of(10)).value}"

        // A command returns once it is delivered, settled before the
        // provider's method runs (ridl §6.1), so its return says nothing about
        // whether the method has run. What the method did is read once the
        // provider has stopped.
        client.setLevel(Level.of(42))

        client.subscribeWarning()
        CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
        val event = client.nextEvent() as Cabin.Event.Warning

        provider.cancelAndJoin()
        lines += "coroutine command ok ${service.levels.single()}"
        lines += "coroutine event ok ${event.occurrence.payload.getOrThrow().code.value}"
        lines
    }
}
