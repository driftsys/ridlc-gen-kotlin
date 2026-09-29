package ridl.rt.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ridl.rt.sample.Duration

/**
 * `Member.callDeadline`: a call's response bound from its member's timing
 * (ridl §9.3; frame specification §8). `crates/ridl-rt/tests/call_deadline.rs`
 * of ridl `main` (story E11.19), case for case.
 */
class CallDeadlineTest {
    private fun member(kind: Kind, timing: Timing?) = Member(Ordinal(1u), kind, "lock", timing, emptyList())

    private fun command(timing: Timing?) = member(Kind.Command, timing)

    /** ridl §9.3: on a `command`, `max` is the response bound. */
    @Test
    fun `the deadline is the response bound`() {
        assertEquals(Duration(100_000), command(Timing(TimingMode.Range, null, Duration(100_000))).callDeadline())
    }

    /** ridl §9.3: on a `query`, `max` is the response bound too. */
    @Test
    fun `the deadline of a query is its response bound`() {
        assertEquals(
            Duration(400_000),
            member(Kind.Query, Timing(TimingMode.Range, null, Duration(400_000))).callDeadline(),
        )
    }

    /** ridl §9: `max` is read whatever the kind; on a `signal` it is returned all the same. */
    @Test
    fun `the max of a signal is returned as it is`() {
        val signal = member(Kind.Signal, Timing(TimingMode.StrictPeriodic, Duration(5_000), Duration(10_000)))
        assertEquals(Duration(10_000), signal.callDeadline())
    }

    /** ridl §9.3: `min` is the call throttle, not part of the deadline. */
    @Test
    fun `the call throttle does not change the deadline`() {
        assertEquals(
            Duration(250_000),
            command(Timing(TimingMode.Range, Duration(20_000), Duration(250_000))).callDeadline(),
        )
    }

    /** ridl §9.3: `@[20ms..]` is a throttle with no response bound. */
    @Test
    fun `a throttle alone gives no deadline`() {
        assertNull(command(Timing(TimingMode.Range, Duration(20_000), null)).callDeadline())
    }

    /** ridl §9.1 and §9.3: a bare `command` has no timing and so no deadline. */
    @Test
    fun `a member with no timing has no deadline`() {
        assertNull(command(null).callDeadline())
    }
}
