// driftsys/ridl#654's payload_constraints.rs at the pinned release, case for
// case where Kotlin can spell it: the generated value objects and codec of
// the `payload-constraints` corpus package, with the model rewritten as the
// Rust test rewrites the IR (ConstraintsTest). A Kotlin struct checks its
// inline fields when it is built, so where Rust encodes a bad value and
// verifies it, this probe expects the constructor to refuse it, and patches
// the bytes of a good one to reach `verify`. Compiled with the generated
// validation/payload sources.
@file:JvmName("PayloadConstraintsProbe")

package ridl.conformance.probe.constraints

import ridl.rt.payload.ConstraintViolation
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import validation.payload.ByteKeys
import validation.payload.ByteKeysCodec
import validation.payload.Corrected
import validation.payload.Decimal
import validation.payload.DecimalCodec
import validation.payload.DenseOrigin
import validation.payload.Extreme
import validation.payload.ExtremeInlineCodec
import validation.payload.Fine
import validation.payload.FineCodec
import validation.payload.FloatingKeys
import validation.payload.FloatingKeysCodec
import validation.payload.HalfLower
import validation.payload.HalfUpper
import validation.payload.Inline
import validation.payload.InlineCodec
import validation.payload.InlineRange
import validation.payload.Keys
import validation.payload.KeysCodec
import validation.payload.Large
import validation.payload.LargeNegative
import validation.payload.Offset
import validation.payload.OverflowStep
import validation.payload.OverflowStepCodec
import validation.payload.Pattern
import validation.payload.PatternCodec
import validation.payload.Ranged
import validation.payload.StepOnly
import validation.payload.Subnormal
import validation.payload.Tiny
import validation.payload.Unconstrained
import validation.payload.Underflow
import validation.payload.UnderflowCodec
import validation.payload.Whole
import validation.payload.Wide
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val failures = mutableListOf<String>()

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

/** [block] builds a value; it must be refused with [rule]. */
private fun refused(label: String, rule: Rule, block: () -> Any?) {
    try {
        block()
        failures += "$label: accepted"
    } catch (e: ConstraintViolation) {
        expectEqual("$label: the rule", rule, e.rule)
    }
}

/** [block] builds a value; it must be accepted. */
private fun accepted(label: String, block: () -> Any?) {
    try {
        block()
    } catch (e: ConstraintViolation) {
        failures += "$label: refused with ${e.rule}"
    }
}

private fun <T> bytes(codec: Payload<T, *>, value: T): ByteArray {
    val out = ByteBuffer.allocate(codec.maxSize)
    codec.encode(value, out)
    out.flip()
    return ByteArray(out.remaining()).also { out.get(it) }
}

private fun <T, V> roundTrip(codec: Payload<T, V>, bytes: ByteArray): T = codec.decode(codec.verify(ByteBuffer.wrap(bytes)))

/** What `verify` says of [bytes]: `null` when it accepts them, else the rule it refuses them with. */
private fun verdict(codec: Payload<*, *>, bytes: ByteArray): Any? = try {
    codec.verify(ByteBuffer.wrap(bytes))
    null
} catch (e: VerifyError.Contract) {
    e.violation.rule
} catch (e: VerifyError.Structure) {
    e.malformed
}

/** [bytes] with the one occurrence of [from] replaced by [to], of the same length. */
private fun patched(bytes: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
    val at = (0..bytes.size - from.size).filter { i -> from.indices.all { bytes[i + it] == from[it] } }
    check(at.size == 1) { "${at.size} occurrences of ${from.toList()}" }
    return bytes.copyOf().also { to.copyInto(it, at.single()) }
}

