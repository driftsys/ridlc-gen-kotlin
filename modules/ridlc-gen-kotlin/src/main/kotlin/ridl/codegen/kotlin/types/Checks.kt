package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass

/** `ridl.rt.payload.Rule`, the kind of constraint a value breaks. */
internal enum class RuleName { Range, Step, Length, Pattern, Variant }

private val ABS = MemberName("kotlin.math", "abs")

/**
 * The typl constraint checks of one scalar (docs/design.md §4), in the order
 * the Rust backend runs them — range, then step, then length, then pattern —
 * each ending in [fail] with the rule it breaks.
 *
 * - Range is inclusive at both ends and written `!(value >= MIN)`, so a NaN
 *   breaks it; a bound at the 64-bit integer limit checks nothing.
 * - Step is `min + n·step` (typl §4.3), from 0 when there is no minimum,
 *   checked with a tolerance of a millionth of the step plus one ulp of the
 *   value at its wire width, so a value that crossed the wire as a binary32
 *   still passes.
 * - A string's length is its count of Unicode scalar values (typl §4.4),
 *   bytes' their count.
 * - A pattern is searched for, not matched whole, as the Rust backend's
 *   `Regex::is_match` does; the pattern's own anchors decide.
 */
internal fun scalarChecks(
    scalar: Scalar,
    value: String,
    pattern: String?,
    fail: (RuleName) -> CodeBlock,
): CodeBlock? {
    if (scalar.vacuous) return null
    val c = scalar.constraint
    val code = CodeBlock.builder()
    when (scalar.class_) {
        ScalarClass.SCALAR_CLASS_INTEGER -> {
            if (c.hasMin() && !Literals.isLongMin(c.min)) {
                code.beginControlFlow("if (%L < %L)", value, Literals.long(c.min)).add(fail(RuleName.Range)).endControlFlow()
            }
            if (c.hasMax() && !Literals.isLongMax(c.max)) {
                code.beginControlFlow("if (%L > %L)", value, Literals.long(c.max)).add(fail(RuleName.Range)).endControlFlow()
            }
        }
        ScalarClass.SCALAR_CLASS_FLOAT, ScalarClass.SCALAR_CLASS_UNSPECIFIED -> {
            if (c.hasMin()) {
                code.beginControlFlow("if (!(%L >= %L))", value, Literals.double(c.min))
                    .add(fail(RuleName.Range)).endControlFlow()
            }
            if (c.hasMax()) {
                code.beginControlFlow("if (!(%L <= %L))", value, Literals.double(c.max))
                    .add(fail(RuleName.Range)).endControlFlow()
            }
            if (c.hasStep()) {
                if (Literals.decimal(c.step).signum() <= 0) refuse("step `${c.step}` is not positive")
                val base = if (c.hasMin()) Literals.double(c.min) else "0.0"
                val step = Literals.double(c.step)
                val ulp = if (scalar.floatWidth == FloatWidth.FLOAT_WIDTH_F32) {
                    "Math.ulp($value.toFloat()).toDouble()"
                } else {
                    "Math.ulp($value)"
                }
                // No local: the check is inlined into a struct's `init`,
                // where any name could shadow one of its fields.
                code.beginControlFlow(
                    "if (%M(%L - (%L + Math.rint((%L - %L) / %L) * %L)) > %L * 1.0E-6 + %L)",
                    ABS, value, base, value, base, step, step, step, ulp,
                ).add(fail(RuleName.Step)).endControlFlow()
            }
        }
        ScalarClass.SCALAR_CLASS_STRING, ScalarClass.SCALAR_CLASS_BYTES -> {
            val length = if (scalar.class_ == ScalarClass.SCALAR_CLASS_STRING) {
                "$value.codePointCount(0, $value.length)"
            } else {
                "$value.size"
            }
            if (c.hasLenMin() && c.lenMin > 0) {
                code.beginControlFlow("if (%L < %L)", length, c.lenMin.toIntBound())
                    .add(fail(RuleName.Length)).endControlFlow()
            }
            if (c.hasLenMax()) {
                code.beginControlFlow("if (%L > %L)", length, c.lenMax.toIntBound())
                    .add(fail(RuleName.Length)).endControlFlow()
            }
            if (pattern != null && scalar.class_ == ScalarClass.SCALAR_CLASS_STRING && c.hasPattern()) {
                code.beginControlFlow("if (!%L.containsMatchIn(%L))", pattern, value)
                    .add(fail(RuleName.Pattern)).endControlFlow()
            }
        }
        ScalarClass.SCALAR_CLASS_BOOLEAN -> {}
        else -> refuse("scalar class `${scalar.class_}` is not one this plugin reads")
    }
    return code.build().takeUnless { it.isEmpty() }
}

/** Whether [scalarChecks] needs a compiled pattern for this scalar. */
internal fun Scalar.checksPattern(): Boolean =
    !vacuous && class_ == ScalarClass.SCALAR_CLASS_STRING && constraint.hasPattern()

/** A length bound as an `Int` literal: a JVM string or array holds at most `Int.MAX_VALUE`. */
private fun Long.toIntBound(): String = if (this > Int.MAX_VALUE) "Int.MAX_VALUE" else toString()
