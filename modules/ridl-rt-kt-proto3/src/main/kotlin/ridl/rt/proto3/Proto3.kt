package ridl.rt.proto3

import ridl.rt.payload.EncodeError
import ridl.rt.payload.Malformed
import ridl.rt.payload.VerifyError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The four proto3 wire types, with their tag bits. */
public enum class WireType(public val bits: Int) {
    /** A variable-width integer. */
    Varint(0),
    /** Eight little-endian bytes. */
    I64(1),
    /** A varint length followed by bytes. */
    Len(2),
    /** Four little-endian bytes. */
    I32(5),
}

/** The number of bytes needed to encode [value] as a varint. */
public fun varintLen(value: ULong): Int = (64 - java.lang.Long.numberOfLeadingZeros((value or 1uL).toLong()) + 6) / 7

/** The length of a field's tag, for any wire type. */
public fun tagLen(number: UInt): Int = varintLen(number.toULong() shl 3)

/** Moves the sign of a `sint32` into bit zero. */
public fun zigzag32(value: Int): UInt = ((value shl 1) xor (value shr 31)).toUInt()

/** Moves the sign of a `sint64` into bit zero. */
public fun zigzag64(value: Long): ULong = ((value shl 1) xor (value shr 63)).toULong()

/** Decodes a zigzag `sint32`. */
public fun unzigzag32(value: UInt): Int = (value shr 1).toInt() xor -(value and 1u).toInt()

/** Decodes a zigzag `sint64`. */
public fun unzigzag64(value: ULong): Long = (value shr 1).toLong() xor -(value and 1uL).toLong()

/** Checks the output's remaining capacity without moving its position. */
public fun checkCapacity(needed: Int, out: ByteBuffer) {
    require(needed >= 0) { "needed must be non-negative" }
    if (needed > out.remaining()) throw EncodeError.Capacity(needed, out.remaining())
}

private fun malformed(reason: Malformed): Nothing = throw VerifyError.Structure(reason)

/**
 * Reads a varint at [buf]'s position, advancing only on success.
 * Truncation is [Malformed.OutOfBounds]; a tenth byte above one is
 * [Malformed.Varint]. Non-minimal encodings are accepted.
 */
public fun readVarint(buf: ByteBuffer): ULong {
    val start = buf.position()
    var value = 0uL
    for (index in 0 until 10) {
        if (index >= buf.remaining()) malformed(Malformed.OutOfBounds)
        val byte = buf.get(start + index).toInt() and 0xff
        if (index == 9 && byte > 1) malformed(Malformed.Varint)
        value = value or ((byte and 0x7f).toULong() shl (7 * index))
        if (byte and 0x80 == 0) {
            buf.position(start + index + 1)
            return value
        }
    }
    malformed(Malformed.Varint)
}

/** Narrows a varint to `uint32`, or reports [Malformed.Varint]. */
public fun narrowU32(value: ULong): UInt {
    if (value > UInt.MAX_VALUE.toULong()) malformed(Malformed.Varint)
    return value.toUInt()
}

/** Narrows an `int32`, accepting negative values only in their 64-bit sign-extended form. */
public fun narrowI32(value: ULong): Int {
    val signed = value.toLong()
    if (signed < Int.MIN_VALUE || signed > Int.MAX_VALUE) malformed(Malformed.Varint)
    return signed.toInt()
}

/** Decodes a `sint32`, refusing encoded values wider than `uint32`. */
public fun unzigzag32Checked(value: ULong): Int = unzigzag32(narrowU32(value))

/** Decodes strict UTF-8 without moving [bytes]'s position. */
public fun utf8(bytes: ByteBuffer): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(bytes.duplicate()).toString()
} catch (_: CharacterCodingException) {
    malformed(Malformed.Utf8)
}

/** A field's wire value; the codec decides the declared scalar type. */
public sealed interface Value {
    /** A varint's unsigned bits. */
    public data class Varint(public val value: ULong) : Value

    /** Eight little-endian bytes as unsigned bits. */
    public data class I64(public val value: ULong) : Value

    /** The bytes behind a varint length, sharing the input storage. */
    public data class Len(public val bytes: ByteBuffer) : Value

    /** Four little-endian bytes as unsigned bits. */
    public data class I32(public val value: UInt) : Value
}

