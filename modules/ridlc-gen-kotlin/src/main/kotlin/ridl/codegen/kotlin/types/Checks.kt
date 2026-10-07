package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.CodeBlock
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** `ridl.rt.payload.Rule`, the kind of constraint a value breaks. */
internal enum class RuleName { Range, Step, Length, Pattern, Variant, Unique }

/**
 * The typl constraint checks of one scalar (docs/design.md §4), in the order
 * the Rust backend runs them — range, then step, then length, then pattern —
 * each ending in [fail] with the rule it breaks.
 *
 * - Range is inclusive at both ends. A float with a minimum or a maximum
 *   must also be finite, so a NaN or an infinity breaks it; a bound at the
 *   64-bit integer limit checks nothing.
 * - Step is `min + n·step` (typl §4.3), from 0 when there is no minimum,
 *   checked by [Lattice] as the Rust backend checks it (driftsys/ridl#654):
 *   a float at its wire width, so a value and the binary32 it crosses the
 *   wire as get one verdict.
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
            if (c.hasStep()) {
                code.beginControlFlow("if (%L)", Lattice.integer(c.min.takeIf { c.hasMin() }, c.step, value))
                    .add(fail(RuleName.Step)).endControlFlow()
            }
        }
        ScalarClass.SCALAR_CLASS_FLOAT, ScalarClass.SCALAR_CLASS_UNSPECIFIED -> {
            if (c.hasMin() || c.hasMax()) {
                code.beginControlFlow("if (!%L.isFinite())", value).add(fail(RuleName.Range)).endControlFlow()
            }
            if (c.hasMin()) {
                code.beginControlFlow("if (%L < %L)", value, Literals.double(c.min)).add(fail(RuleName.Range)).endControlFlow()
            }
            if (c.hasMax()) {
                code.beginControlFlow("if (%L > %L)", value, Literals.double(c.max)).add(fail(RuleName.Range)).endControlFlow()
            }
            if (c.hasStep()) {
                val f32 = scalar.floatWidth == FloatWidth.FLOAT_WIDTH_F32
                code.beginControlFlow("if (%L)", Lattice.float(c.min.takeIf { c.hasMin() }, c.step, value, f32))
                    .add(fail(RuleName.Step)).endControlFlow()
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

/**
 * [body], refused unless `java.util.regex` compiles it, as the generated
 * `Regex(body)` does when its class loads. `ridl check` passes a pattern
 * ECMA-262 and the Rust `regex` crate both compile (TYPL-106, TYPL-220), and
 * neither guarantees Java does: `\p{Greek}`, `\p{Letter}` or `\u{41}` pass
 * both and throw `PatternSyntaxException` here (driftsys/ridlc-gen-kotlin#12).
 */
internal fun javaPattern(body: String): String {
    try {
        Pattern.compile(body)
    } catch (e: PatternSyntaxException) {
        refuse("its pattern `/$body/` does not compile under java.util.regex: ${e.description}")
    }
    return body
}

/**
 * Whether this scalar's generated class checks nothing, and so has a public
 * constructor and no `of`, `violation` or `unchecked`: the model's `vacuous`,
 * or a constraint whose every bound checks nothing, such as an integer range
 * over all 64 bits. Every emitter asks this, never `vacuous` alone, so a
 * value object and its codec agree on how the value is built. Public for the
 * conformance probes, which build values the way the generated code does.
 */
val Scalar.plain: Boolean
    get() = scalarChecks(this, "value", "PATTERN") { CodeBlock.of("") } == null

/** Whether [scalarChecks] needs a compiled pattern for this scalar. */
internal fun Scalar.checksPattern(): Boolean =
    !vacuous && class_ == ScalarClass.SCALAR_CLASS_STRING && constraint.hasPattern()

/** A length bound as an `Int` literal: a JVM string or array holds at most `Int.MAX_VALUE`. */
private fun Long.toIntBound(): String = if (this > Int.MAX_VALUE) Int.MAX_VALUE.toString() else toString()
