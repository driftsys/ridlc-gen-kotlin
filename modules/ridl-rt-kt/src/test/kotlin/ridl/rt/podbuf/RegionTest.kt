package ridl.rt.podbuf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A `Region` is a window that reads 0 past the size the producer wrote. */
class RegionTest {
    private val source = ByteBuffer.wrap(ByteArray(24) { (it + 1).toByte() })

    @Test
    fun `a field a later release appended reads 0 from an older producer's region`() {
        val old = Buffer(source).region(0, 8)
        assertEquals(0x0807060504030201L, old.u64At(0))
        assertEquals(0L, old.u64At(8))
        assertEquals(0, old.u8At(8))
    }

    @Test
    fun `a region starts at its offset in the buffer`() {
        assertEquals(9, Buffer(source).region(8, 8).u8At(0))
    }

    @Test
    fun `floats and signed loads are little-endian whatever the source's order`() {
        val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        b.order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(0, 1.5f).putDouble(8, -2.25).putShort(4, -2)
        b.order(ByteOrder.BIG_ENDIAN)
        val r = Buffer(b).region(0, 16)
        assertEquals(1.5f, r.f32At(0))
        assertEquals(-2.25, r.f64At(8))
        assertEquals((-2).toShort(), r.i16At(4))
    }
}
