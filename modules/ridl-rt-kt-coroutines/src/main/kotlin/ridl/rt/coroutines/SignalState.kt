// A signal as a shared `StateFlow` (#76): the suspending client's view of a
// value over time. The runtime has no wake-up for a signal change, so a state
// polls the port while it has a subscriber, on the shared grid of `PollGrid`,
// and keeps its last sample. A later version can wait on a commit instead
// without changing what a collector sees.
package ridl.rt.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ridl.rt.contract.Signal
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.SignalReader
import ridl.rt.sample.Freshness
import ridl.rt.sample.Sample
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The signal states of one client (#76): one [SignalState] per signal and
 * effective period, created on first use and kept for the client's life.
 *
 * Every state polls in [scope], on its dispatcher: give it a background one,
 * not the main thread. Cancelling [scope] stops every state's polling. States
 * poll on [grid], and stop polling [linger] after their last subscriber left.
 */
public class SignalStates(
    private val port: SignalReader,
    internal val scope: CoroutineScope,
    internal val grid: PollGrid = PollGrid.Default,
    internal val linger: Duration = 3.seconds,
) {
    init {
        require(!linger.isNegative()) { "a linger is not negative, not $linger" }
    }

    private val states = ConcurrentHashMap<Pair<Signal<*>, Duration>, SignalState<*>>()

    /**
     * The state of [signal] polled at `effectivePeriod(signal.member.timing,
     * desired, grid.quantum)`, created the first time that period is asked
     * for. Two requests that resolve to the same period get the same state.
     * [decode] turns the bytes a read copied into [maxSize] bytes, and the
     * read's [RawSample], into a sample; it runs only for a publication the
     * state has not decoded yet.
     *
     * @throws ReadError when the state is created and its first read fails.
     */
    public fun <T> of(
        signal: Signal<T>,
        desired: Duration?,
        maxSize: Int,
        decode: (RawSample, ByteBuffer) -> Sample<T>,
    ): SignalState<T> {
        val period = effectivePeriod(signal.member.timing, desired, grid.quantum)
        @Suppress("UNCHECKED_CAST")
        return states.computeIfAbsent(signal to period) { SignalState(this, port, signal, period, maxSize, decode) } as SignalState<T>
    }
}

/**
 * One signal as a hot `StateFlow` of its samples (#76), polled at [period]
 * while it has a subscriber.
 *
 * - **Lifecycle.** Polling starts with the first subscriber, and stops
 *   `linger` (3 s by default) after the last one left; a subscriber that comes
 *   back within that time keeps the same polling. A read at once starts it,
 *   then one at each multiple of [period] on the grid, so states of
 *   compatible periods read in the same slot.
 * - **Emission.** A sample is emitted when its value, its provenance, its
 *   envelope or its staleness differs from the last one emitted: every
 *   publication, a `touch` included, and a turn to stale or back. The age of a
 *   stale sample is not compared, so a stale value is emitted once.
 * - **[value].** While polling, the last sample polled, at most one [period]
 *   old; otherwise a fresh read of the port. [read] always reads the port.
 * - **Cost.** A read copies the channel into one buffer the state keeps, and
 *   decodes it only when its envelope, provenance or length changed since the
 *   last read: an unchanged publication is not decoded again.
 *
 * A [ReadError] while polling stops the polling; [value] and [read] then
 * throw it again, as the port does. The polling runs in the client's scope:
 * cancelling it stops the polling, and a collector then waits forever, as on
 * any `StateFlow`.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
public class SignalState<T> internal constructor(
    private val states: SignalStates,
    private val port: SignalReader,
    private val signal: Signal<T>,
    /** The period this state polls at, on its grid. */
    public val period: Duration,
    private val maxSize: Int,
    private val decode: (RawSample, ByteBuffer) -> Sample<T>,
) : StateFlow<Sample<T>> {
    private val lock = Any()
    private val buf: ByteBuffer = ByteBuffer.allocate(maxSize)
    private var lastRaw: RawSample? = null
    private var lastSample: Sample<T>? = null
    private val state = MutableStateFlow(fetch())

    @Volatile
    private var polling = false

    // The subscribers, the polling and the pending stop, under `lock`. No
    // coroutine runs while the state has no subscriber and no stop is pending.
    private var subscribers = 0
    private var poller: Job? = null
    private var stopper: Job? = null

    private fun subscribe() {
        synchronized(lock) {
            subscribers++
            stopper?.cancel()
            stopper = null
            if (poller?.isActive != true) poller = states.scope.launch { poll() }
        }
    }

    private fun unsubscribe() {
        synchronized(lock) {
            if (--subscribers > 0) return
            stopper = states.scope.launch {
                delay(states.linger)
                synchronized(lock) {
                    if (subscribers == 0) {
                        poller?.cancel()
                        poller = null
                    }
                }
            }
        }
    }

    private suspend fun poll() {
        polling = true
        try {
            while (true) {
                read()
                delay(states.grid.untilNext(period))
            }
        } catch (_: ReadError) {
            // The next read, by `value` or `read`, meets the error again.
        } finally {
            polling = false
        }
    }

    /** Reads the port, decoding only a publication not decoded yet. */
    private fun fetch(): Sample<T> = synchronized(lock) {
        buf.clear()
        val raw = port.read(signal.iface.number, signal.member.ordinal, buf)
        val last = lastRaw
        val cached = lastSample
        val sample = if (cached != null && last != null && raw.envelope == last.envelope &&
            raw.provenance == last.provenance && raw.len == last.len
        ) {
            cached.copy(freshness = raw.freshness)
        } else {
            decode(raw, buf)
        }
        lastRaw = raw
        lastSample = sample
        sample
    }

    private fun publish(sample: Sample<T>) {
        state.update { last -> if (differs(last, sample)) sample else last }
    }

    /**
     * Reads the port now, whether or not the state is polling, and returns the
     * sample; a sample that differs from the last one emitted is emitted. It
     * does not wait.
     *
     * @throws ReadError as [SignalReader.read] does.
     */
    public fun read(): Sample<T> = fetch().also(::publish)

    /** While polling, the last sample polled; otherwise a fresh [read]. */
    override val value: Sample<T>
        get() = if (polling) state.value else read()

    override val replayCache: List<Sample<T>>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Sample<T>>): Nothing {
        subscribe()
        try {
            // A subscriber after a stop sees the port now, not the sample from before the stop.
            if (!polling) read()
            state.collect(collector)
        } finally {
            unsubscribe()
        }
    }

    /**
     * The state of the same signal polled every [period], never faster than
     * its rate floor: the client's shared state for that effective period, this
     * one when it resolves to [this.period].
     */
    public fun every(period: Duration): SignalState<T> = states.of(signal, period, maxSize, decode)

    /** The values alone: a `StateFlow` that emits when the value changes, over this state's polling. */
    public val values: StateFlow<T> by lazy { SignalValues(this) }
}

/** What [SignalState] compares: a sample with its staleness, but not its age. */
private fun differs(a: Sample<*>, b: Sample<*>): Boolean = a.value != b.value || a.provenance != b.provenance ||
    a.envelope != b.envelope || (a.freshness is Freshness.Stale) != (b.freshness is Freshness.Stale)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class SignalValues<T>(private val parent: SignalState<T>) : StateFlow<T> {
    override val value: T get() = parent.value.value
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        parent.map { it.value }.distinctUntilChanged().collect(collector)
        error("a signal state never completes")
    }
}
