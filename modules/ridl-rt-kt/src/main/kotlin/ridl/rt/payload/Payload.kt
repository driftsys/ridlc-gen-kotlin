// Encoding, verifying and decoding a payload: the spelling of
// `ridl_rt::payload` (docs/design.md §3, §4).
package ridl.rt.payload

import ridl.rt.RidlError
import ridl.rt.encoding.Encoding
import ridl.rt.sample.Detection
import java.nio.ByteBuffer

/**
 * Checks received [bytes] for a payload type, keeping a contract violation and
 * reporting every other verification error as corruption.
 * `ridl_rt::payload::check`.
 *
 * @throws Detection when [bytes] do not verify.
 */
public fun <T, V> check(codec: Payload<T, V>, bytes: ByteBuffer) {
    try {
        codec.verify(bytes)
    } catch (e: VerifyError) {
        throw if (e is VerifyError.Contract) Detection.InvalidValue(e.violation) else Detection.Corrupt
    }
}

/**
 * The codec of one payload type [T] in one [encoding].
 * `ridl_rt::payload::Payload<E>`.
 *
 * A generated `<Type>Codec` object implements it, one per type per encoding
 * (docs/design.md §4). Where the Rust trait writes into a slice and returns
 * the bytes, [encode] writes into a [ByteBuffer] from its position and
 * advances it; where Rust returns a `Result`, the Kotlin method throws.
 *
 * [decode] takes the [V] view that [verify] returned, so a value is decoded
 * only from bytes that were checked, which is what the Rust `Ref` proof
 * guarantees.
 */
public interface Payload<T, V> {
    /** The encoding this codec reads and writes. */
    public val encoding: Encoding

    /** The largest encoded size of any legal value, in bytes. `Payload::MAX_SIZE`. */
    public val maxSize: Int

    /**
     * Writes [value] into [out] from its position, advances the position past
     * the bytes written, and returns their number.
     *
     * @throws EncodeError when [out] has too few bytes remaining.
     */
    public fun encode(value: T, out: ByteBuffer): Int

    /**
     * Checks the structure of the bytes from [buf]'s position to its limit and
     * the typl constraints of the value they hold, in one pass.
     *
     * @throws VerifyError when the bytes are malformed or the value breaks a constraint.
     */
    public fun verify(buf: ByteBuffer): V

    /** Builds the value from a view [verify] returned. It cannot fail. */
    public fun decode(view: V): T
}

/** An encode that failed. `ridl_rt::payload::EncodeError`. */
public sealed class EncodeError(message: String) : RidlError(message) {
    /**
     * The output buffer is shorter than the encoding. [needed] is a lower
     * bound, not always the whole requirement; a caller that wants a buffer
     * that always suffices uses [Payload.maxSize].
     */
    public data class Capacity(public val needed: Int, public val available: Int) :
        EncodeError("the encoding needs $needed bytes and the buffer has $available")

    /**
     * The value breaks a typl constraint: `INVALID_VALUE`, ridl §10.2. The
     * mirror of [VerifyError.Contract]: the encoder refused the value and
     * reports the rule the receiver's `verify` would have reported for it.
     */
    public data class Contract(public val violation: Violation) : EncodeError("INVALID_VALUE: $violation")

    /** The output buffer does not start at the alignment the encoding requires. */
    public data object Unaligned : EncodeError("the output buffer is not at the encoding's alignment")

    /**
     * The encoder asked for a layout the encoding does not allow, or stored a
     * value outside the bytes it had reserved: a defect in the generated
     * encoder, not in the value or in the output buffer. [at] is the offset of
     * the first offending store, or the size the encoder asked for when that
     * size is not allowed.
     */
    public data class Layout(public val at: Int) : EncodeError("the encoder broke the layout at $at")
}

