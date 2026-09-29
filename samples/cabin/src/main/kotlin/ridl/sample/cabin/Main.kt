// An application against what `ridl build --plugin kotlin` writes for
// `cabin.ridl`, written as one outside this repository would be: it names the
// generated clients, `Cabin.serve` and ridl-rt-kt-loopback, and nothing of the
// plugin that produced them. The Kotlin twin of the ridl repository's
// `examples/cabin/consumer`, and `just demo`'s program.
//
// Each line it prints carries the value its round trip carried, not a bare
// `ok`, so a codec or a face that returns a wrong value fails the demo's
// match. One loopback carries the four round trips; the provider serves it on
// a thread of its own.
package ridl.sample.cabin

import ridl.rt.loopback.Loopback
import ridl.rt.sample.Provenance
import veh.cabin.Average
import veh.cabin.Cabin
import veh.cabin.CabinClient
import veh.cabin.CabinProvider
import veh.cabin.CabinPublisher
import veh.cabin.Health
import veh.cabin.Level
import veh.cabin.Temperature
import veh.cabin.Warning
import veh.cabin.Window
import veh.cabin.commit
import veh.cabin.nextEvent
import veh.cabin.subscribeWarning
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The provider: records the levels it is set to and answers every average with [average]. */
class CabinService(private val average: Long) : CabinProvider {
    val levels: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

    override fun setLevel(level: Level) {
        levels += level.value
    }

    override fun average(window: Window): Average = Average.of(average)
}

/** The four round trips, one line each. Every check throws when a round trip does not hold. */
fun demo(): List<String> {
    val lines = mutableListOf<String>()
    val port = Loopback(Cabin.catalog)
    val handler = port.handler()
    val service = CabinService(average = 7)
    // serve returns at each short timeout, so the flag stops the provider within one.
    val running = AtomicBoolean(true)
    val provider = thread(name = "cabin-provider") { while (running.get()) Cabin.serve(handler, service, 100.milliseconds) }
    val client = CabinClient(port, timeout = 5.seconds)

    // 1 — signal
    CabinPublisher(port).apply {
        temperature(Temperature.of(21))
        commit()
    }
    val sample = client.temperature()
    check(sample.value == Temperature.of(21) && sample.provenance == Provenance.Live) { "signal: $sample" }
    lines += "signal ok ${sample.value.value}"

    // 2 — event
    client.subscribeWarning()
    CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
    val event = client.nextEvent() as? Cabin.Event.Warning ?: error("event: no occurrence")
    val warning = event.occurrence.payload.getOrThrow()
    check(warning.health == Health.WARN) { "event: $warning" }
    lines += "event ok ${warning.code.value}"

    // 3 — command, 4 — query. A command returns once it is delivered, before
    // the provider's method runs (ridl §6.1); the provider runs its claims in
    // order on its one thread, so once the query has replied the command's
    // method has run.
    client.setLevel(Level.of(42))
    val average = client.average(Window.of(10))
    lines += "command ok ${service.levels.single()}"
    lines += "query ok ${average.value}"

    running.set(false)
    provider.join()
    return lines
}

fun main() {
    (demo() + coroutineDemo()).forEach(::println)
}
