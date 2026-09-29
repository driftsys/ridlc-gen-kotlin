// `Clock`: the time envelopes are stamped from. `crates/ridl-rt-conformance/src/clock.rs`.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.sample.Duration
import kotlin.reflect.KFunction0

/** The tests of `Clock`. */
public class ClockContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(::`the clock is hand driven not wall clock`)

    /**
     * The clock does not move while real time passes, and [Factory.advance]
     * moves it by exactly the amount given. A clock that read wall-clock time
     * could not hold the first half, and every test that states a timestamp
     * relies on both.
     */
    public fun `the clock is hand driven not wall clock`() {
        val rt = runtime()
        val before = rt.now()
        Thread.sleep(5)
        assertEquals(before, rt.now(), "the clock must not read wall-clock time")

        factory.advance(rt, Duration(1_000))
        assertEquals(later(before, 1_000), rt.now(), "advance moves the hand-driven clock by exactly the given amount")
    }
}
