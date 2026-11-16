// The catalog descriptor: the spelling of `ridl_descriptor`'s reading half,
// `verify` and the tables of `schema/catalog.fbs`, at the pinned release.
package ridl.descriptor

/** The schema version this module reads. `ridl_descriptor::SCHEMA_VERSION`. */
public const val SCHEMA_VERSION: Long = 1

/** The file identifier at bytes 4..8 of every catalog descriptor. `ridl_descriptor::FILE_IDENTIFIER`. */
public const val FILE_IDENTIFIER: String = "RDLC"

/** The artifact suffix, `<unit>.catalog.binfb`. `ridl_descriptor::FILE_SUFFIX`. */
public const val FILE_SUFFIX: String = ".catalog.binfb"

/**
 * A catalog descriptor `verify` refuses. `ridl_descriptor::VerifyError`.
 * Thrown, as every error of the runtime is (`modules/ridl-rt-kt/README.md`).
 */
public sealed class VerifyError(message: String) : RuntimeException(message, null, false, false) {
    /** Fewer bytes than the root offset and the identifier need. */
    public data class TooShort(val len: Int) : VerifyError("$len bytes is shorter than a catalog descriptor header")

    /** Bytes 4..8 are not [FILE_IDENTIFIER]: another kind of file. */
    public class WrongIdentifier(public val identifier: ByteArray) :
        VerifyError("not a catalog descriptor: file identifier ${quoted(identifier)}, expected \"$FILE_IDENTIFIER\"")

    /** A schema version this module does not know. */
    public data class WrongVersion(val version: Long) :
        VerifyError("catalog descriptor version $version; this toolchain reads version $SCHEMA_VERSION")

    /**
     * The FlatBuffers structure is not sound: an offset or a length points
     * outside the buffer, a required field is missing, an enum tag is unknown.
     * [kind] names planus's `ErrorKind`.
     */
    public data class Invalid(val kind: String) : VerifyError("catalog descriptor is malformed: $kind")
}

/**
 * The four bytes of an identifier as Rust's `{:?}` of `String::from_utf8_lossy`
 * shows them: `<str as Debug>`, which escapes each character as
 * `char::escape_debug_ext` does. Which characters are escaped past ASCII comes
 * from the JDK's general categories, near Rust's Unicode properties but not
 * equal to them: Rust also escapes a few spacing marks and fillers, and
 * prints what a newer Unicode than the JDK's assigns.
 */
internal fun quoted(bytes: ByteArray): String = buildString {
    append('"')
    val text = lossy(bytes)
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        when (c) {
            0 -> append("\\0")
            '\t'.code -> append("\\t")
            '\r'.code -> append("\\r")
            '\n'.code -> append("\\n")
            '"'.code, '\\'.code -> append('\\').append(c.toChar())
            in 0x20..0x7E -> append(c.toChar())
            else -> if (escaped(c)) append("\\u{").append(Integer.toHexString(c)).append('}') else appendCodePoint(c)
        }
        i += Character.charCount(c)
    }
    append('"')
}

/**
 * Control, private use, whitespace, grapheme extender, default ignorable,
 * format or unassigned: what `escape_debug_ext` escapes past ASCII.
 */
private fun escaped(c: Int): Boolean = when (Character.getType(c).toByte()) {
    Character.CONTROL, Character.PRIVATE_USE, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT,
    Character.UNASSIGNED,
    -> true
    else -> false
}

/**
 * `String::from_utf8_lossy`: each maximal ill-formed subsequence is one
 * U+FFFD (Unicode §3.9). The JDK's decoder takes a surrogate's three bytes as
 * one, where Rust, refusing `ED` before `A0`, replaces each.
 */
private fun lossy(bytes: ByteArray): String = buildString {
    var i = 0
    while (i < bytes.size) {
        val lead = bytes[i].toInt() and 0xFF
        // The length of the sequence [lead] starts, and the range its second byte must lie in.
        val (n, second) = when (lead) {
            in 0x00..0x7F -> 1 to 0..0
            in 0xC2..0xDF -> 2 to 0x80..0xBF
            0xE0 -> 3 to 0xA0..0xBF
            0xED -> 3 to 0x80..0x9F
            in 0xE1..0xEF -> 3 to 0x80..0xBF
            0xF0 -> 4 to 0x90..0xBF
            in 0xF1..0xF3 -> 4 to 0x80..0xBF
            0xF4 -> 4 to 0x80..0x8F
            else -> 0 to 0..0 // continuation bytes, C0, C1, F5 to FF
        }
        // A lead byte that starts no sequence is a subsequence of its own.
        var valid = 1
        while (valid < n && i + valid < bytes.size) {
            val b = bytes[i + valid].toInt() and 0xFF
            if (b !in (if (valid == 1) second else 0x80..0xBF)) break
            valid++
        }
        if (valid == n) {
            append(String(bytes, i, n, Charsets.UTF_8))
            i += n
        } else {
            append('\uFFFD')
            i += valid
        }
    }
}

/** `ridl.descriptor.Kind`, the interaction kind, in schema order. */
public enum class Kind { Signal, Event, Command, Query, Fixed }

