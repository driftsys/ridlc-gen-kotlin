package ridl.rt.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.Interface
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Kind
import ridl.rt.contract.Member
import ridl.rt.contract.Ordinal
import ridl.rt.contract.Signal
import ridl.rt.contract.Sizes
import ridl.rt.contract.Timing
import ridl.rt.contract.TimingMode
import ridl.rt.encoding.Encoding
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SignalReader
import ridl.rt.sample.Cause
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Sample
import ridl.rt.sample.Timestamp
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ridl.rt.sample.Duration as RidlDuration

/** #76: a signal as a shared `StateFlow`, polled on the grid while it has a subscriber. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalStateTest {
    /** A port with one signal channel holding an `Int`, which records the virtual time of each read. */
    private class Channel(private val now: () -> Long) : SignalReader {
        override val catalog = CatalogRef("test", CatalogHash(ByteArray(32)))
        var value = 21
        var seq = 1uL
        var provenance: Provenance = Provenance.Live
        var freshness: Freshness = Freshness.Fresh
        var failure: Throwable? = null
        val reads = mutableListOf<Long>()

        fun publish(value: Int) {
            this.value = value
            seq++
        }

        override fun read(iface: InterfaceNo, ord: Ordinal, out: ByteBuffer): RawSample {
            reads += now()
            failure?.let { throw it }
            out.putInt(value)
            return RawSample(provenance, freshness, Envelope(Timestamp(seq.toLong()), seq), 4)
        }
    }

    private class Level(timing: Timing?, override val iface: Interface = Gauge) : Signal<Int> {
        override val member = Member(Ordinal(1u), Kind.Signal, "level", timing, emptyList())
        override val row = 0

        override fun init() = 0
    }

    private object Gauge : Interface {
        override val catalog = CatalogRef("test", CatalogHash(ByteArray(32)))
        override val number = InterfaceNo(1u)
        override val provisional = false
        override val name = "Gauge"
        override val members get() = emptyList<Member>()

        override fun sizes(encoding: Encoding): Sizes = throw IllegalArgumentException("no codec")
    }

    private fun range(minMs: Long?, maxMs: Long?) =
        Timing(TimingMode.Range, minMs?.let { RidlDuration(it * 1000) }, maxMs?.let { RidlDuration(it * 1000) })

    private class Fixture(
        scope: TestScope,
        val signal: Signal<Int> = Level(Timing(TimingMode.Range, RidlDuration(100_000), RidlDuration(1_000_000))),
        pollScope: CoroutineScope = scope.backgroundScope,
    ) {
        val channel = Channel { scope.currentTime }
        val states = SignalStates(channel, pollScope, PollGrid(timeSource = scope.testScheduler.timeSource))
        var decodes = 0
        var decodeFailure: Throwable? = null

        fun state(desired: Duration? = null): SignalState<Int> = states.of(signal, desired, 4) { raw, buf ->
            decodes++
            decodeFailure?.let { throw it }
            buf.flip()
            Sample(buf.getInt(), raw.provenance, raw.freshness, raw.envelope)
        }
    }

    private fun TestScope.subscribe(state: SignalState<Int>, into: MutableList<Sample<Int>> = mutableListOf()): Job =
        backgroundScope.launch { state.collect { into += it } }.also { runCurrent() }

    // -- the period ------------------------------------------------------------

    @Test
    fun `the default period is the rate floor rounded up, capped at the staleness bound rounded down`() {
        val q = 10.milliseconds
        assertEquals(100.milliseconds, effectivePeriod(Timing(TimingMode.StrictPeriodic, RidlDuration(100_000), RidlDuration(100_000)), null, q))
        assertEquals(30.milliseconds, effectivePeriod(range(25, 500), null, q), "rounded up to the grid")
        assertEquals(10.milliseconds, effectivePeriod(range(5, null), null, q), "never less than one quantum")
        assertEquals(90.milliseconds, effectivePeriod(Timing(TimingMode.Range, RidlDuration(95_000), RidlDuration(99_000)), null, q), "the cap wins")
        assertEquals(500.milliseconds, effectivePeriod(range(null, 1000), null, q), "half the staleness bound without a floor")
        assertEquals(100.milliseconds, effectivePeriod(null, null, q), "ridl's default floor without a timing")
    }

    @Test
    fun `a desired period is never faster than the rate floor, and may be slower than the staleness bound`() {
        val q = 10.milliseconds
        assertEquals(100.milliseconds, effectivePeriod(range(100, 1000), 10.milliseconds, q), "clamped to the floor")
        assertEquals(1.seconds, effectivePeriod(range(100, 1000), 1.seconds, q))
        assertEquals(5.seconds, effectivePeriod(range(100, 1000), 5.seconds, q), "not capped")
        assertEquals(260.milliseconds, effectivePeriod(range(100, 1000), 255.milliseconds, q), "rounded up to the grid")
    }

    @Test
    fun `a desired period faster than the default is the default, the cap included`() {
        val q = 10.milliseconds
        val capped = Timing(TimingMode.Range, RidlDuration(95_000), RidlDuration(99_000))
        assertEquals(90.milliseconds, effectivePeriod(capped, 10.milliseconds, q), "the capped default, not the floor rounded up")
        val strict = Timing(TimingMode.StrictPeriodic, RidlDuration(15_000), RidlDuration(15_000))
        assertEquals(effectivePeriod(strict, null, q), effectivePeriod(strict, 1.milliseconds, q))
    }

    @Test
    fun `an infinite desired period saturates at the longest period, not the shortest`() {
        val q = 10.milliseconds
        val period = effectivePeriod(range(100, 1000), Duration.INFINITE, q)
        assertTrue(period.inWholeDays > 36_500, "a century at least, not $period")
        assertEquals(0L, period.inWholeNanoseconds % q.inWholeNanoseconds, "on the grid")
    }

    // -- sharing ---------------------------------------------------------------

    @Test
    fun `requests that resolve to the same period share one state`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        assertSame(state, f.state(), "the same signal again")
        assertSame(state, state.every(10.milliseconds), "faster than the floor resolves to the floor")
        assertSame(state, state.every(95.milliseconds), "rounds up to the floor")
        val slow = state.every(1.seconds)
        assertEquals(1.seconds, slow.period)
        assertSame(slow, state.every(1.seconds))
    }

    // -- the lifecycle ----------------------------------------------------------

    @Test
    fun `nothing polls without a subscriber, and value is then a fresh read`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        advanceTimeBy(1.seconds)
        assertEquals(0, f.channel.reads.size, "creating a state reads nothing")
        f.channel.publish(22)
        assertEquals(22, state.value.value, "value reads the port")
        assertEquals(1, f.channel.reads.size)
    }

    @Test
    fun `polling starts with the first subscriber and stops three seconds after the last one left`() = runTest {
        val f = Fixture(this)
        val job = subscribe(f.state())
        advanceTimeBy(1.seconds)
        job.cancel()
        advanceTimeBy(2.9.seconds)
        val before = f.channel.reads.size
        advanceTimeBy(100.milliseconds)
        assertTrue(f.channel.reads.size > before, "still polling 2.9 s after the last subscriber left")
        advanceTimeBy(150.milliseconds)
        val stopped = f.channel.reads.size
        advanceTimeBy(5.seconds)
        assertEquals(stopped, f.channel.reads.size, "stopped after 3 s")
    }

    @Test
    fun `a subscriber back within the linger keeps the same polling`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        subscribe(state).cancel()
        advanceTimeBy(1.seconds)
        subscribe(state)
        advanceTimeBy(1.seconds)
        val polls = f.channel.reads.filter { it > 0 }
        assertTrue(polls.all { it % 100 == 0L }, "every poll on the grid, with no restart off it: $polls")
    }

    @Test
    fun `while polling, value is the last sample polled, and read goes to the port`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        subscribe(state)
        advanceTimeBy(150.milliseconds)
        f.channel.publish(22)
        val reads = f.channel.reads.size
        assertEquals(21, state.value.value, "the last poll, not the port")
        assertEquals(reads, f.channel.reads.size)
        assertEquals(22, state.read().value)
        assertEquals(reads + 1, f.channel.reads.size)
    }

    @Test
    fun `while polling, a sample older than one period and one quantum is read again, as from a starved poller`() = runTest {
        // A poller on a scheduler nothing advances: it never reads after the subscriber's first read.
        val starved = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()) + Job())
        val f = Fixture(this, pollScope = starved)
        val state = f.state()
        subscribe(state)
        f.channel.publish(22)
        advanceTimeBy(110.milliseconds)
        assertEquals(21, state.value.value, "one period and one quantum old: the last poll")
        advanceTimeBy(1.milliseconds)
        assertEquals(22, state.value.value, "older: a read of the port")
        val reads = f.channel.reads.size
        assertEquals(22, state.value.value, "the read is the sample now")
        assertEquals(reads, f.channel.reads.size)
        starved.cancel()
    }

    @Test
    fun `a restarted polling is seen as polling, whatever the stopped one does late`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        subscribe(state).cancel()
        advanceTimeBy(10.seconds)
        subscribe(state)
        advanceTimeBy(150.milliseconds)
        val reads = f.channel.reads.size
        state.value
        assertEquals(reads, f.channel.reads.size, "value is the last poll, not a read: the new polling is the one seen")
    }

    @Test
    fun `the first emission after a stop is the port now, not the sample from before`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        subscribe(state).cancel()
        advanceTimeBy(10.seconds)
        f.channel.publish(22)
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(state, emitted)
        assertEquals(22, emitted.first().value)
    }

    // -- the grid --------------------------------------------------------------

    @Test
    fun `states of compatible periods read in the same slots`() = runTest {
        val f = Fixture(this)
        advanceTimeBy(37.milliseconds)
        val fast = f.state()
        val slow = fast.every(300.milliseconds)
        subscribe(fast)
        advanceTimeBy(13.milliseconds)
        subscribe(slow)
        f.channel.reads.clear()
        advanceTimeBy(1.seconds)
        val slots = f.channel.reads.groupingBy { it }.eachCount()
        assertTrue(slots.keys.all { it % 100 == 0L }, "every read on a multiple of 100 ms: ${slots.keys}")
        assertEquals(setOf(300L, 600L, 900L), slots.filterValues { it == 2 }.keys, "the slow state reads in the fast one's slots")
    }

    // -- emission and cost --------------------------------------------------------

    @Test
    fun `every publication is emitted, a touch of the same value included, and an unchanged one is not`() = runTest {
        val f = Fixture(this)
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(f.state(), emitted)
        advanceTimeBy(250.milliseconds)
        f.channel.publish(21)
        advanceTimeBy(100.milliseconds)
        f.channel.publish(22)
        advanceTimeBy(500.milliseconds)
        assertEquals(listOf(21 to 1uL, 21 to 2uL, 22 to 3uL), emitted.map { it.value to it.envelope.seq })
    }

    @Test
    fun `a turn to stale or invalid is emitted with an unchanged value, a growing age is not`() = runTest {
        val f = Fixture(this)
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(f.state(), emitted)
        for (by in 1L..3L) {
            f.channel.freshness = Freshness.Stale(RidlDuration(by))
            advanceTimeBy(100.milliseconds)
        }
        f.channel.provenance = Provenance.Invalid(Cause.Declared)
        advanceTimeBy(100.milliseconds)
        assertEquals(
            listOf(false to Provenance.Live, true to Provenance.Live, true to Provenance.Invalid(Cause.Declared)),
            emitted.map { (it.freshness is Freshness.Stale) to it.provenance },
        )
    }

    @Test
    fun `while stale and polled, value carries the freshness of the last poll`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        subscribe(state)
        f.channel.freshness = Freshness.Stale(RidlDuration(1))
        advanceTimeBy(150.milliseconds)
        f.channel.freshness = Freshness.Stale(RidlDuration(9))
        advanceTimeBy(100.milliseconds)
        assertEquals(Freshness.Stale(RidlDuration(9)), state.value.freshness)
    }

    @Test
    fun `new bytes under the same envelope are decoded, as from a second writer whose count restarted`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        assertEquals(21, state.read().value)
        f.channel.value = 22
        assertEquals(22, state.read().value, "same stamp, same seq, other bytes")
        assertEquals(2, f.decodes)
    }

    @Test
    fun `a read error while polling ends every collector with it`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        val ended = mutableListOf<Throwable>()
        backgroundScope.launch { runCatching { state.collect {} }.exceptionOrNull()?.let(ended::add) }
        runCurrent()
        f.channel.failure = ReadError.Detached
        advanceTimeBy(150.milliseconds)
        assertEquals(listOf<Throwable>(ReadError.Detached), ended)
    }

    @Test
    fun `any failure while polling stays out of the scope the state polls in`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        val sibling = backgroundScope.launch { kotlinx.coroutines.awaitCancellation() }
        val ended = mutableListOf<Throwable>()
        backgroundScope.launch { runCatching { state.collect {} }.exceptionOrNull()?.let(ended::add) }
        runCurrent()
        f.channel.publish(22)
        f.decodeFailure = IllegalStateException("a codec bug")
        advanceTimeBy(150.milliseconds)
        assertEquals("a codec bug", ended.single().message, "the collector meets it")
        assertTrue(sibling.isActive, "the scope's other coroutines do not")
        f.decodeFailure = null
        assertEquals(22, state.value.value, "a later read reads the port again")
    }

    @Test
    fun `a failure is not lost to a read made before its collector sees it`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ended = mutableListOf<Throwable>()
        backgroundScope.launch { runCatching { state.collect { gate.await() } }.exceptionOrNull()?.let(ended::add) }
        runCurrent()
        f.channel.failure = ReadError.Detached
        advanceTimeBy(150.milliseconds)
        f.channel.failure = null
        state.read()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf<Throwable>(ReadError.Detached), ended, "the collector still meets the failure")
    }

    @Test
    fun `a subscriber right after a failure starts the polling again`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        backgroundScope.launch { runCatching { state.collect {} } }
        runCurrent()
        f.channel.failure = ReadError.Detached
        advanceTimeBy(150.milliseconds)
        f.channel.failure = null
        f.channel.publish(22)
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(state, emitted)
        assertEquals(22, emitted.single().value, "the port now, not the old failure")
        val reads = f.channel.reads.size
        advanceTimeBy(500.milliseconds)
        assertTrue(f.channel.reads.size > reads, "polling again")
    }

    @Test
    fun `an Error from a codec ends collectors and stays out of the scope`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        val sibling = backgroundScope.launch { kotlinx.coroutines.awaitCancellation() }
        val ended = mutableListOf<Throwable>()
        backgroundScope.launch { runCatching { state.collect {} }.exceptionOrNull()?.let(ended::add) }
        runCurrent()
        f.channel.publish(22)
        f.decodeFailure = NotImplementedError("a codec TODO")
        advanceTimeBy(150.milliseconds)
        assertTrue(ended.single() is NotImplementedError)
        assertTrue(sibling.isActive)
    }

    @Test
    fun `a CancellationException a port throws is a failure, not the stop, and reaches collectors wrapped`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        val ended = mutableListOf<Throwable>()
        backgroundScope.launch { runCatching { state.collect {} }.exceptionOrNull()?.let(ended::add) }
        runCurrent()
        f.channel.failure = java.util.concurrent.CancellationException("a future the port waited on")
        advanceTimeBy(150.milliseconds)
        // Wrapped, so a collector launched in a scope does not end quietly, as if cancelled.
        val error = ended.single()
        assertTrue(error is IllegalStateException, "$error")
        assertEquals("a future the port waited on", error.cause?.message)
    }

    @Test
    fun `a decode that throws leaves the cache as it was`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        assertEquals(21, state.read().value)
        f.channel.value = 22
        f.decodeFailure = IllegalStateException("bad bytes")
        assertTrue(runCatching { state.read() }.isFailure)
        f.decodeFailure = null
        assertEquals(22, state.read().value, "the bytes that failed are decoded, not taken for the last ones")
    }

    @Test
    fun `the first emission after a stop has the freshness of the port now`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        f.channel.freshness = Freshness.Stale(RidlDuration(1))
        subscribe(state).cancel()
        advanceTimeBy(10.seconds)
        f.channel.freshness = Freshness.Stale(RidlDuration(9))
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(state, emitted)
        assertEquals(Freshness.Stale(RidlDuration(9)), emitted.first().freshness)
    }

    @Test
    fun `an unchanged publication is not decoded again`() = runTest {
        val f = Fixture(this)
        subscribe(f.state())
        advanceTimeBy(1.seconds)
        assertTrue(f.channel.reads.size > 5)
        assertEquals(1, f.decodes, "one publication, one decode")
        f.channel.publish(22)
        advanceTimeBy(1.seconds)
        assertEquals(2, f.decodes)
    }

    @Test
    fun `values emits a change of the value alone, over the same polling`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        val values = mutableListOf<Int>()
        backgroundScope.launch { state.values.collect { values += it } }
        runCurrent()
        f.channel.publish(21)
        advanceTimeBy(150.milliseconds)
        f.channel.freshness = Freshness.Stale(RidlDuration(1))
        advanceTimeBy(100.milliseconds)
        f.channel.publish(23)
        advanceTimeBy(100.milliseconds)
        assertEquals(listOf(21, 23), values)
        assertEquals(23, state.values.value)
    }

    // -- the client's states (#79) ---------------------------------------------------

    @Test
    fun `a state of another period is dropped when its polling stops, the default one is kept`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        // A slider: one period after another, each collected for a moment.
        for (step in 1..50) subscribe(state.every((100 + 10 * step).milliseconds)).cancel()
        assertEquals(51, f.states.size, "every period polls, or lingers")
        subscribe(state).cancel()
        advanceTimeBy(10.seconds)
        assertEquals(1, f.states.size, "the default state alone, once every linger ran out")
        assertSame(state, f.state(), "and it is the same instance")
        assertEquals(1, f.states.size, "a default state asked for again is not created again")
    }

    @Test
    fun `a dropped state a caller holds keeps working, and is shared again when collected again`() = runTest {
        val f = Fixture(this)
        val slow = f.state().every(1.seconds)
        subscribe(slow).cancel()
        advanceTimeBy(10.seconds)
        assertEquals(1, f.states.size, "dropped")
        f.channel.publish(22)
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(slow, emitted)
        assertEquals(22, emitted.single().value, "a held state still polls")
        assertSame(slow, f.state().every(1.seconds), "and is the shared one again")
        assertEquals(2, f.states.size)
    }

    @Test
    fun `a state that fails while polling is dropped, and one that never polled stays`() = runTest {
        val f = Fixture(this)
        val slow = f.state().every(1.seconds)
        val idle = f.state().every(2.seconds)
        backgroundScope.launch { runCatching { slow.collect {} } }
        runCurrent()
        f.channel.failure = ReadError.Detached
        advanceTimeBy(1.5.seconds)
        assertSame(idle, f.state().every(2.seconds), "never collected, still found while held")
        assertEquals(1, f.states.size, "the default alone is kept: the failed one is gone, the idle one is only remembered")
    }

    @Test
    fun `a state created and never collected is not kept, only remembered while held`() = runTest {
        val f = Fixture(this)
        // A slider read with `value` instead of collected: fifty periods, none polled.
        for (step in 1..50) f.state().every((100 + 10 * step).milliseconds).value
        assertEquals(1, f.states.size, "nothing is kept beside the default")
        val held = f.state().every(7.seconds)
        assertSame(held, f.state().every(7.seconds), "while held, two requests share one state")
        assertEquals(1, f.states.size, "and it is still not kept")
    }

    @Test
    fun `after the polling scope ends, collectors end with a failure, a new one too, and value reads the port`() = runTest {
        val own = CoroutineScope(coroutineContext + Job())
        val f = Fixture(this, pollScope = own)
        val state = f.state()
        val slow = state.every(1.seconds)
        val ended = mutableListOf<Throwable>()
        backgroundScope.launch { runCatching { state.collect {} }.exceptionOrNull()?.let(ended::add) }
        backgroundScope.launch { runCatching { slow.collect {} }.exceptionOrNull()?.let(ended::add) }
        runCurrent()
        advanceTimeBy(150.milliseconds)
        own.cancel()
        runCurrent()
        assertEquals(2, ended.size, "no collector waits on a state nothing polls")
        assertTrue(ended.all { it is IllegalStateException && it.cause is CancellationException }, "$ended")
        f.channel.publish(22)
        assertEquals(22, state.value.value, "no poller runs: value reads the port")
        assertEquals(1, f.states.size, "the default state is kept, the slow one is dropped")
        val late = runCatching { slow.collect {} }.exceptionOrNull()
        assertTrue(late is IllegalStateException, "a later subscriber fails at once, not forever: $late")
        assertEquals(1, f.states.size, "and does not put the state back")
    }

    @Test
    fun `a long period stops the linger after its last subscriber, not at its next slot`() = runTest {
        val f = Fixture(this)
        val slow = f.state().every(1.hours)
        subscribe(slow).cancel()
        assertEquals(1, f.channel.reads.size, "the subscriber's read alone")
        advanceTimeBy(3.seconds + 10.milliseconds)
        assertEquals(1, f.states.size, "stopped 3 s after the last subscriber left")
        advanceTimeBy(2.hours)
        assertEquals(1, f.channel.reads.size, "and no slot was read after it")
        // The saturated period, the longest there is, stops just the same.
        subscribe(slow.every(Duration.INFINITE)).cancel()
        advanceTimeBy(3.seconds + 10.milliseconds)
        assertEquals(1, f.states.size)
    }

    @Test
    fun `a subscriber back during the linger of a long period keeps the polling`() = runTest {
        val f = Fixture(this)
        val slow = f.state().every(1.seconds)
        subscribe(slow).cancel()
        advanceTimeBy(2.seconds)
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(slow, emitted)
        advanceTimeBy(5.seconds)
        assertEquals(2, f.states.size, "still polling")
        f.channel.publish(22)
        advanceTimeBy(1.seconds)
        assertEquals(22, emitted.last().value, "on its slots")
    }

    @Test
    fun `a desired period that resolves to the default, asked for first, is the default state`() = runTest {
        val f = Fixture(this)
        val first = f.state(100.milliseconds)
        assertEquals(0, f.states.size, "remembered, not kept")
        val default = f.state()
        assertSame(first, default, "one state per period, whichever request came first")
        assertEquals(1, f.states.size, "and it is kept as the default")
        subscribe(first).cancel()
        advanceTimeBy(10.seconds)
        assertEquals(1, f.states.size, "never dropped")
    }

    @Test
    fun `a subscriber whose first read fails is not counted`() = runTest {
        val f = Fixture(this)
        val state = f.state()
        f.channel.failure = ReadError.Detached
        assertTrue(runCatching { state.collect {} }.exceptionOrNull() is ReadError)
        f.channel.failure = null
        val emitted = mutableListOf<Sample<Int>>()
        subscribe(state, emitted).cancel()
        advanceTimeBy(10.seconds)
        f.channel.reads.clear()
        advanceTimeBy(1.seconds)
        assertEquals(0, f.channel.reads.size, "stopped after its only subscriber left")
    }
}
