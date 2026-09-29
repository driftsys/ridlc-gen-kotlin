package ridl.conformance

import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import java.math.BigDecimal

/**
 * A Kotlin source that exercises the value objects of a model through their
 * public API, compiled with them: for every constrained scalar, a value on
 * each bound constructs and a value one step outside it throws
 * `ConstraintViolation` with the rule it breaks (docs/design.md §7, "The value
 * objects refuse invalid values"); for every enum and enum set, a declared
 * discriminant reads and an undeclared one does not.
 *
 * A patterned string needs a value that matches, which the model cannot
 * give: [SAMPLES] holds one per patterned type of the corpus, and a type with
 * none is a failure of the probe, not a skipped case.
 */
object Probe {
    val SAMPLES: Map<String, String> = mapOf(
        "kt.values.Code" to "ABC",
        "kt.values.Serial" to "1234",
        "ridl.std.Uuid" to "123e4567-e89b-12d3-a456-426614174000",
        "ridl.std.Ulid" to "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "ridl.std.Uri" to "urn://example",
        "ridl.std.Url" to "https://example.com",
        "ridl.std.Email" to "a@b.co",
        "ridl.std.IpV4" to "10.0.0.1",
        "ridl.std.IpV6" to "::1",
        "ridl.std.Label" to "label",
        "ridl.std.Name" to "name",
        "ridl.std.Message" to "a message",
        "ridl.std.Date" to "2026-09-24",
        "ridl.std.TimeOfDay" to "12:30:00",
        "ridl.std.Version" to "1.2.3",
        "ridl.std.CountryCode" to "FR",
        "ridl.std.LanguageCode" to "fr-FR",
    )

    /** The probe source for [models], a top-level `fun probe(): List<String>` of the failures. */
    fun source(models: List<Model>): String {
        val cases = StringBuilder()
        for (model in models) {
            for (declaration in model.declarationsList) {
                val type = "${model.name.dotted}.${declaration.name.camel}"
                when {
                    declaration.hasScalar() -> scalar(type, declaration, cases)
                    declaration.hasEnum() -> enum(type, declaration, cases)
                    declaration.hasEnumSet() -> enumSet(type, declaration, cases)
                }
            }
        }
        return """
            |@file:JvmName("Probe")
            |
            |package ridl.conformance.probe
            |
            |import ridl.rt.payload.ConstraintViolation
            |import ridl.rt.payload.Rule
            |
            |private val failures = mutableListOf<String>()
            |
            |private fun ok(label: String, block: () -> Any?) {
            |    try {
            |        block()
            |    } catch (e: ConstraintViolation) {
            |        failures += "${'$'}label: refused with ${'$'}{e.rule}"
            |    }
            |}
            |
            |private fun breaks(label: String, type: String, rule: Rule, block: () -> Any?) {
            |    try {
            |        block()
            |        failures += "${'$'}label: accepted"
            |    } catch (e: ConstraintViolation) {
            |        if (e.type != type || e.rule != rule) failures += "${'$'}label: ${'$'}{e.type} ${'$'}{e.rule}, not ${'$'}type ${'$'}rule"
            |    }
            |}
            |
            |private fun expect(label: String, condition: Boolean) {
            |    if (!condition) failures += label
            |}
            |
            |fun probe(): List<String> {
            |$cases    return failures
            |}
            |""".trimMargin()
    }

