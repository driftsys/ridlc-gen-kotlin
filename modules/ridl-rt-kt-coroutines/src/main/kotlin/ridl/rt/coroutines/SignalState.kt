// A signal as a shared `StateFlow` (#76): the suspending client's view of a
// value over time. The runtime has no wake-up for a signal change, so a state
// polls the port while it has a subscriber, on the shared grid of `PollGrid`,
// and keeps its last sample. A later version can wait on a commit instead
// without changing what a collector sees.
package ridl.rt.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    /** Each signal's state at its default period: what a generated property returns on every access. */
    private val defaults = ConcurrentHashMap<Signal<*>, SignalState<*>>()

    /**
     * The state of [signal] polled at `effectivePeriod(signal.member.timing,
     * desired, grid.quantum)`, created the first time that period is asked
     * for. Two requests that resolve to the same period get the same state.
     * [decode] turns the bytes a read copied into [maxSize] bytes, and the
     * read's [RawSample], into a sample; it runs only for a publication the
     * state has not decoded yet. Creating a state reads nothing: its first
     * read is its first `value`, `read()` or subscriber.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> of(
        signal: Signal<T>,
        desired: Duration?,
        maxSize: Int,
        decode: (RawSample, ByteBuffer) -> Sample<T>,
    ): SignalState<T> {
        if (desired == null) defaults[signal]?.let { return it as SignalState<T> }
        val period = effectivePeriod(signal.member.timing, desired, grid.quantum)
        val state = states.computeIfAbsent(signal to period) { SignalState(this, port, signal, period, maxSize, decode) }
        if (desired == null) defaults.putIfAbsent(signal, state)
        return state as SignalState<T>
    }
}

/**
 * One signal as a hot `StateFlow` of its samples (#76), polled at [period]
 * while it has a subscriber.
 *
 * - **Lifecycle.** Polling starts with the first subscriber, and stops
 *   `linger` (3 s by default) after the last one left; a subscriber that comes
 *   back within that time keeps the same polling. A subscriber that starts the
 *   polling reads the port at once, then the state reads at each multiple of
 *   [period] on the grid, so states of compatible periods read in the same
 *   slot. No coroutine runs while the state has no subscriber and no stop is
 *   pending, and creating a state reads nothing.
 * - **Emission.** A sample is emitted when its value, its provenance, its
 *   envelope or its staleness differs from the last one emitted: each
 *   publication a poll reads, a `touch` included, and a turn to stale or back.
 *   A poll period rounded up to the grid can miss publications closer together
 *   than it, as a signal is a last-value state. The age of a stale sample is
 *   not compared, so a stale value is emitted once.
 * - **[value].** While polling, the last sample polled, with the freshness of
 *   that poll: at most one [period] old while the poller keeps its slots, older
 *   if its dispatcher starves it, as no clock is read to check (#78).
 *   Otherwise a fresh read of the port. [read] always reads the port.
 * - **Cost.** A read copies the channel into one buffer the state keeps, and
 *   decodes it only when its bytes, envelope or provenance changed since the
 *   last read: an unchanged publication is not decoded again.
 * - **Failure.** A read or a decode that throws while polling stops the
 *   polling, and every collector's `collect` throws it, as a cold flow would;
 *   the failure stays what collectors see until a subscriber starts the
 *   polling again. It never reaches the scope the state polls in, except a
 *   `VirtualMachineError` such as `OutOfMemoryError`, which is rethrown after
 *   collectors are told.
 *
 * Cancelling the scope the state polls in stops the polling; a collector then
 * waits forever, as on any `StateFlow`.
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
    /** A failed poll, as collectors see it until the polling starts again. */
    private class Failed(val error: Throwable)

    /** What collectors see before the first read. */
    private object Unset

    // Guarded by `lock`: a read and its publication, the decode cache, the
    // subscribers, the polling and the pending stop.
    private val lock = Any()
    private val buf: ByteBuffer = ByteBuffer.allocate(maxSize)
    private var lastRaw: RawSample? = null
    private var lastBytes = ByteArray(0)
    private var lastDecoded: Sample<T>? = null
    private var subscribers = 0
    private var poller: Job? = null
    private var stopper: Job? = null

    /** Whether a poller runs: written under `lock`, read without it by [value]. */
    @Volatile
    private var polling = false

    /** The last sample read, with the freshness of that read: what [value] returns while polling. */
    @Volatile
    private var latest: Sample<T>? = null

    /** What collectors see: [Unset], the last sample emitted, or the failure that stopped the polling. */
    private val state = MutableStateFlow<Any>(Unset)

    /**
     * Reads the port and publishes the sample, under one lock so that two
     * reads publish in the order they read. A failure collectors have not yet
     * seen stays until [restart] replaces it; a [restart] read replaces
     * whatever is there, so its freshness is the port's now.
     */
    private fun readLocked(restart: Boolean = false): Sample<T> = synchronized(lock) {
        val sample = fetch()
        latest = sample
        state.update { last ->
            when {
                restart -> sample
                last is Failed -> last
                last !is Sample<*> || differs(last, sample) -> sample
                else -> last
            }
        }
        sample
    }

    /** Reads the port, decoding only a publication not decoded yet; the cache changes only on a decode that returns. */
    private fun fetch(): Sample<T> = synchronized(lock) {
        buf.clear()
        val raw = port.read(signal.iface.number, signal.member.ordinal, buf)
        val last = lastRaw
        val decoded = lastDecoded
        val sample = if (decoded != null && last != null && raw.envelope == last.envelope &&
            raw.provenance == last.provenance && sameBytes(raw.len)
        ) {
            decoded.copy(freshness = raw.freshness)
        } else {
            decode(raw, buf).also {
                remember(raw.len)
                lastDecoded = it
            }
        }
        lastRaw = raw
        sample
    }

    private fun sameBytes(len: Int): Boolean {
        if (len != lastBytes.size) return false
        for (i in 0 until len) if (buf.get(i) != lastBytes[i]) return false
        return true
    }

    private fun remember(len: Int) {
        lastBytes = ByteArray(len) { buf.get(it) }
    }

    private fun subscribe() {
        synchronized(lock) {
            subscribers++
            stopper?.cancel()
            stopper = null
            if (!polling) {
                // A subscriber that starts the polling sees the port now, not the sample or the failure from before.
                readLocked(restart = true)
                polling = true
                poller = states.scope.launch { poll() }
            }
        }
    }

    private fun unsubscribe() {
        synchronized(lock) {
            if (--subscribers > 0) return
            stopper = states.scope.launch {
                delay(states.linger)
                val self = coroutineContext[Job]
                synchronized(lock) {
                    // A stopper cancelled while it waited for the lock is no longer the stop.
                    if (stopper === self && subscribers == 0) {
                        stopper = null
                        val job = poller
                        stopPolling(job)
                        job?.cancel()
                    }
                }
            }
        }
    }

    /** Marks [job] stopped, under `lock`, if it is still the poller: a later subscriber starts a new one. */
    private fun stopPolling(job: Job?) {
        if (job != null && poller === job) {
            poller = null
            polling = false
        }
    }

    private suspend fun poll() {
        val self = currentCoroutineContext()[Job]
        try {
            while (true) {
                delay(states.grid.untilNext(period))
                readLocked()
            }
        } catch (e: Throwable) {
            // A cancellation of this poller is the stop; one a port or a codec throws is a failure.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            synchronized(lock) {
                // A poller already replaced has nothing left to tell.
                if (self != null && poller === self) {
                    stopPolling(self)
                    state.value = Failed(e)
                }
            }
            // Collectors meet the failure; the scope the state polls in never does, but for the JVM's own.
            if (e is VirtualMachineError) throw e
        }
    }

    /**
     * Reads the port now, whether or not the state is polling, and returns the
     * sample; a sample that differs from the last one emitted is emitted. It
     * does not wait.
     *
     * @throws ReadError as [SignalReader.read] does.
     */
    public fun read(): Sample<T> = readLocked()

    /** While polling, the last sample polled; otherwise a fresh [read]. */
    override val value: Sample<T>
        get() = (if (polling) latest else null) ?: read()

    override val replayCache: List<Sample<T>>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Sample<T>>): Nothing {
        try {
            subscribe()
            state.collect {
                if (it is Failed) throw it.error
                @Suppress("UNCHECKED_CAST")
                if (it !== Unset) collector.emit(it as Sample<T>)
            }
        } finally {
            unsubscribe()
        }
    }

    /**
     * The state of the same signal polled every [period], never faster than
     * its default period: the client's shared state for that effective period,
     * this one when it resolves to [this.period].
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
