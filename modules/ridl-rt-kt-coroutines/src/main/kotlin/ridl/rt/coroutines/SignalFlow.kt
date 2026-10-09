// A signal as a cold `Flow` (#72): the suspending client's view of a value over
// time. The runtime has no wake-up for a signal change, so the flow polls the
// read at the signal's rate; a later version can wait on a commit instead
// without changing what a collector sees.
package ridl.rt.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ridl.rt.sample.Freshness
import ridl.rt.sample.Sample
import kotlin.time.Duration

/**
 * The samples of one signal: [read] at once, then every [period], emitting
 * each sample that differs from the last one emitted.
 *
 * A sample differs when its value, its provenance or its envelope differs, so
 * every publication is emitted, a `touch` that re-affirms the same value
 * included, or when it turns stale or fresh again. The age of a stale sample
 * grows on every read and is not compared, so a stale value is emitted once,
 * not once per period. A caller that wants only changes of the value applies
 * `distinctUntilChangedBy { it.value }`.
 *
 * [read] does not suspend; it runs on the collector's context. The flow is
 * cold, never completes, and ends when its collector is cancelled. An
 * exception [read] throws ends it.
 */
public fun <T> signalFlow(period: Duration, read: () -> Sample<T>): Flow<Sample<T>> {
    require(period.isPositive()) { "a signal flow polls at a positive period, not $period" }
    return flow {
        var last: Key? = null
        while (true) {
            val sample = read()
            val key = Key(sample)
            if (key != last) {
                emit(sample)
                last = key
            }
            delay(period)
        }
    }
}

/** What [signalFlow] compares: a sample with its staleness, but not its age. */
private data class Key(val value: Any?, val provenance: Any, val envelope: Any, val stale: Boolean) {
    constructor(sample: Sample<*>) : this(sample.value, sample.provenance, sample.envelope, sample.freshness is Freshness.Stale)
}
