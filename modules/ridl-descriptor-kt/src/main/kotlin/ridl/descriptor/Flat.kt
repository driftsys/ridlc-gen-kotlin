// The FlatBuffers reading rules of planus 1.3.0, the library the Rust
// descriptors are read with, so that a buffer is refused here exactly when
// `ridl-descriptor` refuses it: `table_reader.rs`, `slice_helpers.rs` and the
// `TableRead` impls of `impls/`, without the `extra-validation` feature, which
// `ridl-descriptor` leaves off.
//
// planus reads an offset relative to a window, a slice of the buffer that
// runs from a start to the end of the buffer. The root is read in the window
// at 0; a table's fields in the window at the table's start; and a vector's
// element in the window at that element, since its iterator advances the
// vector by one element at a time (`split_first`). A table's vtable is found
// by a signed offset back from the table, and must lie inside the window the
// table was read from: planus refuses one before it. Every check is in `Long`,
// so no sum overflows.
package ridl.descriptor

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A buffer planus refuses, with the `ErrorKind` it names. */
internal class Malformed(val kind: String) : Exception(kind, null, false, false)

private fun malformed(kind: String): Nothing = throw Malformed(kind)

/** A buffer read under planus's rules. */
internal class Flat(private val buf: ByteArray) {
    private val size: Long = buf.size.toLong()

    /** `advance_as_array::<n>(at)` from window [window]: the [n] bytes at `window + at`, or `InvalidOffset`. */
    private fun bytesAt(window: Long, at: Long, n: Int): Int {
        val from = window + at
        if (from < 0 || from + n > size) malformed("InvalidOffset")
        return from.toInt()
    }

    fun u8(window: Long, at: Long): Int = buf[bytesAt(window, at, 1)].toInt() and 0xFF

    fun u16(window: Long, at: Long): Int {
        val i = bytesAt(window, at, 2)
        return (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)
    }

    fun i32(window: Long, at: Long): Int {
        val i = bytesAt(window, at, 4)
        return (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8) or
            ((buf[i + 2].toInt() and 0xFF) shl 16) or ((buf[i + 3].toInt() and 0xFF) shl 24)
    }

    fun u32(window: Long, at: Long): Long = i32(window, at).toLong() and 0xFFFF_FFFFL

    /** `SliceWithStartOffset::advance`: the window at `window + by`, which may be empty, or `InvalidOffset`. */
    private fun advance(window: Long, by: Long): Long {
        if (window + by > size) malformed("InvalidOffset")
        return window + by
    }

    /** `Table::from_buffer(window, at)`: the table whose offset is the `u32` at `window + at`. */
    fun table(window: Long, at: Long): Table {
        val objectOffset = at + u32(window, at)
        val objectStart = advance(window, objectOffset)
        val vtableOffset = objectOffset - i32(window, objectOffset)
        if (vtableOffset < 0) malformed("InvalidOffset")
        val vtableSize = u16(window, vtableOffset)
        if (vtableSize < 4 || vtableSize % 2 != 0) malformed("InvalidVtableLength")
        val vtableStart = window + vtableOffset
        if (vtableStart + vtableSize > size) malformed("InvalidLength")
        return Table(this, objectStart, vtableStart + 4, vtableSize - 4)
    }

    /** `array_from_buffer(window, at)`: the start of the vector's first element, and its length. */
    private fun array(window: Long, at: Long): Pair<Long, Long> {
        val start = advance(window, at + u32(window, at))
        val len = u32(start, 0)
        return advance(start, 4) to len
    }

