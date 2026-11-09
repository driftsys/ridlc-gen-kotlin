package ridl.rt.podbuf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.payload.EncodeError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** `Writer`, with the Rust tests of `podbuf.rs` that apply. */
class WriterTest {
    private fun dirty(n: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteBuffer {
        val b = ByteBuffer.wrap(ByteArray(n) { 0xAA.toByte() })
        b.order(order)
        return b
    }

    private fun encodeError(body: () -> Unit): EncodeError = assertThrows(EncodeError::class.java) { body() }

    @Test
    fun `the writer with maximum numbers is an error, not another exception`() {
        val w = Writer(dirty(32))
        assertTrue(encodeError { w.tail(Long.MAX_VALUE, Int.MAX_VALUE) } is EncodeError.Capacity)
        assertTrue(encodeError { w.reserveHead(Int.MAX_VALUE) } is EncodeError.Layout)
        assertTrue(encodeError { w.reserveHead(Int.MAX_VALUE - 7) } is EncodeError.Capacity)
        assertTrue(encodeError { w.reserveHead(-8) } is EncodeError.Layout)
        assertEquals(PREAMBLE_SIZE, w.reserveHead(8))
        assertTrue(encodeError { w.tail(Long.MAX_VALUE, Int.MAX_VALUE) } is EncodeError.Capacity)
        assertTrue(encodeError { w.tail(Long.MAX_VALUE, 8) } is EncodeError.Capacity)
        assertTrue(encodeError { w.tail(1, Int.MAX_VALUE) } is EncodeError.Capacity)
        assertTrue(encodeError { w.tail(-1, 8) } is EncodeError.Layout)
        assertEquals(16, w.finish())
    }

    @Test
    fun `finish reports the first store outside the written bytes as Layout(at)`() {
        val out = dirty(32)
        val w = Writer(out)
        w.reserveHead(8)
        w.putU32(14, 0xFFFF_FFFFL) // 14..18 passes the written 16 bytes
        w.putU8(Int.MAX_VALUE, 1) // a later defect does not replace the first
        w.putU8(-1, 1)
        assertEquals(EncodeError.Layout(14), encodeError { w.finish() })
        assertEquals(0xAA.toByte(), out.array()[18], "the dropped store wrote nothing")
        assertEquals(0, out.position(), "a failed finish leaves the position")
    }

    @Test
    fun `reserveHead refuses a size that is not a multiple of 8`() {
        val w = Writer(dirty(64))
        assertEquals(EncodeError.Layout(12), encodeError { w.reserveHead(12) })
        assertEquals(PREAMBLE_SIZE, w.reserveHead(16))
    }

    @Test
    fun `reserveHead reports a head that does not fit as Capacity`() {
        val w = Writer(dirty(16))
        assertEquals(EncodeError.Capacity(24, 16), encodeError { w.reserveHead(16) })
    }

    @Test
    fun `putBytes stores a slice inside the reserved tail`() {
        val out = dirty(32)
        val w = Writer(out)
        w.reserveHead(8)
        val at = w.tail(3, 1)
        w.putBytes(at, "ab".toByteArray())
        assertEquals(19, w.finish())
        assertArrayEquals(byteArrayOf(97, 98, 0), out.array().copyOfRange(16, 19))
        assertEquals(19, out.position())
    }

    @Test
    fun `tailData writes the data and a zero terminator over dirty output`() {
        val out = dirty(64)
        val w = Writer(out)
        w.reserveHead(8)
        val a = w.tailData("abc".toByteArray(), true)
        val b = w.tailData("xy".toByteArray(), false)
        val c = w.tailData(byteArrayOf(), true)
        assertEquals(listOf(16, 20, 22), listOf(a, b, c))
        assertEquals(23, w.finish())
        assertArrayEquals("abc\u0000xy\u0000".toByteArray(), out.array().copyOfRange(16, 23))
    }

    @Test
    fun `tailData refuses a block that does not fit and leaves the writer usable`() {
        val w = Writer(dirty(24))
        w.reserveHead(8)
        assertEquals(EncodeError.Capacity(25, 24), encodeError { w.tailData("abcdefgh".toByteArray(), true) })
        assertEquals(16, w.tailData("abcdefg".toByteArray(), true))
        encodeError { w.tailData(byteArrayOf(), true) }
        w.tailData(byteArrayOf(), false)
    }

    @Test
    fun `a second reserve starts the buffer over`() {
        val w = Writer(dirty(32))
        w.reserveHead(8)
        w.tail(8, 8)
        w.reserveHead(16)
        assertEquals(24, w.finish())
    }

    @Test
    fun `finish without a reserved head is Capacity`() {
        assertEquals(EncodeError.Capacity(PREAMBLE_SIZE, 32), encodeError { Writer(dirty(32)).finish() })
    }

    @Test
    fun `the writer zeroes reserved bytes it does not write`() {
        val out = dirty(64)
        val w = Writer(out)
        w.reserveHead(16)
        w.putU8(8, 7)
        val at = w.tail(5, 8)
        assertEquals(24, at)
        assertEquals(29, w.finish())
        val a = out.array()
        assertEquals(16, a[0].toInt())
        for (i in 1..7) assertEquals(0, a[i].toInt(), "preamble byte $i")
        assertEquals(7, a[8].toInt())
        for (i in 9 until 29) assertEquals(0, a[i].toInt(), "byte $i")
        assertEquals(0xAA.toByte(), a[29], "past the end is untouched")
    }

    @Test
    fun `the writer starts at the output's position and advances it`() {
        val a = ByteArray(40) { 0x55 }
        val out = ByteBuffer.wrap(a)
        out.position(8)
        val w = Writer(out)
        w.reserveHead(8)
        w.putU64(8, 0x0102030405060708L)
        assertEquals(16, w.finish())
        assertEquals(24, out.position())
        assertEquals(8, a[8].toInt())
        assertEquals(8, a[16].toInt())
        assertEquals(1, a[23].toInt())
        assertEquals(0x55, a[0].toInt())
    }

    @Test
    fun `descriptors and every width are stored little-endian`() {
        val out = dirty(64)
        val w = Writer(out)
        w.reserveHead(56)
        w.putDesc(8, Desc(0xFFFF_FFFEL, 3))
        w.putArrayDesc(16, ArrayDesc(5, 0xFFFF, 4))
        w.putI8(24, -1)
        w.putI16(26, -2)
        w.putI32(28, -3)
        w.putI64(32, -4)
        w.putF32(40, 1.5f)
        w.putF64(48, -2.25)
        w.putU16(56, 0xABCD)
        assertEquals(64, w.finish())
        val r = Buffer(out.also { it.position(0) }).region(8, 56)
        assertEquals(Desc(0xFFFF_FFFEL, 3), Desc.read(r, 0))
        assertEquals(ArrayDesc(5, 0xFFFF, 4), ArrayDesc.read(r, 8))
        assertEquals((-1).toByte(), r.i8At(16))
        assertEquals((-2).toShort(), r.i16At(18))
        assertEquals(-3, r.i32At(20))
        assertEquals(-4L, r.i64At(24))
        assertEquals(1.5f, r.f32At(32))
        assertEquals(-2.25, r.f64At(40))
        assertEquals(0xABCD, r.u16At(48))
    }

    @Test
    fun `no store depends on the caller's byte order`() {
        val little = dirty(32, ByteOrder.LITTLE_ENDIAN)
        val big = dirty(32, ByteOrder.BIG_ENDIAN)
        for (b in listOf(little, big)) {
            val w = Writer(b)
            w.reserveHead(24)
            w.putU16(8, 0x0102)
            w.putU32(12, 0x03040506L)
            w.putU64(16, 0x0708090A0B0C0D0EL)
            w.putF32(24, 1.5f)
            w.finish()
        }
        assertArrayEquals(little.array(), big.array())
        assertEquals(0x02, big.array()[8].toInt())
        assertEquals(ByteOrder.BIG_ENDIAN, big.order())
    }
}
