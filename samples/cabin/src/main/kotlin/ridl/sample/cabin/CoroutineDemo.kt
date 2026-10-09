// The cabin round trips again, suspended rather than blocked: the consumer
// on the calling coroutine, the provider on another thread with a handler
// handle of its own.
//
// Every wait is a call of `CabinAsyncClient`, and the signal is read as a
// shared state, polled in a scope of its own; the provider is
// `Cabin.serveAsync`.
package ridl.sample.cabin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ridl.rt.loopback.Loopback
import veh.cabin.Cabin
import veh.cabin.CabinAsyncClient
import veh.cabin.CabinPublisher
import veh.cabin.Health
import veh.cabin.Level
import veh.cabin.Temperature
import veh.cabin.Warning
import veh.cabin.Window
import veh.cabin.commit
import veh.cabin.nextEvent
import veh.cabin.subscribeWarning
import veh.cabin.temperature

/** A signal read as a state, then the three round trips a consumer waits on, one line each. */
fun coroutineDemo(): List<String> = runBlocking {
    // The scope the signal states poll in: off the calling thread, and
    // cancelled once the demo is done.
    val signals = CoroutineScope(Dispatchers.Default)
    try {
        rounds(signals)
    } finally {
        signals.cancel()
    }
}

private suspend fun rounds(signals: CoroutineScope): List<String> = withTimeout(10_000) {
    val port = Loopback(Cabin.catalog)
    val client = CabinAsyncClient(port, signals)
    val service = CabinService(average = 7)
    val handler = port.handler()
    val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, service) }

    val lines = mutableListOf<String>()

    // A signal as a state: the current sample at once, then each new
    // publication, polled at its rate floor while it is collected. The
    // second value is published once the first is seen.
    val publisher = CabinPublisher(port)
    publisher.temperature(Temperature.of(21))
    publisher.commit()
    val temperatures = client.temperature
        .onEach {
            if (it.value == Temperature.of(21)) {
                publisher.temperature(Temperature.of(22))
                publisher.commit()
            }
        }
        .take(2)
        .toList()
    lines += "coroutine signal ok ${temperatures.joinToString(",") { it.value.value.toString() }}"

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
