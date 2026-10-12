// #78: what `SignalState.value` costs while the state polls, against a read.
//
// - `value`: the last sample polled, behind an age guard: the poller check, a
//   clock read against when the poller last read, and two volatile reads.
//   Before #78 it was the poller check and one volatile read, 0.5 ns here.
// - `read`: what `value` would cost if it always read, `SignalState.read()`:
//   a port read, a byte compare, a decode when the publication changed, and a
//   publication.
//
// Each runs over the loopback and over `SeqlockPort`, with the state polled by
// a collector on `Dispatchers.Default` at the signal's 10 ms floor, as an app
// would. `clock` and `portRead` are the floors `value` and `read` build on;
// the `Changed` benchmarks publish a new value before each read, so
// `readChanged − publishChanged` is a read that decodes.
package ridl.sample.cabin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import ridl.rt.contract.Ordinal
import ridl.rt.coroutines.SignalState
import ridl.rt.loopback.Loopback
import ridl.rt.port.RawSample
import ridl.rt.port.SignalReader
import ridl.rt.sample.Provenance
import ridl.rt.sample.Sample
import veh.cabin.Cabin
import veh.cabin.CabinAsyncClient
import veh.cabin.CabinPublisher
import veh.cabin.Temperature
import veh.cabin.TemperatureCodec
import veh.cabin.commit
import veh.cabin.temperature
import java.nio.ByteBuffer

@State(Scope.Benchmark)
open class SignalStateBench {
    @Param("loopback", "seqlock")
    lateinit var runtime: String

    private lateinit var scope: CoroutineScope
    private lateinit var port: SignalReader
    private lateinit var state: SignalState<Temperature>
    private lateinit var publishNext: () -> Unit
    private val writer = Any()

    @Setup(Level.Trial)
    fun setup() {
        val loopback = Loopback(Cabin.catalog)
        val publisher = CabinPublisher(loopback)
        // The two values the `Changed` benchmarks alternate, as the reader sees their bytes.
        val encoded = listOf(21L, 22L).map {
            publisher.temperature(Temperature.of(it))
            publisher.commit()
            val buf = ByteBuffer.allocate(TemperatureCodec.maxSize)
            val raw = loopback.read(Cabin.number, TEMPERATURE, buf)
            ByteArray(raw.len) { i -> buf.get(i) }
        }
        var next = 0L
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        when (runtime) {
            "loopback" -> {
                publisher.temperature(Temperature.of(21))
                publisher.commit()
                port = loopback
                state = CabinAsyncClient(loopback, scope).temperature
                publishNext = {
                    publisher.temperature(Temperature.of(21 + next))
                    publisher.commit()
                    next = 1L - next
                }
            }
            "seqlock" -> {
                val seqlock = SeqlockPort(loopback)
                seqlock.publish(encoded[0])
                port = seqlock
                state = CabinAsyncClient(seqlock, scope).temperature
                publishNext = {
                    next = 1L - next
                    seqlock.publish(encoded[next.toInt()])
                }
            }
            else -> error("unknown runtime $runtime")
        }
        scope.launch { state.collect {} }
        // The first collector's read is done and the poller is on its slots.
        Thread.sleep(100)
        val sample = state.read()
        check(sample.provenance == Provenance.Live && sample.value == Temperature.of(21)) {
            "$runtime reads $sample, not a live 21"
        }
        check(state.value === state.value) { "$runtime: `value` is not the cached sample while polling" }
    }

    @TearDown(Level.Trial)
    fun tearDown(): Unit = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    /** A buffer per benchmark thread, for the port read alone. */
    @State(Scope.Thread)
    open class Buffer {
        val buf: ByteBuffer = ByteBuffer.allocate(TemperatureCodec.maxSize)
    }

    @Benchmark
    fun clock(): Long = System.nanoTime()

    @Benchmark
    fun portRead(b: Buffer): RawSample {
        b.buf.clear()
        return port.read(Cabin.number, TEMPERATURE, b.buf)
    }

    @Benchmark
    fun value(): Sample<Temperature> = state.value

    @Benchmark
    fun read(): Sample<Temperature> = state.read()

    // One writer at a time: a signal has one, and the loopback's writer handle is driven by one thread.
    private fun publish(): Unit = synchronized(writer) { publishNext() }

    @Benchmark
    fun publishChanged() {
        publish()
    }

    @Benchmark
    fun portReadChanged(b: Buffer): RawSample {
        publish()
        b.buf.clear()
        return port.read(Cabin.number, TEMPERATURE, b.buf)
    }

    @Benchmark
    fun readChanged(): Sample<Temperature> {
        publish()
        return state.read()
    }

    private companion object {
        val TEMPERATURE = Ordinal(1u)
    }
}
