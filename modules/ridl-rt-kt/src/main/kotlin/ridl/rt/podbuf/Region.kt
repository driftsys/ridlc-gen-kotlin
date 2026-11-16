package ridl.rt.podbuf

import java.nio.ByteBuffer

/**
 * A window over a buffer that reads 0 past the size the producer wrote.
 * `ridl_rt::podbuf::Region`.
 *
 * [start] is the offset of the window in the buffer and [size] is the number
 * of bytes the producer wrote for it. A load at `off` reads each of its bytes
 * from `buffer[start + off]` when `off` is below [size] and the byte exists,
 * and reads 0 otherwise, so a field that a later release appended reads as 0
 * when an older producer wrote the buffer. No offset makes a load throw.
 *
 * Unsigned 32-bit values are returned as `Long`, unsigned 8- and 16-bit values
 * as `Int`.
 */
public class Region internal constructor(
    private val buf: ByteBuffer,
    private val start: Int,
    private val size: Int,
) {
    /** The byte at [off] as an unsigned value in 0..255, or 0 outside the window. `u8_at`. */
    public fun u8At(off: Int): Int {
        if (off < 0 || off >= size) return 0
        val at = start.toLong() + off
        if (at < 0 || at >= buf.limit()) return 0
        return buf.get(at.toInt()).toInt() and 0xFF
    }

    /** True when all [n] bytes from [off] are inside the window and the buffer. */
    private fun whole(off: Int, n: Int): Boolean {
        if (off < 0 || off.toLong() + n > size) return false
        val from = start.toLong() + off
        return from >= 0 && from + n <= buf.limit()
    }

    /** [n] bytes from [off] as a little-endian value, each read like [u8At]. */
    private fun slow(off: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or u8At(off.plusSat(i)).toLong()
        return v
    }

    public fun i8At(off: Int): Byte = u8At(off).toByte()

    public fun u16At(off: Int): Int =
        if (whole(off, 2)) buf.getShort(start + off).toInt() and 0xFFFF else slow(off, 2).toInt()

    public fun i16At(off: Int): Short = u16At(off).toShort()

    public fun u32At(off: Int): Long =
        if (whole(off, 4)) buf.getInt(start + off).toLong() and 0xFFFF_FFFFL else slow(off, 4)

    public fun i32At(off: Int): Int = u32At(off).toInt()

    public fun u64At(off: Int): Long = if (whole(off, 8)) buf.getLong(start + off) else slow(off, 8)

    public fun i64At(off: Int): Long = u64At(off)

    public fun f32At(off: Int): Float = java.lang.Float.intBitsToFloat(i32At(off))

    public fun f64At(off: Int): Double = java.lang.Double.longBitsToDouble(u64At(off))

    /** The [n] bytes from [off], each read like [u8At]. `bytes_at`. */
    public fun bytesAt(off: Int, n: Int): ByteArray = ByteArray(maxOf(n, 0)) { u8At(off.plusSat(it)).toByte() }
}