/** A check that failed. `ridl_rt::payload::VerifyError`. */
public sealed class VerifyError(message: String) : RidlError(message) {
    /** The bytes are not a well-formed encoding: a serialization failure, ridl §10.3. */
    public data class Structure(public val malformed: Malformed) : VerifyError("malformed: $malformed")

    /**
     * The bytes are well formed and hold a value that breaks a typl
     * constraint: `INVALID_VALUE`, ridl §10.2.
     */
    public data class Contract(public val violation: Violation) : VerifyError("INVALID_VALUE: $violation")
}

/** How the bytes of an encoding are malformed. `ridl_rt::payload::Malformed`. */
public enum class Malformed {
    /** An offset or a length points outside the buffer. */
    OutOfBounds,

    /** A value is not at its required alignment. */
    Unaligned,

    /** A required field is absent. */
    MissingRequired,

    /** A string is not valid UTF-8. */
    Utf8,

    /** A union's type does not match its value. */
    Union,

    /** The nesting is deeper than the verifier's limit. */
    TooDeep,

    /** The buffer holds more tables than the verifier's limit. */
    TooManyTables,

    /**
     * The buffer is larger than the verifier's limit. No generated codec
     * throws it: `verify` applies no length check, and the transport refuses
     * a payload above the interaction's limit.
     */
    TooLarge,

    /**
     * A varint is longer than ten bytes, has a tenth byte above 1, or holds a
     * value wider than the scalar of its field.
     */
    Varint,

    /** A tag names field number 0, a wire type proto3 does not have, or one the known field's type does not take. */
    WireType,

    /** A field that is not repeated appears twice, or a union message carries two arms, or one arm twice. */
    Duplicate,

    /**
     * A podbuf buffer is shorter than its 8-byte preamble, or the preamble's
     * `head_size` is not a multiple of 8 or reaches past the end of the buffer.
     */
    Preamble,

    /** A podbuf array or map descriptor states an `elem_size` the rules for its element type do not allow. */
    Stride,

    /** A podbuf string does not end in a NUL byte, or holds a NUL byte before its end. */
    Terminator,

    /** The keys of a podbuf map are not in strictly increasing order. */
    KeyOrder,
}

/** A value that breaks a typl constraint. `ridl_rt::payload::Violation`. */
public data class Violation(
    /**
     * The name of the typl type whose constraint failed. `null` only for an
     * `INVALID_VALUE` outcome decoded from a frame, as a frame carries the
     * [rule] alone (frame specification §9.6). It is set otherwise: when the
     * generated code detected the violation, on bytes received in a frame
     * included, and when an in-process transport such as `ridl-rt-kt-loopback`
     * delivered it. No Kotlin transport decodes a frame yet.
     */
    public val typeName: String?,
    /** The kind of constraint that failed. */
    public val rule: Rule,
)

/** The kind of typl constraint a [Violation] breaks. `ridl_rt::payload::Rule`. */
public enum class Rule {
    /** A number is outside its declared range. */
    Range,

    /** A number is not its range's lower bound, or 0 without one, plus a whole multiple of its declared step. */
    Step,

    /** A string, a byte sequence or a collection is outside its declared length bounds. */
    Length,

    /** A string does not match its declared pattern. */
    Pattern,

    /** A discriminant names no declared variant. */
    Variant,

    /** A map holds more than one entry with the same key. */
    Unique,

    /** A text chunk of a `<string>` stream is not valid UTF-8 (ADR-0032 decision 12). */
    Utf8,
}

/**
 * What a generated value object's `of` throws when a value breaks a typl
 * constraint (docs/design.md §4): the [Violation], with the refused [value].
 */
public class ConstraintViolation(
    /** The name of the typl type whose constraint failed. */
    public val type: String,
    /** The kind of constraint that failed. */
    public val rule: Rule,
    /** The refused value. */
    public val value: Any?,
) : RidlError("$value breaks the ${rule.name.lowercase()} constraint of $type") {
    /** The violation, as a payload check reports it. */
    public val violation: Violation get() = Violation(type, rule)
}
