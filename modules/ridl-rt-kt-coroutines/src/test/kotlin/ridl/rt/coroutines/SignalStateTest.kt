package ridl.rt.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import ridl.rt.contract.Timing
import ridl.rt.contract.TimingMode
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

        override fun init() = 0
    }

    private object Gauge : Interface {
        override val catalog = CatalogRef("test", CatalogHash(ByteArray(32)))
        override val number = InterfaceNo(1u)
        override val provisional = false
        override val name = "Gauge"
        override val members get() = emptyList<Member>()
    }

    private fun range(minMs: Long?, maxMs: Long?) =
        Timing(TimingMode.Range, minMs?.let { RidlDuration(it * 1000) }, maxMs?.let { RidlDuration(it * 1000) })

    private class Fixture(scope: TestScope, val signal: Signal<Int> = Level(Timing(TimingMode.Range, RidlDuration(100_000), RidlDuration(1_000_000)))) {
        val channel = Channel { scope.currentTime }
        val states = SignalStates(channel, scope.backgroundScope, PollGrid(timeSource = scope.testScheduler.timeSource))
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
        assertEquals(1, f.channel.reads.size, "the one read that created the state")
        f.channel.publish(22)
        assertEquals(22, state.value.value, "value reads the port")
        assertEquals(2, f.channel.reads.size)
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
}