private fun le(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

private fun le(value: Float): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()

fun probe(): List<String> {
    constructors()
    inlineConstraints()
    mapKeys()
    wireSteps()
    patterns()
    decimalCompensation()
    narrowWire()
    absentZero()
    inlineRange()
    return failures
}

/** `constructors_reject_off_grid_values_and_constrained_nan`. */
private fun constructors() {
    refused("Ranged NaN outside a numeric range", Rule.Range) { Ranged.of(Double.NaN) }
    expect("Unconstrained keeps NaN", Unconstrained(Double.NaN).value.isNaN())
    expectEqual("Unconstrained keeps +inf", Double.POSITIVE_INFINITY, Unconstrained(Double.POSITIVE_INFINITY).value)
    expectEqual("Unconstrained keeps -inf", Double.NEGATIVE_INFINITY, Unconstrained(Double.NEGATIVE_INFINITY).value)
    for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        refused("HalfLower $value: an implicit upper bound is finite", Rule.Range) { HalfLower.of(value) }
        refused("HalfUpper $value: an implicit lower bound is finite", Rule.Range) { HalfUpper.of(value) }
    }
    accepted("HalfLower MAX") { HalfLower.of(Double.MAX_VALUE) }
    accepted("HalfUpper -MAX") { HalfUpper.of(-Double.MAX_VALUE) }
    accepted("Decimal 0.3") { Decimal.of(0.3) }
    refused("Decimal 0.35", Rule.Step) { Decimal.of(0.35) }
    refused("Decimal NaN", Rule.Range) { Decimal.of(Double.NaN) }
    accepted("Offset 1.0: the step starts at -3") { Offset.of(1.0) }
    refused("Offset 0.0", Rule.Step) { Offset.of(0.0) }
    accepted("StepOnly 1.0") { StepOnly.of(1.0) }
    refused("StepOnly 1.25", Rule.Step) { StepOnly.of(1.25) }
    for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        refused("StepOnly $value", Rule.Step) { StepOnly.of(value) }
    }
    accepted("Tiny 1e308: a dense lattice does not overflow the quotient") { Tiny.of(1e308) }
    for (value in listOf(-1e308, 0.0, 1e308)) accepted("Wide $value") { Wide.of(value) }
    refused("Wide 0.5e308", Rule.Step) { Wide.of(0.5e308) }
    accepted("Large 1e12") { Large.of(1e12) }
    accepted("Large 1e12 + 0.01") { Large.of(1e12 + 0.01) }
    refused("Large 1e12 + 0.005: a large origin admits no half step", Rule.Step) { Large.of(1e12 + 0.005) }
    accepted("Extreme MIN") { Extreme.of(Long.MIN_VALUE) }
    accepted("Extreme MAX: the subtraction does not overflow") { Extreme.of(Long.MAX_VALUE) }
    refused("Extreme MAX - 1", Rule.Step) { Extreme.of(Long.MAX_VALUE - 1) }
}

/** `verify_enforces_anonymous_scalar_constraints`, at the constructor, and `verify` of a good value. */
private fun inlineConstraints() {
    val good = Inline(text = "abcdefgh", count = 2, ratio = 0.5, data = byteArrayOf(1, 2))
    expectEqual("a good Inline verifies", null, verdict(InlineCodec, bytes(InlineCodec, good)))
    refused("an inline string minimum", Rule.Length) { Inline(text = "short", count = 2, ratio = 0.5, data = byteArrayOf(1, 2)) }
    accepted("length is Unicode characters") { Inline(text = "éééééééé", count = 2, ratio = 0.5, data = byteArrayOf(1, 2)) }
    refused("an inline integer bound", Rule.Range) { Inline(text = "abcdefgh", count = 4, ratio = 0.5, data = byteArrayOf(1, 2)) }
    refused("an inline constrained NaN", Rule.Range) { Inline(text = "abcdefgh", count = 2, ratio = Double.NaN, data = byteArrayOf(1, 2)) }
    refused("an inline float step", Rule.Step) { Inline(text = "abcdefgh", count = 2, ratio = 0.35, data = byteArrayOf(1, 2)) }
    val onGrid = Inline(text = "abcdefgh", count = 2, ratio = 0.3, data = byteArrayOf(1, 2))
    expectEqual("a decimal f32 wire grid point verifies", null, verdict(InlineCodec, bytes(InlineCodec, onGrid)))
    refused("an inline byte minimum", Rule.Length) { Inline(text = "abcdefgh", count = 2, ratio = 0.5, data = byteArrayOf(1)) }
    // The bytes of the step, past the constructor: 0.35 in place of 0.3.
    val ratio = patched(bytes(InlineCodec, onGrid), le(0.3f), le(0.35f))
    expectEqual("verify refuses an inline step", Rule.Step, verdict(InlineCodec, ratio))
}

