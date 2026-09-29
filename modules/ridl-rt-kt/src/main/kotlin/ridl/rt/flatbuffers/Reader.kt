// Shared reading for the FlatBuffers payload encoding: the spelling of the
// reading half of `ridl_rt::flatbuffers` (docs/design.md §4, O-K1 option A).
//
// Everything a generated `verify` needs to walk a buffer it does not trust:
// the little-endian reads, the vtable walk, and the string and vector
// headers, each checked against the buffer's bounds and failing with
// `VerifyError.Structure`, never with an exception of the JVM's own. The
// layout is not decided here: which slot a field takes is the projection's,
// and a generated codec passes it in.
package ridl.rt.flatbuffers

import ridl.rt.payload.Malformed
import ridl.rt.payload.VerifyError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The size of a `uoffset_t`, of an `soffset_t`, and of a length prefix. */
public const val OFFSET_SIZE: Int = 4

/** The size of a `voffset_t`, one vtable entry. */
public const val VOFFSET_SIZE: Int = 2

/** The bytes a vtable carries before its first entry: its own size, then its table's. */
public const val VTABLE_HEADER: Int = 4

private fun malformed(kind: Malformed): Nothing = throw VerifyError.Structure(kind)

/** A vector's length and the position of its first element. */
public data class Vector(val len: Int, val first: Int) {
    /** The position of element [index], which the caller has checked against [len]. */
    public fun element(index: Int, stride: Int): Int = first + index * stride
}

/**
 * A read-only view of one FlatBuffers buffer: the bytes of [source] from its
 * position to its limit, addressed from 0. The source's position is left
 * where it was.
 *
 * Every read copies nothing and aligns nothing: a buffer may arrive at any
 * alignment. A read outside the buffer throws `VerifyError.Structure`.
 */
public class Reader(source: ByteBuffer) {
    private val buf: ByteBuffer = source.slice().order(ByteOrder.LITTLE_ENDIAN)

    /** The buffer's size in bytes. */
    public val size: Int get() = buf.limit()

    private fun at(from: Int, len: Int): Int {
        if (from < 0 || len < 0 || from.toLong() + len > buf.limit()) malformed(Malformed.OutOfBounds)
        return from
    }

    public fun u8(from: Int): Int = buf.get(at(from, 1)).toInt() and 0xFF

    public fun i8(from: Int): Int = buf.get(at(from, 1)).toInt()

    public fun u16(from: Int): Int = buf.getShort(at(from, 2)).toInt() and 0xFFFF

    public fun i16(from: Int): Int = buf.getShort(at(from, 2)).toInt()

    public fun u32(from: Int): Long = buf.getInt(at(from, 4)).toLong() and 0xFFFF_FFFFL

    public fun i32(from: Int): Int = buf.getInt(at(from, 4))

    /** A `u64`, as the `Long` with the same bits. */
    public fun u64(from: Int): Long = buf.getLong(at(from, 8))

    public fun i64(from: Int): Long = buf.getLong(at(from, 8))

    public fun f32(from: Int): Float = buf.getFloat(at(from, 4))

    public fun f64(from: Int): Double = buf.getDouble(at(from, 8))

    /** A FlatBuffers boolean: zero is false, anything else is true. */
    public fun bool(from: Int): Boolean = u8(from) != 0

    /** Follows the `uoffset_t` at [from]: unsigned and relative, so it always points forward. */
    public fun follow(from: Int): Int {
        val target = from.toLong() + u32(from)
        if (target >= buf.limit()) malformed(Malformed.OutOfBounds)
        return target.toInt()
    }

    /** The position of the root table: the `uoffset_t` at the start of the buffer. */
    public fun root(): Int = follow(0)

    /**
     * The position of field [slot] of the table at [table], or `null` when
     * the table does not carry it. An absent field is not an error here:
     * whether it is legal is the generated `verify`'s question. The field,
     * [width] bytes, must lie wholly inside the table its vtable describes.
     */
    public fun field(table: Int, slot: Int, width: Int): Int? {
        val vtable = table.toLong() - i32(table)
        if (vtable < 0 || vtable >= buf.limit()) malformed(Malformed.OutOfBounds)
        val v = vtable.toInt()
        val vtableBytes = u16(v)
        if (vtableBytes < VTABLE_HEADER) malformed(Malformed.OutOfBounds)
        // The whole vtable lies in the buffer, so a walk of it is bounded by
        // the buffer and not by its own declared size.
        at(v, vtableBytes)
        val tableBytes = u16(v + VOFFSET_SIZE)
        val entry = v + VTABLE_HEADER + slot * VOFFSET_SIZE
        if (entry + VOFFSET_SIZE > v + vtableBytes) return null
        val offset = u16(entry)
        if (offset == 0) return null
        if (offset < OFFSET_SIZE || offset + width > tableBytes) malformed(Malformed.OutOfBounds)
        val position = table.toLong() + offset
        if (position >= buf.limit()) malformed(Malformed.OutOfBounds)
        return position.toInt()
    }

    /**
     * The string the `uoffset_t` at [from] names, checked for UTF-8 and for
     * the terminating zero a C reader relies on.
     */
    public fun string(from: Int): String {
        val start = follow(from)
        val len = u32(start)
        if (len > Int.MAX_VALUE - OFFSET_SIZE - 1) malformed(Malformed.OutOfBounds)
        val first = at(start + OFFSET_SIZE, len.toInt())
        if (u8(first + len.toInt()) != 0) malformed(Malformed.OutOfBounds)
        val bytes = buf.duplicate().position(first).limit(first + len.toInt())
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes).toString()
        } catch (_: CharacterCodingException) {
            malformed(Malformed.Utf8)
        }
    }

    /**
     * The vector the `uoffset_t` at [from] names, of [stride]-byte elements.
     * The whole element span is checked here, so a walk over the elements is
     * bounded by one check.
     */
    public fun vector(from: Int, stride: Int): Vector {
        val start = follow(from)
        val len = u32(start)
        val span = len * stride
        if (span > Int.MAX_VALUE) malformed(Malformed.OutOfBounds)
        at(start + OFFSET_SIZE, span.toInt())
        return Vector(len.toInt(), start + OFFSET_SIZE)
    }

    /** The bytes the `uoffset_t` at [from] names, as a `[ubyte]` vector. */
    public fun bytes(from: Int): ByteArray {
        val vector = vector(from, 1)
        return ByteArray(vector.len).also { buf.duplicate().position(vector.first).get(it) }
    }
}

/**
 * What a generated FlatBuffers codec's `verify` returns and its `decode`
 * takes: a checked buffer and the position of its root table. Kotlin's own,
 * in place of the Rust codec's `<Type>FbView` per type.
 */
public class TableView(public val reader: Reader, public val table: Int)
