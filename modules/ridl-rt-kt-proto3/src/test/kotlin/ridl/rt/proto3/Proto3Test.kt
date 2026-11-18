package ridl.rt.proto3

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ridl.rt.payload.EncodeError
import ridl.rt.payload.Malformed
import ridl.rt.payload.VerifyError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** The unit tests of `ridl_rt::proto3` at editor-v0.20.0. */
class Proto3Test {
    private fun buf(vararg bytes: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun malformed(expected: Malformed, body: () -> Unit) {
        assertEquals(expected, assertThrows(VerifyError.Structure::class.java) { body() }.malformed)
    }

    private fun encoded(size: Int = 32, body: (Writer) -> Unit): ByteBuffer {
        val out = ByteBuffer.allocate(size)
        val writer = Writer(out)
        body(writer)
        val length = writer.finish()
        assertEquals(length, out.position())
        out.flip()
        return out
    }

    /** Rust: `varint_round_trips_at_every_length`. */
    @Test
    fun varintRoundTripsAtEveryLength() {
        val cases = listOf(0uL to 1, 1uL to 1, 127uL to 1, 128uL to 2, 16383uL to 2,
            16384uL to 3, UInt.MAX_VALUE.toULong() to 5, ULong.MAX_VALUE to 10)
        for ((value, length) in cases) {
            assertEquals(length, varintLen(value))
            val bytes = encoded(16) { it.varint(value) }
            assertEquals(length, bytes.remaining())
            assertEquals(value, readVarint(bytes))
            assertEquals(length, bytes.position())
        }
    }

    /** Rust: `zigzag_matches_the_protobuf_table`. */
    @Test
    fun zigzagMatchesTheProtobufTable() {
        for ((signed, value) in listOf(0 to 0u, -1 to 1u, 1 to 2u, -2 to 3u, Int.MIN_VALUE to UInt.MAX_VALUE)) {
            assertEquals(value, zigzag32(signed))
            assertEquals(signed, unzigzag32(value))
            assertEquals(value.toULong(), zigzag64(signed.toLong()))
            assertEquals(signed.toLong(), unzigzag64(value.toULong()))
        }
        assertEquals(ULong.MAX_VALUE, zigzag64(Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, unzigzag64(ULong.MAX_VALUE))
    }

    /** Rust: `an_eleven_byte_varint_is_refused`. */
    @Test
    fun anElevenByteVarintIsRefused() {
        val bytes = ByteBuffer.wrap(ByteArray(11) { if (it == 10) 0 else 0x80.toByte() })
        malformed(Malformed.Varint) { readVarint(bytes) }
        assertEquals(0, bytes.position())
    }

    /** Rust: `a_tenth_byte_above_one_is_refused`. */
    @Test
    fun aTenthByteAboveOneIsRefused() {
        val bytes = ByteBuffer.wrap(ByteArray(10) { 0xff.toByte() })
        bytes.put(9, 2)
        malformed(Malformed.Varint) { readVarint(bytes) }
        assertEquals(0, bytes.position())
        bytes.put(9, 1)
        assertEquals(ULong.MAX_VALUE, readVarint(bytes))
    }

    /** Rust: `a_non_minimal_varint_reads`. */
    @Test
    fun aNonMinimalVarintReads() {
        val bytes = buf(0x80, 0)
        assertEquals(0uL, readVarint(bytes))
        assertEquals(2, bytes.position())
    }

    /** Rust: `a_truncated_varint_is_out_of_bounds`. */
    @Test
    fun aTruncatedVarintIsOutOfBounds() {
        for (bytes in listOf(buf(0x80), buf())) {
            malformed(Malformed.OutOfBounds) { readVarint(bytes) }
            assertEquals(0, bytes.position())
        }
    }

    /** Rust: `the_reader_refuses_field_zero_and_groups_and_wire_types_six_and_seven`. */
    @Test
    fun theReaderRefusesFieldZeroAndGroupsAndWireTypesSixAndSeven() {
        for (tag in listOf(0, 0x0b, 0x0c, 0x0e, 0x0f)) {
            val reader = Reader(buf(tag, 1))
            malformed(Malformed.WireType) { reader.next() }
            assertNull(reader.next())
        }
    }

    /** Rust: `the_reader_reports_a_truncated_length_as_out_of_bounds`. */
    @Test
    fun theReaderReportsATruncatedLengthAsOutOfBounds() {
        val reader = Reader(buf(0x12, 5, 0x61))
        malformed(Malformed.OutOfBounds) { reader.next() }
        assertNull(reader.next())
    }

    /** Rust: `the_reader_yields_each_wire_type`. */
    @Test
    fun theReaderYieldsEachWireType() {
        val reader = Reader(buf(8, 0x96, 1, 0x11, 1, 0, 0, 0, 0, 0, 0, 0,
            0x1a, 2, 0x68, 0x69, 0x25, 2, 0, 0, 0))
        assertEquals(Field(1u, Value.Varint(150uL)), reader.next())
        assertEquals(Field(2u, Value.I64(1uL)), reader.next())
        assertEquals(Field(3u, Value.Len(buf(0x68, 0x69))), reader.next())
        assertEquals(Field(4u, Value.I32(2u)), reader.next())
        assertNull(reader.next())
        assertNull(reader.next())
    }

    /** Rust: `the_reader_refuses_a_tag_wider_than_u32`. */
    @Test
    fun theReaderRefusesATagWiderThanU32() {
        for (value in listOf(ULong.MAX_VALUE, 1uL shl 32)) {
            val reader = Reader(encoded { it.varint(value) })
            malformed(Malformed.Varint) { reader.next() }
            assertNull(reader.next())
        }
    }

    /** Rust: `narrowing_refuses_what_does_not_fit`. */
    @Test
    fun narrowingRefusesWhatDoesNotFit() {
        malformed(Malformed.Varint) { narrowU32(1uL shl 32) }
        assertEquals(UInt.MAX_VALUE, narrowU32(UInt.MAX_VALUE.toULong()))
        malformed(Malformed.Varint) { unzigzag32Checked(UInt.MAX_VALUE.toULong() + 1u) }
        assertEquals(Int.MIN_VALUE, unzigzag32Checked(UInt.MAX_VALUE.toULong()))
        assertEquals(-3, narrowI32((-3L).toULong()))
        malformed(Malformed.Varint) { narrowI32(1uL shl 40) }
        malformed(Malformed.Varint) { narrowI32(UInt.MAX_VALUE.toULong()) }
        assertEquals(Int.MAX_VALUE, narrowI32(Int.MAX_VALUE.toULong()))
        assertEquals(Int.MIN_VALUE, narrowI32(Int.MIN_VALUE.toLong().toULong()))
        malformed(Malformed.Varint) { narrowI32((Int.MIN_VALUE.toLong() - 1).toULong()) }
    }

    /** Rust: `the_writer_writes_a_tag_and_a_packed_run`. */
    @Test
    fun theWriterWritesATagAndAPackedRun() {
        assertEquals(buf(0x7a, 2, 1, 2), encoded(8) {
            it.tag(15u, WireType.Len)
            it.varint(2u)
            it.varint(1u)
            it.varint(2u)
        })
    }

    /** Rust: `the_writer_writes_fixed_widths_and_bytes`. */
    @Test
    fun theWriterWritesFixedWidthsAndBytes() {
        assertEquals(buf(1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 7, 8, 97, 98), encoded(16) {
            it.fixed32(0x04030201u)
            it.fixed64(0x0807060504030201uL)
            it.bytes(buf(97, 98))
        })
    }

    /** Rust: `the_writer_reports_an_overflow_at_finish`. */
    @Test
    fun theWriterReportsAnOverflowAtFinish() {
        val out = ByteBuffer.allocate(3)
        val writer = Writer(out)
        writer.tag(1u, WireType.Varint)
        writer.bytes(buf(1, 2, 3, 4))
        writer.fixed32(7u)
        assertEquals(EncodeError.Capacity(9, 3), assertThrows(EncodeError.Capacity::class.java) { writer.finish() })
        assertEquals(0, out.position())
        assertEquals(buf(8, 1, 2), out)
    }

    /** Rust: `check_capacity_reports_the_shortfall`. */
    @Test
    fun checkCapacityReportsTheShortfall() {
        checkCapacity(4, ByteBuffer.allocate(4))
        assertEquals(EncodeError.Capacity(5, 4), assertThrows(EncodeError.Capacity::class.java) {
            checkCapacity(5, ByteBuffer.allocate(4))
        })
    }

    /** Rust: `utf8_refuses_bad_bytes`. */
    @Test
    fun utf8RefusesBadBytes() {
        assertEquals("hi", utf8(buf(0x68, 0x69)))
        malformed(Malformed.Utf8) { utf8(buf(0xff)) }
    }

    /** Rust: `verified_hands_back_its_bytes`. */
    @Test
    fun verifiedHandsBackItsBytes() {
        assertEquals(buf(1, 2), Verified(buf(1, 2)).bytes)
    }

    /** Rust: `tag_len_counts_the_shifted_number`. */
    @Test
    fun tagLenCountsTheShiftedNumber() {
        for ((number, length) in listOf(1u to 1, 15u to 1, 16u to 2, 2047u to 2, 2048u to 3)) {
            assertEquals(length, tagLen(number))
        }
    }

    @Test
    fun coreRuntimeHasNoProto3Reference() {
        val core = Path.of("../ridl-rt-kt")
        assertTrue(Files.isDirectory(core.resolve("src/main")))
        Files.walk(core.resolve("src")).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach {
                assertFalse(Files.readString(it).contains("ridl.rt.proto3"), it.toString())
            }
        }
        assertFalse(Files.readString(core.resolve("build.gradle.kts")).contains("ridl-rt-kt-proto3"))
    }

    @Test
    fun buffersRespectPositionsLimitsAndByteOrder() {
        for (out in listOf(ByteBuffer.allocate(20), ByteBuffer.allocateDirect(20))) {
            out.order(ByteOrder.BIG_ENDIAN)
            out.position(3)
            out.limit(17)
            val writer = Writer(out)
            writer.fixed32(0x04030201u)
            writer.fixed64(0x0807060504030201uL)
            val source = buf(9, 97, 98, 9)
            source.position(1)
            source.limit(3)
            writer.bytes(source.asReadOnlyBuffer())
            assertEquals(1, source.position())
            assertEquals(3, out.position())
            assertEquals(14, writer.finish())
            assertEquals(17, out.position())
            assertEquals(ByteOrder.BIG_ENDIAN, out.order())
            out.position(3)
            assertEquals(buf(1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 7, 8, 97, 98), out)
        }
        val input = buf(99, 8, 1, 99)
        input.position(1)
        input.limit(3)
        val reader = Reader(input.asReadOnlyBuffer())
        assertEquals(Field(1u, Value.Varint(1uL)), reader.next())
        assertNull(reader.next())
        assertEquals(1, input.position())
    }

    @Test
    fun overflowCountSaturatesWithoutWrapping() {
        val writer = Writer(ByteBuffer.allocate(0))
        val source = ByteBuffer.allocate(1024 * 1024)
        repeat(2049) { writer.bytes(source) }
        assertEquals(EncodeError.Capacity(Int.MAX_VALUE, 0), assertThrows(EncodeError.Capacity::class.java) {
            writer.finish()
        })
    }

    @Test
    fun lengthDelimitedViewsCannotExposeFollowingFields() {
        for (input in listOf(buf(10, 1, 97, 16, 1), buf(10, 0, 16, 1))) {
            val reader = Reader(input)
            val bytes = (reader.next()!!.value as Value.Len).bytes
            val length = bytes.remaining()
            bytes.clear()
            assertEquals(length, bytes.remaining())
            assertTrue(bytes.isReadOnly)
            assertEquals(Field(2u, Value.Varint(1uL)), reader.next())
        }
    }

    @Test
    fun truncatedFixedValuesAndHugeLengthsAreOutOfBounds() {
        for (bytes in listOf(buf(0x11, 1), buf(0x0d, 1), encoded {
            it.tag(1u, WireType.Len)
            it.varint(ULong.MAX_VALUE)
        })) {
            val reader = Reader(bytes)
            malformed(Malformed.OutOfBounds) { reader.next() }
            assertNull(reader.next())
        }
    }

    @Test
    fun utf8RefusesOverlongSurrogateAndTruncatedSequences() {
        for (bytes in listOf(buf(0xc0, 0x80), buf(0xed, 0xa0, 0x80), buf(0xe2, 0x82))) {
            malformed(Malformed.Utf8) { utf8(bytes) }
            assertEquals(0, bytes.position())
        }
    }
}
