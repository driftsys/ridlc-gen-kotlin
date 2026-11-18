package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
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
 *
 * The result is rewritten to match what the Rust output's `regex::Regex`
 * matches under its defaults (driftsys/ridlc-gen-kotlin#155). The `(?dU)`
 * prefix makes `\n` the only line terminator, so `.` refuses `\n` alone, and
 * makes `\d`, `\w`, `\s` and `\b` Unicode classes. Each `$` outside a class
 * becomes `\z`, because a Java `$` also matches before a final line
 * terminator. A pattern that turns on the multi-line flag `m` is refused:
 * its `$` must keep the line meaning, and this rewrite does not track flag
 * scopes. `\v` and the class operators `--` and `~~` are refused because
 * Java assigns them different meanings from Rust.
 */
internal fun javaPattern(body: String): String {
    val rewritten = StringBuilder("(?dU)")
    var i = 0
    var depth = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length -> {
                if (body[i + 1] == 'v') {
                    refuse("its pattern `/$body/` uses `\\v`, which this plugin does not translate to Rust's U+000B")
                }
                rewritten.append(c).append(body[i + 1])
                i += 2
                continue
            }
            depth > 0 && (body.startsWith("--", i) || body.startsWith("~~", i)) -> {
                refuse("its pattern `/$body/` uses class operator `${body.substring(i, i + 2)}`, which this plugin does not translate")
            }
            c == '[' -> {
                rewritten.append(c)
                i++
                if (i < body.length && body[i] == '^') rewritten.append(body[i++])
                // A `]` first in a class is a literal `]`.
                if (i < body.length && body[i] == ']') rewritten.append(body[i++])
                depth++
                continue
            }
            c == ']' && depth > 0 -> depth--
            c == '$' && depth == 0 -> {
                rewritten.append("\\z")
                i++
                continue
            }
            c == '(' && depth == 0 && body.startsWith("(?", i) -> {
                val flags = body.substring(i + 2).takeWhile { it.isLetter() || it == '-' }
                if ('m' in flags.substringBefore('-')) {
                    refuse("its pattern `/$body/` turns on the multi-line flag `m`, which this plugin does not translate")
                }
            }
        }
        rewritten.append(c)
        i++
    }
    try {
        Pattern.compile(rewritten.toString())
    } catch (e: PatternSyntaxException) {
        refuse("its pattern `/$body/` does not compile under java.util.regex: ${e.description}")
    }
    return rewritten.toString()
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
    get() = class_ != ScalarClass.SCALAR_CLASS_STRING && scalarChecks(this, "value", "PATTERN") { CodeBlock.of("") } == null

/** Whether [scalarChecks] needs a compiled pattern for this scalar. */
internal fun Scalar.checksPattern(): Boolean =
    !vacuous && class_ == ScalarClass.SCALAR_CLASS_STRING && constraint.hasPattern()

/** A length bound as an `Int` literal: a JVM string or array holds at most `Int.MAX_VALUE`. */
private fun Long.toIntBound(): String = if (this > Int.MAX_VALUE) Int.MAX_VALUE.toString() else toString()
