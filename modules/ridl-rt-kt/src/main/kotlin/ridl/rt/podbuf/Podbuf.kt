// Shared reading for the podbuf payload encoding: the spelling of
// `ridl_rt::podbuf` (docs/design.md §4), less its `Aligned` wrapper, which a
// `ByteBuffer` position replaces.
//
// It holds the parts of a podbuf codec that are the same for every payload
// type, so a generated codec carries only what its own shape decides: the size
// and offsets of its head, which fields it has, and its typl constraints. This
// module decides no layout; it is handed sizes, offsets and alignments. Every
// read is a little-endian load over bytes fetched with a bounds check, so no
// input makes a function of this package throw anything but `VerifyError`.
package ridl.rt.podbuf

import ridl.rt.payload.Malformed
import ridl.rt.payload.VerifyError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The size in bytes of the preamble that starts every buffer. `PREAMBLE_SIZE`. */
public const val PREAMBLE_SIZE: Int = 8

/** The alignment in bytes of the start of a buffer and of a head. */
internal const val BUFFER_ALIGN: Int = 8

private fun malformed(kind: Malformed): Nothing = throw VerifyError.Structure(kind)

/**
 * A read-only little-endian view of one podbuf buffer: the bytes of the source
 * from its position to its limit, with offsets counted from 0 at the position.
 * The source's position and byte order are left as they were.
 *
 * Rust checks the address of the slice for 8-byte alignment; a JVM buffer has
 * no address to check, so the alignment is `position % 8` of the source.
 */
public class Buffer(source: ByteBuffer) {
    internal val bytes: ByteBuffer = source.slice().order(ByteOrder.LITTLE_ENDIAN)
    internal val aligned: Boolean = source.position() % BUFFER_ALIGN == 0

    /** The buffer's size in bytes. */
    public val size: Int get() = bytes.limit()

    /** A window of [size] bytes that starts at [start] in this buffer. `Region::new`. */
    public fun region(start: Int, size: Int): Region = Region(bytes, start, size)
}

/**
 * The preamble of a buffer: `{ head_size: u32, reserved: u32 }`, both
 * little-endian. `ridl_rt::podbuf::Preamble`.
 *
 * @property headSize The size in bytes of the head the producer wrote, a multiple of 8.
 */
public data class Preamble(val headSize: Int) {
    public companion object {
        /**
         * Reads and checks the preamble of [buf]. `Preamble::read`.
         *
         * The buffer start must be 8-aligned ([Malformed.Unaligned]), the buffer
         * must hold at least 8 bytes, `headSize` must be a multiple of 8, and
         * `8 + headSize` must not exceed the buffer length ([Malformed.Preamble]).
         * The reserved word is ignored.
         */
        public fun read(buf: Buffer): Preamble {
            if (!buf.aligned) malformed(Malformed.Unaligned)
            val head = buf.region(0, buf.size).u32At(0)
            if (buf.size < PREAMBLE_SIZE || head % 8 != 0L || PREAMBLE_SIZE + head > buf.size) {
                malformed(Malformed.Preamble)
            }
            return Preamble(head.toInt())
        }
    }
}

/**
 * The descriptor of a string, bytes or union field: `{ off: u32, len: u32 }`.
 * Both words are unsigned and carried as `Long`. `ridl_rt::podbuf::Desc`.
 *
 * @property off The offset of the data from the start of the buffer.
 * @property len The length of the data: bytes for a string or bytes, the arm head size for a union.
 */
public data class Desc(val off: Long, val len: Long) {
    /** Whether both words are 0: an empty field or an absent optional. */
    public fun isZero(): Boolean = off == 0L && len == 0L

    public companion object {
        /** Reads the descriptor at offset [at] of [r]. `Desc::read`. */
        public fun read(r: Region, at: Int): Desc = Desc(r.u32At(at), r.u32At(at.plusSat(4)))
    }
}

/**
 * The descriptor of an array or map field: `{ off: u32, len: u16, elem_size: u16 }`.
 * `ridl_rt::podbuf::ArrayDesc`.
 *
 * @property off The offset of the first element from the start of the buffer, unsigned.
 * @property len The number of elements or entries.
 * @property elemSize The size in bytes of one element or entry as the producer laid it out.
 */
public data class ArrayDesc(val off: Long, val len: Int, val elemSize: Int) {
    /** Whether all three words are 0: an empty field or an absent optional. */
    public fun isZero(): Boolean = off == 0L && len == 0 && elemSize == 0

    public companion object {
        /** Reads the descriptor at offset [at] of [r]. `ArrayDesc::read`. */
        public fun read(r: Region, at: Int): ArrayDesc =
            ArrayDesc(r.u32At(at), r.u16At(at.plusSat(4)), r.u16At(at.plusSat(6)))
    }
}

/** `this + n`, clamped at `Int.MAX_VALUE` as Rust's `saturating_add` is. */
internal fun Int.plusSat(n: Int): Int = if (this > Int.MAX_VALUE - n) Int.MAX_VALUE else this + n

/**
 * Checks where a descriptor's data lies and returns its byte range.
 * `ridl_rt::podbuf::tail_range`.
 *
 * The data starts at [off] (an unsigned 32-bit offset) and is [bytes] long.
 * [off] must be at least [minOff], the first byte of the tail, and the end must
 * not pass [bufLen]; both failures, and an overflow of the end, are
 * [Malformed.OutOfBounds]. [off] must be a multiple of [align]
 * ([Malformed.Unaligned]); an [align] below 1 is treated as 1.
 */
public fun tailRange(off: Long, bytes: Long, bufLen: Int, minOff: Int, align: Int): IntRange {
    if (off < minOff) malformed(Malformed.OutOfBounds)
    val end = off + bytes
    // `bytes` is an unsigned 64-bit count in Rust; a negative one or a wrapped sum is an overflow.
    if (bytes < 0 || end < 0 || end > bufLen) malformed(Malformed.OutOfBounds)
    if (off % maxOf(align, 1) != 0L) malformed(Malformed.Unaligned)
    return off.toInt() until end.toInt()
}

/**
 * Checks the bytes of a string and returns its text. `ridl_rt::podbuf::text`.
 *
 * [bytes] is the string's data followed by its terminator. The last byte must
 * be NUL and no other byte may be ([Malformed.Terminator]), and the data must
 * be valid UTF-8 ([Malformed.Utf8]).
 */
public fun text(bytes: ByteArray): String {
    if (bytes.isEmpty() || bytes[bytes.size - 1] != 0.toByte() || hasNul(bytes, bytes.size - 1)) {
        malformed(Malformed.Terminator)
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.size - 1)).toString()
    } catch (_: CharacterCodingException) {
        malformed(Malformed.Utf8)
    }
}

/** Whether [bytes] holds a NUL byte. `ridl_rt::podbuf::has_nul`. */
public fun hasNul(bytes: ByteArray): Boolean = hasNul(bytes, bytes.size)

private fun hasNul(bytes: ByteArray, end: Int): Boolean {
    for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
    return false
}

/** The number of Unicode code points of [s], the unit of a typl string bound. `scalar_count`. */
public fun scalarCount(s: String): Int = s.codePointCount(0, s.length)
