package ridl.rt.podbuf

import ridl.rt.payload.EncodeError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a buffer into the caller's [ByteBuffer], from its position.
 * `ridl_rt::podbuf::Writer`. Every byte it reserves is either zeroed or
 * written by the encoder, so no byte of the output before the end of the buffer
 * keeps an older value.
 *
 * The encoder reserves the head with [reserveHead], stores the head fields with
 * the `put*` methods, and appends the tail blocks in the order of the head's
 * fields, depth first: [tailData] for a string or a bytes field, [tail] for any
 * other block. A tail block starts at its alignment; the gap before it is
 * zero-filled.
 *
 * A store outside the written bytes is a defect of the encoder that issued it.
 * The writer drops the store and remembers the first such offset, and [finish]
 * then throws [EncodeError.Layout] instead of returning a buffer that would
 * verify with wrong data. (Rust's debug build also panics at the store; the
 * JVM has no such build.)
 *
 * Whatever the caller's byte order, every store is little-endian, and the
 * caller's buffer keeps its order. Nothing is written to the caller's buffer
 * position: [finish] advances it.
 */
public class Writer(private val out: ByteBuffer) {
    private val aligned: Boolean = out.position() % BUFFER_ALIGN == 0
    private val buf: ByteBuffer = out.slice().order(ByteOrder.LITTLE_ENDIAN)
    private var len: Int = 0
    private var defect: Int? = null

    /**
     * Writes the preamble, zeroes a head of [size] bytes, and returns the offset
     * of the head, which is [PREAMBLE_SIZE]. Calling it again starts the buffer
     * over. `reserve_head`.
     *
     * @throws EncodeError.Unaligned when the output's position is not a multiple of 8.
     * @throws EncodeError.Layout when [size] is not a non-negative multiple of 8.
     * @throws EncodeError.Capacity when the output cannot hold the preamble and the head.
     */
    public fun reserveHead(size: Int): Int {
        if (!aligned) throw EncodeError.Unaligned
        if (size < 0 || size % 8 != 0) throw EncodeError.Layout(size)
        val end = PREAMBLE_SIZE.toLong() + size
        if (end > buf.limit()) throw EncodeError.Capacity(clampInt(end), buf.limit())
        zero(0, end.toInt())
        buf.putInt(0, size)
        len = end.toInt()
        defect = null
        return PREAMBLE_SIZE
    }

    /**
     * Appends [bytes] zeroed bytes at a multiple of [align], zero-fills the gap
     * before them, and returns their offset. An [align] below 1 is treated as 1.
     *
     * @throws EncodeError.Capacity when they do not fit; the writer is unchanged.
     * @throws EncodeError.Layout when [bytes] is negative.
     */
    public fun tail(bytes: Long, align: Int): Int {
        if (bytes < 0) throw EncodeError.Layout(len)
        val a = maxOf(align, 1).toLong()
        val start = (len + a - 1) / a * a
        val end = if (bytes > Long.MAX_VALUE - start) Long.MAX_VALUE else start + bytes
        if (end > buf.limit()) throw EncodeError.Capacity(clampInt(end), buf.limit())
        zero(len, end.toInt())
        len = end.toInt()
        return start.toInt()
    }

    /**
     * Appends [data] at the next byte, followed by one NUL byte when
     * [terminated] is true, and returns the offset of [data]. The block has
     * alignment 1, so no gap precedes it.
     *
     * @throws EncodeError.Capacity when it does not fit; the writer is unchanged.
     */
    public fun tailData(data: ByteArray, terminated: Boolean): Int {
        val start = len
        val end = start.toLong() + data.size + (if (terminated) 1 else 0)
        if (end > buf.limit()) throw EncodeError.Capacity(clampInt(end), buf.limit())
        val dst = buf.duplicate()
        dst.position(start)
        dst.put(data)
        len = end.toInt()
        if (terminated) buf.put(len - 1, 0)
        return start
    }

    private fun zero(from: Int, to: Int) {
        val dst = buf.duplicate()
        dst.position(from)
        val block = ZEROS
        var left = to - from
        while (left > 0) {
            val n = minOf(left, block.size)
            dst.put(block, 0, n)
            left -= n
        }
    }

    private fun clampInt(v: Long): Int = if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()

    /** Whether the [n] bytes at [at] lie in the written bytes; otherwise remembers the defect. */
    private fun inside(at: Int, n: Int): Boolean {
        if (at >= 0 && at.toLong() + n <= len) return true
        if (defect == null) defect = at
        return false
    }

    public fun putU8(at: Int, v: Int) {
        if (inside(at, 1)) buf.put(at, v.toByte())
    }

    public fun putI8(at: Int, v: Byte) {
        if (inside(at, 1)) buf.put(at, v)
    }

    public fun putU16(at: Int, v: Int) {
        if (inside(at, 2)) buf.putShort(at, v.toShort())
    }

    public fun putI16(at: Int, v: Short) {
        if (inside(at, 2)) buf.putShort(at, v)
    }

    /** Stores the low 32 bits of [v], an unsigned value carried as `Long`. */
    public fun putU32(at: Int, v: Long) {
        if (inside(at, 4)) buf.putInt(at, v.toInt())
    }

    public fun putI32(at: Int, v: Int) {
        if (inside(at, 4)) buf.putInt(at, v)
    }

    public fun putU64(at: Int, v: Long) {
        if (inside(at, 8)) buf.putLong(at, v)
    }

    public fun putI64(at: Int, v: Long) {
        if (inside(at, 8)) buf.putLong(at, v)
    }

    public fun putF32(at: Int, v: Float) {
        if (inside(at, 4)) buf.putInt(at, java.lang.Float.floatToRawIntBits(v))
    }

    public fun putF64(at: Int, v: Double) {
        if (inside(at, 8)) buf.putLong(at, java.lang.Double.doubleToRawLongBits(v))
    }

    /**
     * Stores [b] at [at]: the data of a fixed-size bytes array in the head, or
     * of a block that [tail] reserved. A string or a bytes field in the tail
     * is appended with [tailData] instead.
     */
    public fun putBytes(at: Int, b: ByteArray) {
        if (!inside(at, b.size)) return
        val dst = buf.duplicate()
        dst.position(at)
        dst.put(b)
    }

    /** Stores the descriptor [d] at [at]. */
    public fun putDesc(at: Int, d: Desc) {
        putU32(at, d.off)
        putU32(at.plusSat(4), d.len)
    }

    /** Stores the array descriptor [d] at [at]. */
    public fun putArrayDesc(at: Int, d: ArrayDesc) {
        putU32(at, d.off)
        putU16(at.plusSat(4), d.len)
        putU16(at.plusSat(6), d.elemSize)
    }

    /**
     * Ends the buffer, advances the output's position by its length, and
     * returns the length.
     *
     * @throws EncodeError.Layout with the offset of the first store outside the written bytes.
     * @throws EncodeError.Capacity when the head was never reserved, with the preamble size as `needed`.
     */
    public fun finish(): Int {
        defect?.let { throw EncodeError.Layout(it) }
        if (len < PREAMBLE_SIZE) throw EncodeError.Capacity(PREAMBLE_SIZE, buf.limit())
        out.position(out.position() + len)
        return len
    }

    private companion object {
        val ZEROS = ByteArray(256)
    }
}