    /** A `&str`: `len` bytes of UTF-8, `InvalidLength` past the buffer, `InvalidUtf8` when not UTF-8. */
    fun string(window: Long, at: Long): String {
        val (start, len) = array(window, at)
        if (start + len > size) malformed("InvalidLength")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buf, start.toInt(), len.toInt()))
                .toString()
        } catch (_: CharacterCodingException) {
            malformed("InvalidUtf8")
        }
    }

    /** A `&[u8]`: a copy of its bytes, `InvalidLength` past the buffer. */
    fun bytes(window: Long, at: Long): ByteArray {
        val (start, len) = array(window, at)
        if (start + len > size) malformed("InvalidLength")
        return buf.copyOfRange(start.toInt(), (start + len).toInt())
    }

    /** A `Vector<T>` of [stride]-byte elements: its first element's start, the window its elements are read in. */
    fun vector(window: Long, at: Long, stride: Int): Vec {
        val (start, len) = array(window, at)
        if (len * stride > size - start) malformed("InvalidLength")
        return Vec(start, len.toInt())
    }

    /** A vector: the start of its first element and their count. Element `i` is read in the window at `start + 4 * i`. */
    class Vec(val window: Long, val len: Int)
}

/** A table as planus holds it: the window at its start, and its vtable's field slots. */
internal class Table(private val flat: Flat, private val window: Long, private val slots: Long, private val slotBytes: Int) {
    /** The field offset in slot [index], or 0 when the vtable is too short to hold it. */
    private fun offset(index: Int): Long =
        if (2 * index + 2 <= slotBytes) flat.u16(slots, 2L * index).toLong() else 0

    /** `access`: the field read by [read] in the table's window, or `null` when absent. */
    fun <T : Any> optional(index: Int, read: Flat.(window: Long, at: Long) -> T): T? {
        val at = offset(index)
        return if (at == 0L) null else flat.read(window, at)
    }

    /** `access_required`: the field, or `MissingRequired`. */
    fun <T : Any> required(index: Int, read: Flat.(window: Long, at: Long) -> T): T =
        optional(index, read) ?: throw Malformed("MissingRequired")

    fun u8(index: Int, default: Int = 0): Int = optional(index) { w, a -> u8(w, a) } ?: default

    fun u32(index: Int, default: Long = 0): Long = optional(index) { w, a -> u32(w, a) } ?: default

    fun i32(index: Int, default: Int = 0): Int = optional(index) { w, a -> i32(w, a) } ?: default

    fun bool(index: Int, default: Boolean = false): Boolean = optional(index) { w, a -> u8(w, a) != 0 } ?: default

    /** A `ubyte` enum: its tag, `UnknownEnumTag` past the last of [count] values, [default] when absent. */
    fun enum(index: Int, count: Int, default: Int = 0): Int {
        val tag = optional(index) { w, a -> u8(w, a) } ?: return default
        if (tag >= count) throw Malformed("UnknownEnumTag")
        return tag
    }

    fun string(index: Int): String = required(index) { w, a -> string(w, a) }

    fun optionalString(index: Int): String? = optional(index) { w, a -> string(w, a) }

    fun bytes(index: Int): ByteArray = required(index) { w, a -> bytes(w, a) }

    fun table(index: Int): Table = required(index) { w, a -> table(w, a) }

    fun optionalTable(index: Int): Table? = optional(index) { w, a -> table(w, a) }

    /** A required vector of tables, each element read when [each] reaches it, as planus iterates. */
    fun <T> tables(index: Int, each: (Table) -> T): List<T> = elements(required(index) { w, a -> vector(w, a, 4) }, each)

    fun <T> optionalTables(index: Int, each: (Table) -> T): List<T>? =
        optional(index) { w, a -> vector(w, a, 4) }?.let { elements(it, each) }

    fun strings(index: Int): List<String> {
        val vec = required(index) { w, a -> vector(w, a, 4) }
        return List(vec.len) { i -> flat.string(vec.window + 4L * i, 0) }
    }

    /** A required vector of `uint32`: planus checks its bounds and reads each element unchecked. */
    fun u32s(index: Int): List<Long> {
        val vec = required(index) { w, a -> vector(w, a, 4) }
        return List(vec.len) { i -> flat.u32(vec.window + 4L * i, 0) }
    }

    private fun <T> elements(vec: Flat.Vec, each: (Table) -> T): List<T> =
        List(vec.len) { i -> each(flat.table(vec.window + 4L * i, 0)) }
}

/** The root table of [bytes], `read_as_root`. */
internal fun root(bytes: ByteArray): Table = Flat(bytes).table(0, 0)
