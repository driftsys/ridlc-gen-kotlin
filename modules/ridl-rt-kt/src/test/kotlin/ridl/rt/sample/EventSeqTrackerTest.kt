package ridl.rt.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal

/**
 * `EventSeqTracker`: event loss from the envelope's sequence number, tracked
 * per channel (ridl §3.1; frame specification §5.2, §6.2 and §7).
 * `crates/ridl-rt/tests/event_seq.rs` of ridl `main` (story E11.19), case for
 * case, except `a_tracker_can_be_built_in_a_const_context`, which has no JVM
 * spelling.
 */
class EventSeqTrackerTest {
    private val iface = InterfaceNo(1u)
    private val otherIface = InterfaceNo(2u)
    private val shiftDone = Ordinal(2u)
    private val doorOpened = Ordinal(3u)

    /** Frame §6.2, §5.2 and §7: the first occurrence a consumer sees may carry any `seq`. */
    @Test
    fun `the first occurrence of a channel reports no loss`() {
        assertEquals(Continuity.First, EventSeqTracker(2).observe(iface, shiftDone, 41u))
    }

    /** ridl §3.1 and frame §7: `last + 1` is the next occurrence with nothing lost. */
    @Test
    fun `consecutive seqs report no loss`() {
        val tracker = EventSeqTracker(2)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 2u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 3u))
    }

    /** ridl §3.1 and frame §5.2 and §7: a gap is a loss of as many occurrences as it is wide. */
    @Test
    fun `a gap is reported as a loss of its width`() {
        val tracker = EventSeqTracker(2)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        assertEquals(Continuity.Lost(1u), tracker.observe(iface, shiftDone, 3u))
        assertEquals(Continuity.Lost(6u), tracker.observe(iface, shiftDone, 10u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 11u))
    }

    /** ridl §3.1 and frame §7: the counter is per channel, so interleaved channels are not a loss. */
    @Test
    fun `interleaved channels of one interface report no loss`() {
        val tracker = EventSeqTracker(2)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        assertEquals(Continuity.First, tracker.observe(iface, doorOpened, 1u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 2u))
        assertEquals(Continuity.Next, tracker.observe(iface, doorOpened, 2u))
        assertEquals(Continuity.Lost(1u), tracker.observe(iface, doorOpened, 4u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 3u))
    }

    /** Frame §3 and §6.2: one ordinal in two interfaces is two channels. */
    @Test
    fun `one ordinal in two interfaces is two channels`() {
        val tracker = EventSeqTracker(2)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 5u))
        assertEquals(Continuity.First, tracker.observe(otherIface, shiftDone, 9u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 6u))
        assertEquals(Continuity.Next, tracker.observe(otherIface, shiftDone, 10u))
    }

    /** Frame §5.2 and §7: a `seq` not greater than the last is reported, and the last is kept. */
    @Test
    fun `a seq not newer than the last is reported and leaves the last unchanged`() {
        val tracker = EventSeqTracker(2)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 5u))
        assertEquals(Continuity.NotNewer(5u), tracker.observe(iface, shiftDone, 5u))
        assertEquals(Continuity.NotNewer(5u), tracker.observe(iface, shiftDone, 3u))
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 6u))
    }

    /** The storage is the caller's: a channel beyond the capacity is refused, and the others keep their count. */
    @Test
    fun `a channel beyond the capacity is refused`() {
        val tracker = EventSeqTracker(1)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        assertThrows<TrackerFull> { tracker.observe(iface, doorOpened, 1u) }
        assertEquals(Continuity.Next, tracker.observe(iface, shiftDone, 2u))
    }

    /** Frame §6.2: a forgotten channel starts again at `First`, and its slot is free for another channel. */
    @Test
    fun `a forgotten channel starts again and frees its slot`() {
        val tracker = EventSeqTracker(1)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        tracker.forget(iface, shiftDone)
        assertEquals(Continuity.First, tracker.observe(iface, doorOpened, 7u))
        tracker.forget(iface, doorOpened)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 9u))
    }

    /** Frame §3: a new tracker holds no channel. */
    @Test
    fun `a default tracker holds no channel`() {
        assertEquals(Continuity.First, EventSeqTracker(1).observe(iface, shiftDone, 3u))
    }

    /** Frame §2 and §4: `seq` is 64 bits unsigned, and the largest value follows its predecessor with no overflow. */
    @Test
    fun `the largest seq follows its predecessor`() {
        val tracker = EventSeqTracker(1)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 0u))
        assertEquals(Continuity.Lost(ULong.MAX_VALUE - 1u), tracker.observe(iface, shiftDone, ULong.MAX_VALUE))
        assertEquals(Continuity.NotNewer(ULong.MAX_VALUE), tracker.observe(iface, shiftDone, ULong.MAX_VALUE))
    }

    /** ridl §3.1 and frame §7: forgetting one channel leaves every other channel's last `seq` in place. */
    @Test
    fun `forgetting one channel leaves the others counting`() {
        val tracker = EventSeqTracker(2)
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        assertEquals(Continuity.First, tracker.observe(iface, doorOpened, 4u))
        tracker.forget(iface, shiftDone)
        assertEquals(Continuity.Next, tracker.observe(iface, doorOpened, 5u))
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 8u))
    }

    /** Frame §3 and §6.2: forgetting a channel of one interface leaves the same ordinal in another in place. */
    @Test
    fun `forgetting a channel leaves the same ordinal in another interface`() {
        val tracker = EventSeqTracker(2)
        // otherIface's channel is observed first, so it holds the first slot.
        assertEquals(Continuity.First, tracker.observe(otherIface, shiftDone, 1u))
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 1u))
        tracker.forget(iface, shiftDone)
        assertEquals(Continuity.Next, tracker.observe(otherIface, shiftDone, 2u))
        assertEquals(Continuity.First, tracker.observe(iface, shiftDone, 9u))
    }
}
