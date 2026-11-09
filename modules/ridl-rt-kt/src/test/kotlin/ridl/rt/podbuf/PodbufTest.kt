package ridl.rt.podbuf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.payload.Malformed
import ridl.rt.payload.VerifyError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** `ridl_rt::podbuf`'s free functions and `Preamble`, with the Rust tests of `podbuf.rs` that apply. */
class PodbufTest {
    private fun malformed(kind: Malformed, body: () -> Unit) {
        val e = assertThrows(VerifyError.Structure::class.java) { body() }
        assertEquals(kind, e.malformed)
    }

    private fun bytes(vararg v: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(v.size) { v[it].toByte() })

    @Test
    fun `a preamble reads its head size and ignores the reserved word`() {
        val b = bytes(16, 0, 0, 0, 0xAA, 0xBB, 0xCC, 0xDD, *IntArray(16))
        assertEquals(Preamble(16), Preamble.read(Buffer(b)))
    }

    @Test
    fun `a preamble is refused when short, ragged or reaching past the buffer`() {
        malformed(Malformed.Preamble) { Preamble.read(Buffer(bytes(0, 0, 0))) }
        malformed(Malformed.Preamble) { Preamble.read(Buffer(bytes(12, 0, 0, 0, 0, 0, 0, 0, *IntArray(16)))) }
        malformed(Malformed.Preamble) { Preamble.read(Buffer(bytes(16, 0, 0, 0, 0, 0, 0, 0, *IntArray(8)))) }
        malformed(Malformed.Preamble) { Preamble.read(Buffer(bytes(0xF8, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0))) }
        malformed(Malformed.Preamble) { Preamble.read(Buffer(bytes(0xF8, 0xFF, 0xFF, 0x7F, 0, 0, 0, 0))) }
    }

    @Test
    fun `a buffer at position 8 of a larger array reads its preamble at that position`() {
        val a = ByteArray(32)
        a[8] = 8
        val b = ByteBuffer.wrap(a)
        b.position(8)
        val buf = Buffer(b)
        assertEquals(24, buf.size)
        assertEquals(Preamble(8), Preamble.read(buf))
        assertEquals(8, b.position(), "the source's position is left where it was")
    }

    @Test
    fun `a buffer at position 4 is Unaligned`() {
        val b = ByteBuffer.wrap(ByteArray(32))
        b.position(4)
        malformed(Malformed.Unaligned) { Preamble.read(Buffer(b)) }
        val w = Writer(b)
        assertEquals(
            ridl.rt.payload.EncodeError.Unaligned,
            assertThrows(ridl.rt.payload.EncodeError::class.java) { w.reserveHead(8) },
        )
    }

