// An application against what `ridl build --plugin kotlin` writes for
// `cabin.ridl`, written as one outside this repository would be: it names the
// generated face and ridl-rt-kt-loopback, and nothing of the plugin that
// produced them. The Kotlin twin of the ridl repository's
// `examples/cabin/consumer`, and `just demo`'s program.
//
// Each line it prints carries the value its round trip carried, not a bare
// `ok`, so a codec or a face that returns a wrong value fails the demo's
// match. One loopback per round trip, so each stands on its own.
package ridl.sample.cabin

import ridl.rt.loopback.Loopback
import ridl.rt.sample.Provenance
import veh.cabin.Average
import veh.cabin.Cabin
import veh.cabin.CabinPollClient
import veh.cabin.CabinProvider
import veh.cabin.CabinPublisher
import veh.cabin.Health
import veh.cabin.Level
import veh.cabin.Temperature
import veh.cabin.Warning
import veh.cabin.Window
import java.nio.ByteBuffer

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

    // 1 — signal
    Loopback(Cabin.catalog).let { port ->
        CabinPublisher(port).apply {
            temperature(Temperature.of(21))
            commit()
        }
        val sample = CabinPollClient(port).temperature()
        check(sample.value == Temperature.of(21) && sample.provenance == Provenance.Live) { "signal: $sample" }
        lines += "signal ok ${sample.value.value}"
    }

    // 2 — event
    Loopback(Cabin.catalog).let { port ->
        val client = CabinPollClient(port)
        client.subscribeWarning()
        CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
        val event = client.nextEvent() as? Cabin.Event.Warning ?: error("event: no occurrence")
        val warning = event.occurrence.payload.getOrThrow()
        check(warning.health == Health.WARN) { "event: $warning" }
        lines += "event ok ${warning.code.value}"
    }

    // 3 — command
    Loopback(Cabin.catalog).let { port ->
        val client = CabinPollClient(port)
        val correlation = client.setLevel(Level.of(42))
        val provider = CabinService(average = 0)
        check(Cabin.dispatch(port, provider, ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE)) == 1) { "command: not dispatched" }
        check(client.setLevelAck(correlation) == Result.success(Unit)) { "command: not acknowledged" }
        lines += "command ok ${provider.levels.single()}"
    }

    // 4 — query
    Loopback(Cabin.catalog).let { port ->
        val client = CabinPollClient(port)
        val correlation = client.average(Window.of(10))
        val provider = CabinService(average = 7)
        check(Cabin.dispatch(port, provider, ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE)) == 1) { "query: not dispatched" }
        val reply = client.averageReply(correlation) ?: error("query: no reply")
        lines += "query ok ${reply.getOrThrow().value}"
    }
    return lines
}

fun main() {
    (demo() + coroutineDemo()).forEach(::println)
}