/** `ridl.descriptor.Encoding`, in schema order. */
public enum class Encoding { Proto3, FlatBuffers, Podbuf }

/** `ridl.descriptor.TimingMode`, in schema order. */
public enum class TimingMode { Unspecified, StrictPeriodic, Range }

/** `ridl.descriptor.SizeState`, in schema order. */
public enum class SizeState { Bounded, Unbounded }

/** `ridl.descriptor.UnboundedCause`, in schema order. */
public enum class UnboundedCause { Unspecified, Member, Untyped, Layout, Aggregate, Exempt }

/** `ridl.descriptor.StreamForm`, in schema order. */
public enum class StreamForm { Elements, Bytes, Text }

public class MaxSize(
    public val encoding: Encoding,
    public val bytes: Long,
    public val state: SizeState,
    public val cause: UnboundedCause,
)

public class Payload(public val role: String, public val typeName: String, public val maxSizes: List<MaxSize>)

public class StreamPayload(
    public val no: Int,
    public val form: StreamForm,
    public val element: String?,
    public val maxSizes: List<MaxSize>,
)

public class Timing(public val mode: TimingMode, public val minUs: String?, public val maxUs: String?)

public class Member(
    public val name: String,
    public val ordinal: Long,
    public val kind: Kind,
    public val payloads: List<Payload>,
    public val timing: Timing?,
    /** Absent when no `[limits]` entry applies. */
    public val limit: Long?,
    /** Absent in a file written before the field existed. */
    public val streams: List<StreamPayload>?,
)

public class Interface(
    public val name: String,
    public val number: Long,
    public val provisional: Boolean,
    public val members: List<Member>,
    public val reservedOrdinals: List<Long>,
)

public class RetiredInterface(public val name: String, public val number: Long)

public class EarlierCatalog(public val hash: ByteArray)

/** A verified catalog descriptor, read in full. `ridl.descriptor.Catalog`. */
public class Catalog(
    public val version: Long,
    public val name: String,
    public val hash: ByteArray,
    public val toolchain: String,
    public val interfaces: List<Interface>,
    public val retired: List<RetiredInterface>,
    /** Absent in a file written before the field existed. */
    public val compatible: List<EarlierCatalog>?,
)

/**
 * Verifies [bytes] as a catalog descriptor and reads it in full.
 * `ridl_descriptor::verify`: the header, the identifier, the root, the
 * version, then every field in the order the Rust walk touches them, so a
 * buffer with several faults is refused for the one Rust names.
 *
 * @throws VerifyError when Rust's `verify` refuses the buffer.
 */
public fun verify(bytes: ByteArray): Catalog {
    if (bytes.size < 8) throw VerifyError.TooShort(bytes.size)
    val identifier = bytes.copyOfRange(4, 8)
    if (!identifier.contentEquals(FILE_IDENTIFIER.toByteArray())) throw VerifyError.WrongIdentifier(identifier)
    return try {
        val catalog = root(bytes)
        val version = catalog.u32(0)
        if (version != SCHEMA_VERSION) throw VerifyError.WrongVersion(version)
        Catalog(
            version = version,
            name = catalog.string(1),
            hash = catalog.bytes(2),
            toolchain = catalog.string(3),
            interfaces = catalog.tables(4, ::readInterface),
            retired = catalog.tables(5) { RetiredInterface(it.string(0), it.u32(1)) },
            compatible = catalog.optionalTables(6) { EarlierCatalog(it.bytes(0)) },
        )
    } catch (e: Malformed) {
        throw VerifyError.Invalid(e.kind)
    }
}

// Kotlin evaluates arguments left to right, so each constructor call below
// reads its fields in the order `walk` touches them.

private fun readInterface(t: Table): Interface {
    val name = t.string(0)
    val number = t.u32(1)
    val provisional = t.bool(2)
    val reserved = t.u32s(4)
    val members = t.tables(3, ::readMember)
    return Interface(name, number, provisional, members, reserved)
}

private fun readMember(t: Table): Member {
    val name = t.string(0)
    val ordinal = t.u32(1)
    val kind = Kind.entries[t.enum(2, Kind.entries.size)]
    val limit = t.optional(5) { w, a -> u32(w, a) }
    val timing = t.optionalTable(4)?.let {
        Timing(TimingMode.entries[it.enum(0, TimingMode.entries.size)], it.optionalString(1), it.optionalString(2))
    }
    val payloads = t.tables(3) { Payload(it.string(0), it.string(1), it.tables(2, ::readSize)) }
    val streams = t.optionalTables(6) {
        StreamPayload(it.u8(0), StreamForm.entries[it.enum(1, StreamForm.entries.size)], it.optionalString(2), it.tables(3, ::readSize))
    }
    return Member(name, ordinal, kind, payloads, timing, limit, streams)
}

private fun readSize(t: Table): MaxSize = MaxSize(
    Encoding.entries[t.enum(0, Encoding.entries.size)],
    t.u32(1),
    SizeState.entries[t.enum(2, SizeState.entries.size)],
    UnboundedCause.entries[t.enum(3, UnboundedCause.entries.size)],
)