    @Test
    fun `a region reads 0 at and past its size, and past the buffer's end`() {
        val buf = Buffer(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16))
        val r = buf.region(4, 8)
        assertEquals(5, r.u8At(0))
        assertEquals(0, r.u8At(8))
        assertEquals(0, r.u8At(Int.MAX_VALUE))
        assertEquals(0, r.u8At(-1))
        val past = buf.region(12, 100)
        assertEquals(13, past.u8At(0))
        assertEquals(16, past.u8At(3))
        assertEquals(0, past.u8At(4))
        assertEquals(0L, past.u64At(Int.MAX_VALUE))
        assertEquals(0, buf.region(Int.MAX_VALUE, Int.MAX_VALUE).u8At(Int.MAX_VALUE))
    }

    @Test
    fun `region loads with buffer-derived numbers do not throw`() {
        val buf = Buffer(ByteBuffer.wrap(ByteArray(32) { -1 }))
        val big = listOf(Int.MAX_VALUE, Int.MAX_VALUE - 3, Int.MIN_VALUE, -1)
        for (start in big) for (size in big) {
            val r = buf.region(start, size)
            for (off in big) {
                r.u8At(off); r.u16At(off); r.u32At(off); r.u64At(off)
                r.i64At(off); r.f32At(off); r.f64At(off)
                Desc.read(r, off); ArrayDesc.read(r, off)
                assertEquals(3, r.bytesAt(off, 3).size)
            }
        }
        assertEquals(0L, buf.region(Int.MAX_VALUE, Int.MAX_VALUE).u64At(Int.MAX_VALUE))
    }

    @Test
    fun `a load that crosses the window or the buffer reads zero past the end`() {
        val buf = Buffer(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, *IntArray(16)))
        val r = buf.region(4, 8)
        assertEquals(0x08070605L, r.u32At(0))
        assertEquals(0x0C0B0A0908070605L, r.u64At(0))
        assertEquals(0x0C0B, r.u16At(6))
        // Crossing the window end: the buffer's bytes 12 and 13 do not leak in.
        assertEquals(0x0000_0C0BL, r.u32At(6))
        assertEquals(0x0C, r.u16At(7))
        assertEquals(0, r.u16At(8))
        assertEquals(0L, r.u64At(100))
        // A window that extends past the buffer.
        val short = Buffer(bytes(1, 2, 3, 4, 5, 6, 7, 8)).region(4, 100)
        assertEquals(0x08070605L, short.u64At(0))
        assertEquals(0x0807L, short.u32At(2))
        assertEquals(0.toShort(), short.i16At(4))
        // Every load shares the path.
        val all = buf.region(0, 16)
        assertEquals(0x08070605, all.i32At(4))
        assertEquals(0x100F0E0D0C0B0A09L, all.i64At(8))
        assertEquals(java.lang.Float.intBitsToFloat(0x04030201), all.f32At(0))
        assertEquals(java.lang.Double.longBitsToDouble(0x100F0E0D0C0B0A09L), all.f64At(8))
        assertEquals(0x100F, all.i32At(14)) // bytes 15 and 16, then zeros past the window
        assertEquals((-1).toByte(), Buffer(bytes(0xFF)).region(0, 1).i8At(0))
    }

    @Test
    fun `bytesAt reads each byte like u8At`() {
        val r = Buffer(bytes(1, 2, 3, 4)).region(2, 2)
        assertEquals(listOf<Byte>(3, 4, 0, 0), r.bytesAt(0, 4).toList())
    }

    @Test
    fun `descriptors read and report zero`() {
        val r = Buffer(bytes(0xFF, 0xFF, 0xFF, 0xFF, 5, 0, 0, 0, 1, 0, 0, 0, 0xFF, 0xFF, 2, 0)).region(0, 16)
        assertEquals(Desc(0xFFFF_FFFFL, 5L), Desc.read(r, 0))
        assertEquals(ArrayDesc(1L, 0xFFFF, 2), ArrayDesc.read(r, 8))
        assertTrue(Desc(0, 0).isZero())
        assertFalse(Desc(0, 1).isZero())
        assertTrue(ArrayDesc(0, 0, 0).isZero())
        assertFalse(ArrayDesc(0, 0, 1).isZero())
        assertEquals(Desc(0, 0), Desc.read(r, 100))
    }

    @Test
    fun `tailRange accepts a range and refuses one outside the tail or the buffer`() {
        assertEquals(8..<8, tailRange(8, 0, 8, 8, 0))
        assertEquals(16..<19, tailRange(16, 3, 32, 16, 1))
        malformed(Malformed.OutOfBounds) { tailRange(8, 1, 32, 16, 1) }
        malformed(Malformed.OutOfBounds) { tailRange(16, 17, 32, 16, 1) }
        malformed(Malformed.Unaligned) { tailRange(17, 4, 32, 16, 8) }
        assertEquals(24..<28, tailRange(24, 4, 32, 16, 8))
    }

    @Test
    fun `tailRange with maximum numbers is an error, not another exception`() {
        for (off in listOf(0L, 1L, 0xFFFF_FFF8L, 0xFFFF_FFFFL)) {
            for (bytes in listOf(0L, 1L, 0xFFFF_FFFFL, Long.MAX_VALUE - 1, Long.MAX_VALUE, -1L)) {
                for (align in listOf(0, 1, 8, Int.MAX_VALUE, -3)) {
                    for ((len, min) in listOf(Int.MAX_VALUE to Int.MAX_VALUE, 64 to 16, 0 to 0)) {
                        try {
                            tailRange(off, bytes, len, min, align)
                        } catch (_: VerifyError.Structure) {
                        }
                    }
                }
            }
        }
        malformed(Malformed.OutOfBounds) { tailRange(8, Long.MAX_VALUE, Int.MAX_VALUE, 8, 8) }
    }

    @Test
    fun `tailRange refuses an offset or length that overflows, as OutOfBounds`() {
        malformed(Malformed.OutOfBounds) { tailRange(0xFFFF_FFFFL, 16, Int.MAX_VALUE, 0, 1) }
        malformed(Malformed.OutOfBounds) { tailRange(8, 0xFFFF_FFFFL * 0xFFFF, Int.MAX_VALUE, 8, 1) }
        malformed(Malformed.OutOfBounds) { tailRange(0xFFFF_FFFFL, 16, 64, 0, 1) }
    }

    @Test
    fun `text accepts a terminated string and refuses the rest`() {
        assertEquals("abc", text("abc\u0000".toByteArray()))
        assertEquals("é€😀", text("é€😀\u0000".toByteArray()))
        assertEquals("", text(byteArrayOf(0)))
        malformed(Malformed.Terminator) { text(byteArrayOf()) }
        malformed(Malformed.Terminator) { text("abc".toByteArray()) }
        malformed(Malformed.Terminator) { text(byteArrayOf(97, 0, 98, 0)) }
        malformed(Malformed.Utf8) { text(byteArrayOf(0xC3.toByte(), 0)) }
        malformed(Malformed.Utf8) { text(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0)) }
        malformed(Malformed.Utf8) { text(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0)) }
    }

    @Test
    fun `hasNul finds a nul at every position and length`() {
        val fill = intArrayOf(1, 2, 0x7f, 0x80, 0xff)
        val data = ByteArray(200) { fill[it % 5].toByte() }
        for (len in 0 until 200) {
            assertFalse(hasNul(data.copyOf(len)), "len $len")
            for (at in 0 until len) {
                val copy = data.copyOf(len)
                copy[at] = 0
                assertTrue(hasNul(copy), "len $len at $at")
            }
        }
    }

    @Test
    fun `scalarCount counts scalar values not units`() {
        assertEquals(0, scalarCount(""))
        assertEquals(2, scalarCount("ab"))
        assertEquals(3, scalarCount("é€😀"))
    }

    @Test
    fun `scalarCount counts a surrogate pair once`() {
        assertEquals("😀".length, 2)
        assertEquals(1, scalarCount("😀"))
    }

    @Test
    fun `no load depends on the caller's byte order`() {
        val b = ByteBuffer.wrap(byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)).order(ByteOrder.BIG_ENDIAN)
        val buf = Buffer(b)
        assertEquals(Preamble(8), Preamble.read(buf))
        val r = buf.region(8, 8)
        assertEquals(0x0201, r.u16At(0))
        assertEquals(0x04030201L, r.u32At(0))
        assertEquals(0x0807060504030201L, r.u64At(0))
        assertEquals(0x04030201, r.i32At(0))
        assertEquals(ByteOrder.BIG_ENDIAN, b.order(), "the caller's order is left alone")
    }
}
