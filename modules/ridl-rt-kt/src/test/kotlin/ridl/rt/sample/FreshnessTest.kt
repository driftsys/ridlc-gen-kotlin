package ridl.rt.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.rt.contract.Timing
import ridl.rt.contract.TimingMode

/**
 * `Freshness.of`: a value's freshness from its envelope's timestamp, the
 * current time and its member's timing (ridl §4, §9; frame specification §8).
 * `crates/ridl-rt/tests/freshness.rs` of ridl `main` (story E11.19), case for
 * case.
 */
class FreshnessTest {
    /** `@[20ms..500ms]`, in microseconds. */
    private val range = Timing(TimingMode.Range, Duration(20_000), Duration(500_000))

    /** An envelope stamped at [us] microseconds. `seq` plays no part in freshness, so the fixtures vary it. */
    private fun stamped(us: Long, seq: ULong) = Envelope(Timestamp(us), seq)

    /** ridl §9 and frame §8: `Fresh` while `now − stamp ≤ max`. */
    @Test
    fun `a value younger than max is fresh`() {
        assertEquals(Freshness.Fresh, Freshness.of(stamped(1_000_000, 0u), Timestamp(1_200_000), range))
    }

    /** ridl §9 and frame §8: the bound is inclusive. */
    @Test
    fun `a value exactly max old is fresh`() {
        assertEquals(Freshness.Fresh, Freshness.of(stamped(1_000_000, 7u), Timestamp(1_500_000), range))
    }

    /** ridl §9 and frame §8: past the bound, `Stale(by = now − stamp − max)`. */
    @Test
    fun `a value older than max is stale by the excess`() {
        assertEquals(Freshness.Stale(Duration(1)), Freshness.of(stamped(1_000_000, 42u), Timestamp(1_500_001), range))
        assertEquals(
            Freshness.Stale(Duration(1_250_000)),
            Freshness.of(stamped(1_000_000, 42u), Timestamp(2_750_000), range),
        )
    }

    /** ridl §9 and frame §8: the rate floor `min` plays no part in freshness. */
    @Test
    fun `min does not affect freshness`() {
        for (timing in listOf(range, range.copy(min = null))) {
            assertEquals(Freshness.Fresh, Freshness.of(stamped(0, 3u), Timestamp(500_000), timing))
            assertEquals(Freshness.Stale(Duration(100_000)), Freshness.of(stamped(0, 3u), Timestamp(600_000), timing))
        }
    }

    /** ridl §4.1 and §9: under a strict period `@10ms`, `max` holds the period. */
    @Test
    fun `a strict period is the staleness bound`() {
        val period = Timing(TimingMode.StrictPeriodic, Duration(10_000), Duration(10_000))
        assertEquals(Freshness.Fresh, Freshness.of(stamped(0, 100u), Timestamp(10_000), period))
        assertEquals(Freshness.Stale(Duration(500)), Freshness.of(stamped(0, 100u), Timestamp(10_500), period))
    }

    /** ridl §9 (`@[20ms..]`) and frame §8: with no `max`, the value is `Unbounded` whatever its age. */
    @Test
    fun `a timing with no max is unbounded`() {
        assertEquals(
            Freshness.Unbounded,
            Freshness.of(stamped(0, ULong.MAX_VALUE), Timestamp(Long.MAX_VALUE), range.copy(max = null)),
        )
    }

    /** `Member.timing` `null` and frame §8: with no timing, the value is `Unbounded`. */
    @Test
    fun `a member with no timing is unbounded`() {
        assertEquals(Freshness.Unbounded, Freshness.of(stamped(0, 555u), Timestamp(1_000_000_000), null))
    }

    /** From the frame §8 formula: a stamp later than `now` gives a negative age, so `Fresh`. */
    @Test
    fun `a stamp later than now is fresh`() {
        assertEquals(Freshness.Fresh, Freshness.of(stamped(2_000_000, 9u), Timestamp(1_000_000), range))
    }

    /** ridl §3.1: timestamps are `int64` microseconds, so the age must not overflow at the ends of the range. */
    @Test
    fun `extreme timestamps do not overflow`() {
        assertEquals(
            Freshness.Stale(Duration(Long.MAX_VALUE - 500_000)),
            Freshness.of(stamped(Long.MIN_VALUE, ULong.MAX_VALUE), Timestamp(Long.MAX_VALUE), range),
        )
        assertEquals(Freshness.Fresh, Freshness.of(stamped(Long.MAX_VALUE, 0u), Timestamp(Long.MIN_VALUE), range))
    }
}