/** `verify_rejects_unbounded_default_string_keys_and_duplicate_keys` and `floating_and_byte_key_equality_is_checked_without_decoding`. */
private fun mapKeys() {
    val text = bytes(KeysCodec, Keys(text = mapOf("a" to Whole(1), "b" to Whole(2)), number = emptyMap()))
    expectEqual("distinct textual keys verify", null, verdict(KeysCodec, text))
    val textDuplicate = patched(text, byteArrayOf(1, 0, 0, 0, 'b'.code.toByte()), byteArrayOf(1, 0, 0, 0, 'a'.code.toByte()))
    expectEqual("a duplicate textual key", Rule.Unique, verdict(KeysCodec, textDuplicate))
    refused("an unnamed key's default [0..256]", Rule.Length) { Keys(text = mapOf("x".repeat(257) to Whole(1)), number = emptyMap()) }
    val distinct = 0x1122334455667788L
    val number = bytes(KeysCodec, Keys(text = emptyMap(), number = mapOf(Long.MIN_VALUE to Whole(1), distinct to Whole(2))))
    expectEqual("distinct integer keys verify", null, verdict(KeysCodec, number))
    expectEqual("a duplicate integer key", Rule.Unique, verdict(KeysCodec, patched(number, le(distinct), le(Long.MIN_VALUE))))
    expectEqual("MIN and MAX keys verify", null,
        verdict(KeysCodec, patched(number, le(distinct), le(Long.MAX_VALUE))))

    expectEqual("no floating key verifies", null, verdict(FloatingKeysCodec, bytes(FloatingKeysCodec, FloatingKeys(emptyMap()))))
    // A Kotlin map holds 0.0 and -0.0 apart; the verifier compares them as floats.
    expectEqual("0.0 and -0.0 are one key", Rule.Unique,
        verdict(FloatingKeysCodec, bytes(FloatingKeysCodec, FloatingKeys(mapOf(0.0 to Whole(1), -0.0 to Whole(1))))))
    // A Kotlin map holds one NaN key; the second is patched in.
    val nan = bytes(FloatingKeysCodec, FloatingKeys(mapOf(Double.NaN to Whole(1), 1.5 to Whole(1))))
    val nanBits = java.lang.Double.doubleToRawLongBits(Double.NaN)
    expectEqual("NaN keys are not equal by floating-point equality", null,
        verdict(FloatingKeysCodec, patched(nan, le(java.lang.Double.doubleToRawLongBits(1.5)), le(nanBits))))

    expectEqual("distinct byte keys verify", null,
        verdict(ByteKeysCodec, bytes(ByteKeysCodec, ByteKeys(mapOf(byteArrayOf(1) to Whole(1), byteArrayOf(2) to Whole(1))))))
    expectEqual("equal byte keys are one key", Rule.Unique,
        verdict(ByteKeysCodec, bytes(ByteKeysCodec, ByteKeys(mapOf(byteArrayOf(1) to Whole(1), byteArrayOf(1) to Whole(1))))))
}

/** `verified_decimal_wire_values_satisfy_the_named_scalar_check`. */
private fun wireSteps() {
    val encoded = bytes(DecimalCodec, Decimal.of(0.3))
    val back = roundTrip(DecimalCodec, encoded)
    accepted("a decoded f32 lattice point stays valid") { Decimal.of(back.value) }
    expectEqual("a 0.35 on the wire is off the step", Rule.Step, verdict(DecimalCodec, patched(encoded, le(0.3f), le(0.35f))))
}

