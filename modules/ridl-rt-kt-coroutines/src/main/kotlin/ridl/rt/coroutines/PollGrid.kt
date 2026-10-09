// The grid signal states poll on (#76): one monotonic epoch and a quantum of
// 10 ms. A state polls at the multiples of its period from the epoch, so two
// states of compatible periods wake in the same slot, whenever each started.
package ridl.rt.coroutines

import ridl.rt.contract.Timing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * A shared time grid: an epoch taken once from [timeSource], and a [quantum]
 * every polling period is a multiple of. [Default] is one grid for the whole
 * process, so every state of every client lines up; a test passes its
 * scheduler's `timeSource`.
 */
public class PollGrid(
    /** The granularity of every period on the grid. */
    public val quantum: Duration = 10.milliseconds,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(quantum.isPositive()) { "a grid has a positive quantum, not $quantum" }
    }

    private val epoch = timeSource.markNow()

    /**
     * The time from now to the next multiple of [period] since the epoch,
     * strictly after now: a full [period] when now is on a multiple. A poll
     * that overran its slot lands on the next one, not on the missed ones.
     */
    public fun untilNext(period: Duration): Duration {
        val p = period.inWholeNanoseconds
        require(p > 0) { "a period is positive, not $period" }
        val elapsed = epoch.elapsedNow().inWholeNanoseconds
        return (p - Math.floorMod(elapsed, p)).nanoseconds
    }

    public companion object {
        /** The grid of the process: one epoch for every client that does not bring its own. */
        public val Default: PollGrid = PollGrid()
    }
}

/** ridl's default rate floor, `@[100ms..1000ms]` (ridl §9.1): the period of a signal whose timing has no bound. */
private val DEFAULT_PERIOD = 100.milliseconds

/**
 * The period a signal of [timing] is polled at on a grid of [quantum] (#76):
 *
 * - [desired] set: `max(desired, timing.min)`, as a faster update than the
 *   rate floor is coalesced (ridl §9) and a faster poll would only read it
 *   again. A [desired] slower than the staleness bound is kept: the sample's
 *   freshness reports the staleness.
 * - [desired] `null`: the rate floor, capped at the staleness bound rounded
 *   down; with no floor, half the staleness bound, rounded down, so a value is
 *   read before it turns stale; with neither, ridl's default floor of 100 ms.
 *
 * A period is rounded up to a multiple of [quantum], unless the cap rounds it
 * down, and is never less than one [quantum].
 */
public fun effectivePeriod(timing: Timing?, desired: Duration?, quantum: Duration): Duration {
    require(quantum.isPositive()) { "a grid has a positive quantum, not $quantum" }
    require(desired == null || desired.isPositive()) { "a desired period is positive, not $desired" }
    val q = quantum.inWholeNanoseconds
    val min = timing?.min?.micros?.takeIf { it > 0 }?.microseconds
    val max = timing?.max?.micros?.takeIf { it > 0 }?.microseconds
    fun up(d: Duration): Long = (d.inWholeNanoseconds + q - 1) / q
    fun down(d: Duration): Long = d.inWholeNanoseconds / q
    val slots = when {
        desired != null -> up(maxOf(desired, min ?: desired))
        min != null -> up(min).let { if (max != null) minOf(it, down(max)) else it }
        max != null -> down(max / 2)
        else -> up(DEFAULT_PERIOD)
    }
    return (q * slots.coerceAtLeast(1)).nanoseconds
}
