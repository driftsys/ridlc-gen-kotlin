package ridl.rt.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.encoding.Encoding

/**
 * The in-flight budget from the descriptors: `Member.reservation` and
 * `tableBudget`. No specification defines this budget; it is derived from
 * `PayloadInfo.maxSize` and `Member.payloads`, and each test cites the
 * descriptor field it reads. `crates/ridl-rt/tests/budget.rs` of ridl `main`
 * (story E11.19), case for case.
 */
class BudgetTest {
    private fun sizes(proto3: UInt, flatbuffers: UInt, reprC: UInt) = EncodedSizes(proto3, flatbuffers, reprC)

    private fun member(ordinal: UInt, kind: Kind, name: String, vararg payloads: PayloadInfo) =
        Member(Ordinal(ordinal), kind, name, null, payloads.toList())

    private val temperature = member(1u, Kind.Signal, "temperature", PayloadInfo("Celsius", sizes(5u, 16u, 4u)))
    private val setTarget = member(2u, Kind.Command, "setTarget", PayloadInfo("Target", sizes(7u, 24u, 8u)))
    private val history = member(
        3u, Kind.Query, "history",
        PayloadInfo("HistoryRequest", sizes(11u, 32u, 12u)),
        PayloadInfo("HistoryReply", sizes(300u, 520u, 256u)),
    )
    private val fanStarted = member(4u, Kind.Event, "fanStarted", PayloadInfo("FanSpeed", sizes(3u, 12u, 2u)))
    private val zoneCount = member(5u, Kind.Fixed, "zoneCount", PayloadInfo("Count", sizes(2u, 10u, 1u)))

    /** `interface Climate`, one member of each kind, every payload sized in every encoding. */
    private val climate = listOf(temperature, setTarget, history, fanStarted, zoneCount)

    /** A query whose reply has no FlatBuffers size. */
    private val unsizedReply = member(
        4u, Kind.Query, "diagnose",
        PayloadInfo("DiagnoseRequest", sizes(2u, 8u, 1u)),
        PayloadInfo("DiagnoseReply", EncodedSizes(40u, null, null)),
    )

    /** A query neither of whose payloads has a FlatBuffers size. */
    private val unsizedBoth = member(
        5u, Kind.Query, "selfTest",
        PayloadInfo("SelfTestRequest", EncodedSizes(6u, null, 4u)),
        PayloadInfo("SelfTestReply", EncodedSizes(9u, null, null)),
    )

    /**
     * `interface Diagnostics`, not in ordinal order: the first unsized member
     * in this order (ordinal 5) is not the lowest-ordinal one (ordinal 4).
     */
    private val diagnostics = listOf(temperature, setTarget, unsizedBoth, unsizedReply)

    /** `Member.payloads` and `PayloadInfo.maxSize`: a one-payload member reserves that payload's size. */
    @Test
    fun `a one payload member reserves its payload size`() {
        assertEquals(16uL, temperature.reservation(Encoding.FlatBuffers))
        assertEquals(24uL, setTarget.reservation(Encoding.FlatBuffers))
    }

    /** `Member.payloads` (the request then the reply): a query reserves the sum of both. */
    @Test
    fun `a query reserves its request and its reply`() {
        assertEquals((32 + 520).toULong(), history.reservation(Encoding.FlatBuffers))
    }

    /** `EncodedSizes`: each encoding reads its own field. */
    @Test
    fun `each encoding reads its own size`() {
        assertEquals((11 + 300).toULong(), history.reservation(Encoding.Proto3))
        assertEquals((32 + 520).toULong(), history.reservation(Encoding.FlatBuffers))
        assertEquals((12 + 256).toULong(), history.reservation(Encoding.ReprC))
    }

    /** `EncodedSizes`: a `null` size is reported with its member and payload, never guessed. */
    @Test
    fun `an unsized payload is reported with its member`() {
        val error = assertThrows<Unsized> { unsizedReply.reservation(Encoding.FlatBuffers) }
        assertEquals(Ordinal(4u), error.ordinal)
        assertEquals("diagnose", error.member)
        assertEquals("DiagnoseReply", error.typeName)
        assertEquals((2 + 40).toULong(), unsizedReply.reservation(Encoding.Proto3))
    }

    /** `Member.payloads` and `EncodedSizes`: of two unsized payloads, the first in order is reported. */
    @Test
    fun `the first unsized payload of a member is reported`() {
        val error = assertThrows<Unsized> { unsizedBoth.reservation(Encoding.FlatBuffers) }
        assertEquals(Ordinal(5u), error.ordinal)
        assertEquals("selfTest", error.member)
        assertEquals("SelfTestRequest", error.typeName)
        assertEquals("SelfTestReply", assertThrows<Unsized> { unsizedBoth.reservation(Encoding.ReprC) }.typeName)
    }

    /** A member with no payload row reserves nothing. */
    @Test
    fun `a member with no payload reserves nothing`() {
        assertEquals(0uL, temperature.copy(payloads = emptyList()).reservation(Encoding.ReprC))
    }

    /** `Interface.members`: a table budget sums every member, whatever its kind. */
    @Test
    fun `a table budget sums every member`() {
        assertEquals((16 + 24 + (32 + 520) + 12 + 10).toULong(), tableBudget(climate, Encoding.FlatBuffers))
        assertEquals((5 + 7 + (11 + 300) + 3 + 2).toULong(), tableBudget(climate, Encoding.Proto3))
    }

    /** `Interface.members` and `EncodedSizes`: the first unsized member in the order given is reported. */
    @Test
    fun `a table budget reports the first unsized member`() {
        val error = assertThrows<Unsized> { tableBudget(diagnostics, Encoding.FlatBuffers) }
        assertEquals(Ordinal(5u), error.ordinal)
        assertEquals("selfTest", error.member)
        assertEquals("SelfTestRequest", error.typeName)
        assertEquals((5 + 7 + (6 + 9) + (2 + 40)).toULong(), tableBudget(diagnostics, Encoding.Proto3))
    }

    /** `Interface.members` may be empty: its budget is zero. */
    @Test
    fun `an empty table has a budget of zero`() {
        assertEquals(0uL, tableBudget(emptyList(), Encoding.ReprC))
    }

    /** `PayloadInfo.maxSize` holds 32-bit sizes; their sum is 64 bits, so two largest sizes do not overflow. */
    @Test
    fun `the sum is wider than one size`() {
        val largest = history.copy(
            payloads = listOf(
                PayloadInfo("A", sizes(UInt.MAX_VALUE, UInt.MAX_VALUE, UInt.MAX_VALUE)),
                PayloadInfo("B", sizes(UInt.MAX_VALUE, UInt.MAX_VALUE, UInt.MAX_VALUE)),
            ),
        )
        assertEquals(2uL * UInt.MAX_VALUE.toULong(), largest.reservation(Encoding.Proto3))
    }
}
