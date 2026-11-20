package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import ridl.codegen.v1.ModelOuterClass.Constraint
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** `ridl.rt.payload.Rule`, the kind of constraint a value breaks. */
internal enum class RuleName { Range, Step, Length, Pattern, Variant, Unique, Utf8 }

private val IS_WELL_FORMED = MemberName("ridl.rt.payload", "isWellFormedString")

/**
 * The check that the string [value] holds no unpaired surrogate (typl §4.4),
 * ending in [fail] with [RuleName.Utf8]. A Kotlin `String` can hold one and a
 * Rust `str` cannot, so the Rust backend has no such check: the constructor
 * of a named string scalar runs it before [scalarChecks] (driftsys/ridl#1322),
 * and each encoder before it writes a string, which would otherwise carry a
 * replacement character (driftsys/ridl#1330).
 */
internal fun wellFormedCheck(value: String, fail: (RuleName) -> CodeBlock): CodeBlock =
    CodeBlock.builder().beginControlFlow("if (!%M(%L))", IS_WELL_FORMED, value).add(fail(RuleName.Utf8)).endControlFlow().build()

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
 * - Each `match` clause must match the whole value, in source order, and the
 *   first that does not is the violation, as the Rust backend checks them
 *   (driftsys/ridl#597). [pattern] names the compiled `Regex` of one clause's
 *   normalised pattern, and registers it; `null` checks no pattern.
 */
internal fun scalarChecks(
    scalar: Scalar,
    value: String,
    pattern: ((String) -> String)?,
    fail: (RuleName) -> CodeBlock,
): CodeBlock? {
    scalar.constraint.requireMatchClauses()
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
            if (pattern != null && scalar.class_ == ScalarClass.SCALAR_CLASS_STRING) {
                for (body in c.patterns()) {
                    code.beginControlFlow("if (!%L.matches(%L))", pattern(body), value)
                        .add(fail(RuleName.Pattern)).endControlFlow()
                }
            }
        }
        ScalarClass.SCALAR_CLASS_BOOLEAN -> {}
        else -> refuse("scalar class `${scalar.class_}` is not one this plugin reads")
    }
    return code.build().takeUnless { it.isEmpty() }
}

/**
 * The normalised pattern of each `match` clause that resolved, in source order.
 * A clause whose constant did not resolve has no pattern and checks nothing.
 */
internal fun Constraint.patterns(): List<String> {
    requireMatchClauses()
    return matchesList.filter { it.hasPattern() }.map { it.pattern }
}

/** Deprecated pattern fields do not carry the semantics of the current match clauses. */
@Suppress("DEPRECATION")
private fun Constraint.requireMatchClauses() {
    if (matchesCount == 0 && (hasPattern() || hasPatternConst())) {
        refuse("the plugin requires ridl editor-v0.21.0 or later; this model uses deprecated pattern fields")
    }
}

/**
 * [body], refused unless `java.util.regex` compiles it, as the generated
 * `Regex(body)` does when its class loads. ridl sends the normalised pattern
 * of a typl `match` clause (driftsys/ridl#597): an I-Regexp (RFC 9485) with no
 * Unicode category, no spelling that the engines read differently, and every
 * `.` written `[^\n\r]`. `java.util.regex` reads it as the Rust `regex` crate
 * does, so the body is used unchanged and matched against the whole value.
 */
internal fun javaPattern(body: String): String {
    try {
        Pattern.compile(body)
    } catch (e: PatternSyntaxException) {
        refuse("its pattern `$body` does not compile under java.util.regex: ${e.description}")
    }
    return body
}

/**
 * Whether this scalar's generated class checks nothing, and so has a public
 * constructor and no `of`, `violation` or `unchecked`: a non-string scalar
 * with the model's `vacuous` or a constraint whose every bound checks nothing,
 * such as an integer range
 * over all 64 bits. Every emitter asks this, never `vacuous` alone, so a
 * value object and its codec agree on how the value is built. Public for the
 * conformance probes, which build values the way the generated code does.
 */
val Scalar.plain: Boolean
    get() = class_ != ScalarClass.SCALAR_CLASS_STRING && scalarChecks(this, "value", { "PATTERN" }) { CodeBlock.of("") } == null

/** Whether [scalarChecks] needs a compiled pattern for this scalar. */
internal fun Scalar.checksPattern(): Boolean =
    !vacuous && class_ == ScalarClass.SCALAR_CLASS_STRING && constraint.patterns().isNotEmpty()

/** A length bound as an `Int` literal: a JVM string or array holds at most `Int.MAX_VALUE`. */
private fun Long.toIntBound(): String = if (this > Int.MAX_VALUE) Int.MAX_VALUE.toString() else toString()
