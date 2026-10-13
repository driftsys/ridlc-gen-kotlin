package ridl.rt.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.encoding.Encoding

/**
 * The size table per codec: [Sizes], [reservation] and [tableBudget].
 * `crates/ridl-rt/tests/sizes.rs` of ridl 0.10.0, case for case, but for
 * `a_static_table_is_sized_from_the_budget`, which tests `const fn` and has
 * no Kotlin spelling. The rows are numbers here: the Rust fixture writes them
 * as `MAX_SIZE` paths of fixture codecs, which this module has none of.
 */
class SizesTest {
    private val cabinRef = CatalogRef("cabin", CatalogHash(ByteArray(32) { 3 }))

    private fun member(ordinal: UInt, kind: Kind, name: String, vararg payloads: String) =
        Member(Ordinal(ordinal), kind, name, null, payloads.map(::PayloadInfo))

    private val warning = member(1u, Kind.Event, "warning", "Warning")
    private val average = member(2u, Kind.Query, "average", "Window", "Warning")
    private val setWindow = member(3u, Kind.Command, "setWindow", "Window")

    /** An interface whose one size table is FlatBuffers, as a package with one codec generates it. */
    private class Iface(
        override val catalog: CatalogRef,
        override val number: InterfaceNo,
        override val name: String,
        override val members: List<Member>,
        private val table: Sizes,
    ) : Interface {
        override val provisional: Boolean get() = false

        override fun sizes(encoding: Encoding): Sizes {
            require(encoding == table.encoding) { "interface `$name` has no ${encoding.encodingName} codec" }
            return table
        }
    }

    private class Descriptor(override val iface: Interface, override val row: Int) : Interaction {
        override val member: Member get() = iface.members[row]
    }

    private fun table(rows: List<ULong>, maxBuffer: Int = 0, eventSource: Int = 0) =
        Sizes(Encoding.FlatBuffers, rows, maxBuffer, eventSource)

    /** `interface Cabin`: an event of 8 bytes, a query of 24 + 8 bytes and a command of 24 bytes, so the three rows are distinct. */
    private val cabin = Iface(
        cabinRef, InterfaceNo(1u), "Cabin", listOf(warning, average, setWindow),
        table(listOf(8uL, 24uL + 8uL, 24uL), maxBuffer = 24, eventSource = 8),
    )
    private val cabinWarning = Descriptor(cabin, 0)
    private val cabinAverage = Descriptor(cabin, 1)
    private val cabinSetWindow = Descriptor(cabin, 2)

    /** `interface Huge`: two rows whose sum overflows a `ULong`. */
    private val huge = Iface(cabinRef, InterfaceNo(2u), "Huge", listOf(setWindow, warning), table(listOf(ULong.MAX_VALUE, 1uL)))

    /** `interface Empty`: no member, so no row. */
    private val empty = Iface(cabinRef, InterfaceNo(3u), "Empty", emptyList(), table(emptyList()))

    /** `interface Wide`: one query whose request and reply are each as large as a `UInt` states. */
    private val wide = Iface(
        cabinRef, InterfaceNo(4u), "Wide", listOf(average),
        table(listOf(UInt.MAX_VALUE.toULong() + UInt.MAX_VALUE.toULong())),
    )

    /** `Interaction.row` and `Sizes.reservations`: the query's row is the sum of its two payloads. */
    @Test
    fun `a reservation is the interaction's row`() {
        assertEquals(32uL, reservation(cabinAverage, Encoding.FlatBuffers))
        assertEquals(8uL, reservation(cabinWarning, Encoding.FlatBuffers))
        assertEquals(24uL, reservation(cabinSetWindow, Encoding.FlatBuffers))
    }

    /** `row` indexes the member `member` is, for every descriptor of the interface. */
    @Test
    fun `a row indexes the descriptor's member`() {
        for (x in listOf(cabinWarning, cabinAverage, cabinSetWindow)) assertEquals(x.member, x.iface.members[x.row], x.member.name)
    }

    /** `tableBudget` sums every row: three distinct rows, so a maximum or a first row gives another number. */
    @Test
    fun `a table budget sums every row`() {
        assertEquals(64uL, tableBudget(cabin, Encoding.FlatBuffers))
    }

    @Test
    fun `an empty table has a budget of zero`() {
        assertEquals(0uL, tableBudget(empty, Encoding.FlatBuffers))
    }

    /** A row is a `ULong`, wider than one `UInt` size. */
    @Test
    fun `the sum is wider than one size`() {
        assertEquals(2uL * UInt.MAX_VALUE.toULong(), reservation(Descriptor(wide, 0), Encoding.FlatBuffers))
        assertEquals(2uL * UInt.MAX_VALUE.toULong(), tableBudget(wide, Encoding.FlatBuffers))
    }

    @Test
    fun `a table budget saturates`() {
        assertEquals(ULong.MAX_VALUE, tableBudget(huge, Encoding.FlatBuffers))
    }

    /** `maxBufferSize` is the largest call payload and `eventSourceBufferSize` the largest event payload. */
    @Test
    fun `the claim and event buffers have the interface maxima`() {
        val sizes = cabin.sizes(Encoding.FlatBuffers)
        assertEquals(24 to 8, sizes.maxBufferSize to sizes.eventSourceBufferSize)
    }

    /** `reservations` is parallel to `Interface.members`, so a data-driven runtime registers the two lists together. */
    @Test
    fun `a data-driven runtime registers the list`() {
        val rows = cabin.sizes(Encoding.FlatBuffers).reservations
        assertEquals(cabin.members.size, rows.size)
        assertEquals(64uL, rows.fold(0uL, ULong::plus))
    }

    /** An encoding the package carries no codec for has no table: Rust refuses `Cabin: Sizes<Proto3>` at compile time. */
    @Test
    fun `an encoding with no codec has no table`() {
        assertThrows<IllegalArgumentException> { tableBudget(cabin, Encoding.Proto3) }
        assertThrows<IllegalArgumentException> { reservation(cabinAverage, Encoding.ReprC) }
    }
}