/** A field number and its wire value. A reader never returns number zero. */
public data class Field(public val number: UInt, public val value: Value)

/**
 * Walks a message in wire order without moving the caller's buffer position.
 * The first malformed field throws [VerifyError.Structure]; subsequent calls
 * return null. Unknown fields have already been skipped when returned.
 */
public class Reader(buf: ByteBuffer) {
    private val input: ByteBuffer = buf.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

    /** Returns the next field, null at the end, or throws the first malformed verdict. */
    public fun next(): Field? {
        if (!input.hasRemaining()) return null
        return try {
            readField()
        } catch (error: VerifyError.Structure) {
            input.position(input.limit())
            throw error
        }
    }

    private fun readField(): Field {
        val tag = narrowU32(readVarint(input))
        val number = tag shr 3
        if (number == 0u) malformed(Malformed.WireType)
        val value = when ((tag and 7u).toInt()) {
            0 -> Value.Varint(readVarint(input))
            1 -> {
                ensureRemaining(8)
                Value.I64(input.long.toULong())
            }
            2 -> {
                val length = readVarint(input)
                if (length > input.remaining().toULong()) malformed(Malformed.OutOfBounds)
                val bytes = input.slice().asReadOnlyBuffer()
                bytes.limit(length.toInt())
                input.position(input.position() + length.toInt())
                Value.Len(bytes.slice().asReadOnlyBuffer())
            }
            5 -> {
                ensureRemaining(4)
                Value.I32(input.int.toUInt())
            }
            else -> malformed(Malformed.WireType)
        }
        return Field(number, value)
    }

    private fun ensureRemaining(length: Int) {
        if (length > input.remaining()) malformed(Malformed.OutOfBounds)
    }
}

/**
 * Writes into the caller's remaining buffer without allocating on successful
 * writes or finish. An overflow writes the prefix that fits and is reported
 * at [finish]. The caller must preserve the output position and limit until
 * finish and must not supply source bytes that overlap the output.
 */
public class Writer(private val out: ByteBuffer) {
    private val start: Int = out.position()
    private val available: Int = out.remaining()
    private var used: Int = 0
    private var needed: Int = 0
    private var overflow: Boolean = false

    /** Writes the field number shifted above the wire type's three bits. */
    public fun tag(number: UInt, wire: WireType) {
        varint((number.toULong() shl 3) or wire.bits.toULong())
    }

    /** Writes seven low bits per byte, with a continuation bit except on the last. */
    public fun varint(value: ULong) {
        var remaining = value
        do {
            val byte = (remaining and 0x7fuL).toInt()
            remaining = remaining shr 7
            push(if (remaining == 0uL) byte else byte or 0x80)
        } while (remaining != 0uL)
    }

    /** Writes four bytes in little-endian order. */
    public fun fixed32(value: UInt) {
        for (index in 0 until 4) push((value shr (8 * index)).toInt())
    }

    /** Writes eight bytes in little-endian order. */
    public fun fixed64(value: ULong) {
        for (index in 0 until 8) push((value shr (8 * index)).toInt())
    }

    /** Writes the remaining bytes unchanged, preserving the source's position. */
    public fun bytes(bytes: ByteBuffer) {
        val count = bytes.remaining()
        account(count)
        val fits = minOf(count, available - used)
        for (index in 0 until fits) out.put(start + used + index, bytes.get(bytes.position() + index))
        used += fits
    }

    private fun account(count: Int) {
        if (count > available - needed) overflow = true
        needed = if (count > Int.MAX_VALUE - needed) Int.MAX_VALUE else needed + count
    }

    private fun push(byte: Int) {
        account(1)
        if (used < available) {
            out.put(start + used, byte.toByte())
            used += 1
        }
    }

    /**
     * Returns the written length and advances the output position on success.
     * On overflow, throws [EncodeError.Capacity] and leaves the position alone.
     */
    public fun finish(): Int {
        if (overflow) throw EncodeError.Capacity(needed, available)
        out.position(start + used)
        return used
    }
}

/** The bytes a caller has verified; proto3 provides no random-access field view. */
public class Verified(bytes: ByteBuffer) {
    private val input: ByteBuffer = bytes.slice().asReadOnlyBuffer()

    /** A read-only view of the verified bytes, sharing their storage. */
    public val bytes: ByteBuffer get() = input.asReadOnlyBuffer()
}
