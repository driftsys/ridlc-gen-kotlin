package ridl.rt.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Sample
import ridl.rt.sample.Timestamp
import kotlin.time.Duration.Companion.milliseconds

/** #72: `signalFlow` reads at once, then every period, and emits what differs from the last emission. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalFlowTest {
    private fun sample(value: Int, seq: Int, freshness: Freshness = Freshness.Fresh, provenance: Provenance = Provenance.Live) =
        Sample(value, provenance, freshness, Envelope(Timestamp(seq * 1000L), seq.toULong()))

    @Test
    fun `the first sample is emitted at once, and an unchanged one is not emitted again`() = runTest {
        var reads = 0
        val emitted = mutableListOf<Sample<Int>>()
        val job = launch { signalFlow(10.milliseconds) { reads++; sample(21, 1) }.collect { emitted += it } }
        runCurrent()
        assertEquals(listOf(sample(21, 1)), emitted, "emitted before the first period")
        advanceTimeBy(95.milliseconds)
        assertEquals(10, reads, "one read per period")
        assertEquals(1, emitted.size, "the same sample is emitted once")
        job.cancel()
    }

    @Test
    fun `every publication is emitted, a touch of the same value included`() = runTest {
        val reads = ArrayDeque(listOf(sample(21, 1), sample(21, 1), sample(22, 2), sample(22, 3), sample(22, 3)))
        val emitted = signalFlow(10.milliseconds) { reads.removeFirstOrNull() ?: sample(22, 3) }.take(3).toList()
        assertEquals(listOf(sample(21, 1), sample(22, 2), sample(22, 3)), emitted)
        assertEquals(30, currentTime, "the third distinct sample is the fourth read")
    }

    @Test
    fun `a stale sample is emitted once, however its age grows, and again when it turns fresh`() = runTest {
        val reads = ArrayDeque(
            listOf(
                sample(21, 1),
                sample(21, 1, Freshness.Stale(Duration(1))),
                sample(21, 1, Freshness.Stale(Duration(2))),
                sample(21, 1, Freshness.Stale(Duration(3))),
                sample(21, 1),
            ),
        )
        val emitted = signalFlow(10.milliseconds) { reads.removeFirst() }.take(3).toList()
        assertEquals(listOf(sample(21, 1), sample(21, 1, Freshness.Stale(Duration(1))), sample(21, 1)), emitted)
    }

    @Test
    fun `a change of provenance is emitted`() = runTest {
        val invalid = sample(0, 1, provenance = Provenance.Invalid(ridl.rt.sample.Cause.Declared))
        val reads = ArrayDeque(listOf(sample(0, 1, provenance = Provenance.Init), invalid))
        val emitted = signalFlow(10.milliseconds) { reads.removeFirst() }.take(2).toList()
        assertEquals(Provenance.Invalid(ridl.rt.sample.Cause.Declared), emitted[1].provenance)
    }

    @Test
    fun `a period that is not positive is refused`() {
        assertThrows<IllegalArgumentException> { signalFlow(kotlin.time.Duration.ZERO) { sample(0, 0) } }
    }
}
