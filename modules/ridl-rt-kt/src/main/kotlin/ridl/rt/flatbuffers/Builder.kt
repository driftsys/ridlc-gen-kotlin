// Shared writing for the FlatBuffers payload encoding: the spelling of
// `ridl_rt::flatbuffers::Builder`.
//
// A FlatBuffers buffer is built back to front: a child before the parent that
// names it, and the root offset last, at the start of the finished buffer.
// The builder fills the caller's buffer from its limit down, and `finish`
// moves the finished bytes to the buffer's position. It decides no layout:
// a table's size, alignment, slots and field offsets are handed to it.
package ridl.rt.flatbuffers

import ridl.rt.payload.EncodeError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The position of an object under construction, measured backwards from the
 * end of the builder's buffer. Meaningful only to the [Builder] that made it.
 */
@JvmInline
public value class Pos(public val fromEnd: Int)

/** A value written into a table field. */
public sealed class Field(public val size: Int) {
    public data class Bool(val value: Boolean) : Field(1)

    public data class U8(val value: Int) : Field(1)

    public data class I8(val value: Int) : Field(1)

    public data class U16(val value: Int) : Field(2)

    public data class I16(val value: Int) : Field(2)

    public data class U32(val value: Long) : Field(4)

    public data class I32(val value: Int) : Field(4)

    public data class U64(val value: Long) : Field(8)

    public data class I64(val value: Long) : Field(8)

    public data class F32(val value: Float) : Field(4)

    public data class F64(val value: Double) : Field(8)

    /** A `uoffset_t` to an object this builder already wrote. */
    public data class Offset(val target: Pos) : Field(4)
}

/** One field of a table, at the slot and offset the projection and the emitter assigned it. */
public data class TableField(val slot: Int, val offset: Int, val value: Field)

/**
 * Builds a FlatBuffers buffer into [out], from its position to its limit.
 * A write that does not fit throws `EncodeError.Capacity` with the bytes the
 * encoding needed so far.
 */
public class Builder(private val out: ByteBuffer) {
    private val buf: ByteBuffer = out.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val start: Int = out.position()
    private val end: Int = out.limit()
    private val capacity: Int = end - start

    /** The bytes written so far, padding included. */
    public var used: Int = 0
        private set