/** `anonymous_pattern_validation_obeys_the_feature`, with the pattern always checked, as Kotlin always checks it. */
private fun patterns() {
    val abc = bytes(PatternCodec, Pattern("ABC"))
    expectEqual("ABC verifies", null, verdict(PatternCodec, abc))
    expectEqual("XYZ is off the pattern", Rule.Pattern, verdict(PatternCodec, patched(abc, "ABC".toByteArray(), "XYZ".toByteArray())))
    refused("XYZ is refused when built", Rule.Pattern) { Pattern("XYZ") }
    refused("AB is too short", Rule.Length) { Pattern("AB") }
}

/** `decimal_lattices_survive_large_indices_and_subnormal_steps`. */
private fun decimalCompensation() {
    accepted("a rounded exact decimal lattice point") { Corrected.of(892794.6706715176) }
    expectEqual("the input is preserved", 892794.6706715176, Corrected.ofOrNull(892794.6706715176)?.value)
    refused("a neighbour outside the sparse grid", Rule.Step) { Corrected.of(892794.6706715177) }
    accepted("a step that fits a rounding cell") { DenseOrigin.of(30859282352002892.0) }
    accepted("phase reduction avoids a huge index") { LargeNegative.of(0.0) }
    for (value in listOf(0.0, 0.5, 1.0)) accepted("Underflow $value") { Underflow.of(value) }
    refused("Underflow NaN", Rule.Range) { Underflow.of(Double.NaN) }
    refused("Underflow +inf", Rule.Range) { Underflow.of(Double.POSITIVE_INFINITY) }
    accepted("the subnormal decimal residual survives") { Subnormal.of(27e-324) }
    accepted("Subnormal 9e-324") { Subnormal.of(9e-324) }
    refused("the first subnormal is not on this grid", Rule.Step) { Subnormal.of(4.9406564584124654e-324) }
    accepted("a huge step keeps its zero lattice point") { OverflowStep.of(0.0) }
    refused("OverflowStep 1.0", Rule.Step) { OverflowStep.of(1.0) }
    refused("OverflowStep NaN", Rule.Range) { OverflowStep.of(Double.NaN) }
}

/** `narrow_wire_projection_preserves_step_validation`. */
private fun narrowWire() {
    val value = Fine.ofOrNull(0.7500008)
    expectEqual("a decimal grid point, preserved", 0.7500008, value?.value)
    if (value != null) {
        val decoded = roundTrip(FineCodec, bytes(FineCodec, value))
        accepted("the decoded f32 image stays valid") { Fine.of(decoded.value) }
    }
    refused("a distinct off-grid wire image", Rule.Step) { Fine.of(0.75000084) }
}

/** `foreign_absent_zero_is_valid_for_extreme_zero_origin_steps`: a root table whose vtable has no field. */
private fun absentZero() {
    val bytes = byteArrayOf(8, 0, 0, 0, 4, 0, 4, 0, 4, 0, 0, 0, 0, 0, 0, 0)
    expectEqual("an absent Underflow reads as 0", 0.0, roundTrip(UnderflowCodec, bytes).value)
    expectEqual("an absent OverflowStep reads as 0", 0.0, roundTrip(OverflowStepCodec, bytes).value)
    val inline = roundTrip(ExtremeInlineCodec, bytes)
    expectEqual("an absent fine reads as 0", 0.0, inline.fine)
    expectEqual("an absent huge reads as 0", 0.0, inline.huge)
}

/** `inline_float_range_rejects_on_grid_outside_values_and_range_only_nan`. */
private fun inlineRange() {
    accepted("InlineRange 100, 0.5") { InlineRange(grid = 100.0, rangeOnly = 0.5) }
    for (outside in listOf(9.0, 150.0)) refused("on-grid $outside fails its inline range", Rule.Range) { InlineRange(grid = outside, rangeOnly = 0.5) }
    refused("a range-only NaN fails its range, not a step", Rule.Range) { InlineRange(grid = 100.0, rangeOnly = Double.NaN) }
}
