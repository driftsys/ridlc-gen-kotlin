// Encoding, verifying and decoding a payload: the spelling of
// `ridl_rt::payload` (docs/design.md §3, §4).
package ridl.rt.payload

import ridl.rt.RidlError
import ridl.rt.encoding.Encoding
import java.nio.ByteBuffer

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

    /** The buffer is larger than the verifier's limit. */
    TooLarge,
}

/** A value that breaks a typl constraint. `ridl_rt::payload::Violation`. */
public data class Violation(
    /** The name of the typl type whose constraint failed. */
    public val typeName: String,
    /** The kind of constraint that failed. */
    public val rule: Rule,
)

/** The kind of typl constraint a [Violation] breaks. `ridl_rt::payload::Rule`. */
public enum class Rule {
    /** A number is outside its declared range. */
    Range,

    /** A number is not its range's lower bound plus a whole multiple of its declared step. */
    Step,

    /** A string, a byte sequence or a collection is outside its declared length bounds. */
    Length,

    /** A string does not match its declared pattern. */
    Pattern,

    /** A discriminant names no declared variant. */
    Variant,
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