    private fun scalar(type: String, declaration: Declaration, out: StringBuilder) {
        val scalar = declaration.scalar
        if (scalar.vacuous) {
            out.line("expect(\"$type has a public constructor\", $type(${vacuousValue(scalar.class_)}) == $type(${vacuousValue(scalar.class_)}))")
            return
        }
        val c = scalar.constraint
        val name = declaration.name.declared
        fun ok(label: String, value: String) = out.line("ok(\"$type.of($label)\") { $type.of($value) }")
        fun breaks(label: String, rule: String, value: String) {
            out.line("breaks(\"$type.of($label)\", \"$name\", Rule.$rule) { $type.of($value) }")
            out.line("expect(\"$type.ofOrNull($label) is null\", $type.ofOrNull($value) == null)")
        }
        when (scalar.class_) {
            ScalarClass.SCALAR_CLASS_INTEGER -> {
                if (c.hasMin()) {
                    ok("min", long(c.min))
                    if (c.min.toBigInteger() > Long.MIN_VALUE.toBigInteger()) breaks("min - 1", "Range", long((c.min.toBigInteger() - 1.toBigInteger()).toString()))
                }
                if (c.hasMax()) {
                    ok("max", long(c.max))
                    if (c.max.toBigInteger() < Long.MAX_VALUE.toBigInteger()) breaks("max + 1", "Range", long((c.max.toBigInteger() + 1.toBigInteger()).toString()))
                }
            }
            ScalarClass.SCALAR_CLASS_FLOAT, ScalarClass.SCALAR_CLASS_UNSPECIFIED -> {
                val step = if (c.hasStep()) BigDecimal(c.step) else BigDecimal.ONE
                if (c.hasMin()) {
                    val min = BigDecimal(c.min)
                    ok("min", double(min))
                    if (distinct(min, min - step)) breaks("min - step", "Range", double(min - step))
                    breaks("NaN", "Range", "Double.NaN")
                    if (c.hasStep()) {
                        ok("min + step", double(min + step))
                        ok("min + step, through binary32", "${double(min + step)}.toFloat().toDouble()")
                        breaks("min + step / 2", "Step", double(min + step.divide(BigDecimal(2))))
                    }
                }
                if (c.hasMax()) {
                    val max = BigDecimal(c.max)
                    val onGrid = !c.hasStep() || !c.hasMin() ||
                        (max - BigDecimal(c.min)).remainder(step).signum() == 0
                    if (onGrid) ok("max", double(max))
                    if (distinct(max, max + step)) breaks("max + step", "Range", double(max + step))
                }
            }
            ScalarClass.SCALAR_CLASS_STRING -> {
                val min = if (c.hasLenMin()) c.lenMin.toInt() else 0
                val max = if (c.hasLenMax()) c.lenMax.toInt() else null
                if (c.hasPattern()) {
                    val sample = SAMPLES[type]
                    if (sample == null) {
                        out.line("failures += \"$type is patterned and the probe has no sample of it\"")
                    } else {
                        ok("a sample", "\"${sample.replace("\\", "\\\\")}\"")
                    }
                    // Control characters match no pattern of the corpus; the length is legal.
                    breaks("a non-match", "Pattern", "\"\\u0001\".repeat(${maxOf(min, 1)})")
                } else {
                    ok("min length", "\"a\".repeat($min)")
                    if (max != null) {
                        ok("max length", "\"a\".repeat($max)")
                        // Two UTF-16 units each: counted as one scalar value each.
                        if (max > 0) ok("max length in astral scalar values", "\"\\uD83D\\uDE00\".repeat($max)")
                    }
                }
                if (min > 0) breaks("min length - 1", "Length", "\"a\".repeat(${min - 1})")
                if (max != null) breaks("max length + 1", "Length", "\"a\".repeat(${max + 1})")
            }
            ScalarClass.SCALAR_CLASS_BYTES -> {
                val min = if (c.hasLenMin()) c.lenMin.toInt() else 0
                ok("min length", "ByteArray($min)")
                out.line("expect(\"$type compares by content\", $type.of(ByteArray($min)) == $type.of(ByteArray($min)))")
                if (min > 0) breaks("min length - 1", "Length", "ByteArray(${min - 1})")
                if (c.hasLenMax()) {
                    ok("max length", "ByteArray(${c.lenMax})")
                    breaks("max length + 1", "Length", "ByteArray(${c.lenMax + 1})")
                }
            }
            else -> {}
        }
    }

    private fun enum(type: String, declaration: Declaration, out: StringBuilder) {
        val values = declaration.enum.valuesList
        for (value in values) {
            out.line("expect(\"$type.fromValue(${value.value}) is ${value.name.declared}\", $type.fromValue(${value.value}L) == $type.${value.name.declared})")
        }
        val undeclared = values.maxOf { it.value } + 1
        out.line("expect(\"$type.fromValue($undeclared) is null\", $type.fromValue(${undeclared}L) == null)")
        for (retired in declaration.enum.retiredList.filter { it.hasValue() }) {
            out.line("expect(\"$type.fromValue(${retired.value}), retired, is null\", $type.fromValue(${retired.value}L) == null)")
        }
    }

    private fun enumSet(type: String, declaration: Declaration, out: StringBuilder) {
        val set = declaration.enumSet
        val name = declaration.name.declared
        out.line("ok(\"$type.of(DECLARED_MASK)\") { $type.of($type.DECLARED_MASK) }")
        for (bit in set.bitsList) {
            out.line("expect(\"$type.${bit.name.declared} is in every member\", $type.${bit.name.declared} in $type.of($type.DECLARED_MASK))")
            out.line("expect(\"$type.${bit.name.declared} leaves by minus\", $type.${bit.name.declared} !in $type.of($type.DECLARED_MASK) - $type.${bit.name.declared})")
        }
        val undeclared = (0..63).firstOrNull { set.declaredMask and (1L shl it) == 0L }
        if (undeclared != null) {
            out.line("breaks(\"$type.of(bit $undeclared)\", \"$name\", Rule.Variant) { $type.of(1L shl $undeclared) }")
        }
    }

    private fun vacuousValue(scalarClass: ScalarClass): String = when (scalarClass) {
        ScalarClass.SCALAR_CLASS_BOOLEAN -> "true"
        ScalarClass.SCALAR_CLASS_INTEGER -> "1L"
        ScalarClass.SCALAR_CLASS_STRING -> "\"a\""
        else -> "1.0"
    }

    private fun long(text: String): String = if (text.toBigInteger() == Long.MIN_VALUE.toBigInteger()) "Long.MIN_VALUE" else "${text}L"

    private fun double(value: BigDecimal): String {
        val plain = value.toPlainString()
        return if ('.' in plain) plain else "$plain.0"
    }

    /** Whether [outside] is a different `Double` from [bound]: past 2^53 a step can round back onto it. */
    private fun distinct(bound: BigDecimal, outside: BigDecimal): Boolean = bound.toDouble() != outside.toDouble()

    private fun double(text: String): String = double(BigDecimal(text))

    private fun StringBuilder.line(text: String) {
        append("    ").append(text).append('\n')
    }
}