    /**
     * Reserves [size] bytes for an object whose byte [skew] lies on an
     * [align] boundary of the finished buffer, zeroes them and their padding,
     * and returns the object's position and its first byte's index in [buf].
     */
    private fun reserve(size: Int, align: Int, skew: Int = 0): Pair<Pos, Int> {
        require(align > 0 && align and (align - 1) == 0) { "an alignment is a power of two" }
        val unpadded = used.toLong() + size
        val padding = ((align + skew % align - unpadded % align) % align)
        val needed = unpadded + padding
        if (needed > capacity) {
            throw EncodeError.Capacity(needed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), capacity)
        }
        used = needed.toInt()
        val first = end - used
        for (i in first until first + size + padding.toInt()) buf.put(i, 0)
        return Pos(used) to first
    }

    private fun delta(from: Pos, to: Pos): Int {
        check(to.fromEnd < from.fromEnd) { "a uoffset points forward, to an object this builder wrote before it" }
        return from.fromEnd - to.fromEnd
    }

    // One scalar each, little-endian, aligned to its own size.

    public fun pushBool(value: Boolean): Pos = reserve(1, 1).also { buf.put(it.second, if (value) 1 else 0) }.first

    public fun pushU8(value: Int): Pos = reserve(1, 1).also { buf.put(it.second, value.toByte()) }.first

    public fun pushI8(value: Int): Pos = reserve(1, 1).also { buf.put(it.second, value.toByte()) }.first

    public fun pushU16(value: Int): Pos = reserve(2, 2).also { buf.putShort(it.second, value.toShort()) }.first

    public fun pushI16(value: Int): Pos = reserve(2, 2).also { buf.putShort(it.second, value.toShort()) }.first

    public fun pushU32(value: Long): Pos = reserve(4, 4).also { buf.putInt(it.second, value.toInt()) }.first

    public fun pushI32(value: Int): Pos = reserve(4, 4).also { buf.putInt(it.second, value) }.first

    public fun pushU64(value: Long): Pos = reserve(8, 8).also { buf.putLong(it.second, value) }.first

    public fun pushI64(value: Long): Pos = reserve(8, 8).also { buf.putLong(it.second, value) }.first

    public fun pushF32(value: Float): Pos = reserve(4, 4).also { buf.putFloat(it.second, value) }.first

    public fun pushF64(value: Double): Pos = reserve(8, 8).also { buf.putDouble(it.second, value) }.first

    /** A `uoffset_t` to [target]. */
    public fun pushOffset(target: Pos): Pos {
        val (position, first) = reserve(OFFSET_SIZE, OFFSET_SIZE)
        buf.putInt(first, delta(position, target))
        return position
    }

    /** A string: its length, its UTF-8 bytes, and a terminating zero. */
    public fun pushString(value: String): Pos {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val (position, first) = reserve(OFFSET_SIZE + bytes.size + 1, OFFSET_SIZE)
        buf.putInt(first, bytes.size)
        buf.duplicate().position(first + OFFSET_SIZE).put(bytes)
        return position
    }

    /**
     * A vector of [elements], each [stride] bytes. The elements carry the
     * alignment, behind the four-byte length prefix.
     */
    public fun pushVector(elements: ByteArray, stride: Int): Pos {
        require(stride > 0 && elements.size % stride == 0) { "the element bytes are a whole number of elements" }
        val (align, skew) = if (stride > OFFSET_SIZE) stride to OFFSET_SIZE else OFFSET_SIZE to 0
        val (position, first) = reserve(OFFSET_SIZE + elements.size, align, skew)
        buf.putInt(first, elements.size / stride)
        buf.duplicate().position(first + OFFSET_SIZE).put(elements)
        return position
    }

    /** A vector of `uoffset_t`s to [targets]: a vector of strings or of tables. */
    public fun pushOffsetVector(targets: List<Pos>): Pos {
        val (position, first) = reserve(OFFSET_SIZE * (targets.size + 1), OFFSET_SIZE)
        buf.putInt(first, targets.size)
        for ((index, target) in targets.withIndex()) {
            val at = OFFSET_SIZE * (index + 1)
            buf.putInt(first + at, delta(Pos(position.fromEnd - at), target))
        }
        return position
    }

    /**
     * A table of [size] bytes aligned to [align], with a vtable of [slots]
     * entries, holding [fields] at the offsets given. The vtable is written
     * after the table, at a lower address, and the table names it with a
     * signed offset backwards.
     */
    public fun pushTable(size: Int, align: Int, slots: Int, fields: List<TableField>): Pos {
        require(size in OFFSET_SIZE..0xFFFF) { "a table's size is a u16 of at least the vtable offset" }
        val (table, first) = reserve(size, align)
        for (f in fields) {
            require(f.offset >= OFFSET_SIZE && f.offset + f.value.size <= size) { "a field lies inside its table" }
            val at = first + f.offset
            when (val v = f.value) {
                is Field.Bool -> buf.put(at, if (v.value) 1 else 0)
                is Field.U8 -> buf.put(at, v.value.toByte())
                is Field.I8 -> buf.put(at, v.value.toByte())
                is Field.U16 -> buf.putShort(at, v.value.toShort())
                is Field.I16 -> buf.putShort(at, v.value.toShort())
                is Field.U32 -> buf.putInt(at, v.value.toInt())
                is Field.I32 -> buf.putInt(at, v.value)
                is Field.U64 -> buf.putLong(at, v.value)
                is Field.I64 -> buf.putLong(at, v.value)
                is Field.F32 -> buf.putFloat(at, v.value)
                is Field.F64 -> buf.putDouble(at, v.value)
                is Field.Offset -> buf.putInt(at, delta(Pos(table.fromEnd - f.offset), v.target))
            }
        }
        val vtableBytes = VTABLE_HEADER + slots * VOFFSET_SIZE
        require(vtableBytes <= 0xFFFF) { "a vtable's size is a u16" }
        val (vtable, vfirst) = reserve(vtableBytes, VOFFSET_SIZE)
        buf.putShort(vfirst, vtableBytes.toShort())
        buf.putShort(vfirst + VOFFSET_SIZE, size.toShort())
        for (f in fields) {
            require(f.slot in 0 until slots) { "a field's slot is one the vtable carries" }
            buf.putShort(vfirst + VTABLE_HEADER + f.slot * VOFFSET_SIZE, f.offset.toShort())
        }
        buf.putInt(first, vtable.fromEnd - table.fromEnd)
        return table
    }

    /**
     * Writes the root offset to [root], aligning the finished buffer to
     * [align], moves the buffer to [out]'s position, advances it past the
     * bytes, and returns their number.
     */
    public fun finish(root: Pos, align: Int): Int {
        val (position, first) = reserve(OFFSET_SIZE, maxOf(align, OFFSET_SIZE))
        buf.putInt(first, delta(position, root))
        val bytes = ByteArray(used).also { buf.duplicate().position(end - used).get(it) }
        out.put(bytes)
        return used
    }
}
