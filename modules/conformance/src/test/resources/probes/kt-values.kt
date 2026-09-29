// The construction checks of `kt.values.Bundle`, which the model-driven probe
// does not reach: an array's and a map's bounds, an inline scalar's
// constraints inside and outside a collection, and the copies that keep a
// constructed value valid. Compiled with the generated Types.kt.
@file:JvmName("ValuesProbe")

package ridl.conformance.probe.values

import kt.values.Bundle
import kt.values.Code
import kt.values.Even
import kt.values.Mode
import kt.values.Offset
import kt.values.Text
import ridl.rt.payload.ConstraintViolation
import ridl.rt.payload.Rule

private val failures = mutableListOf<String>()

private fun bundle(
    codes: List<Code> = listOf(Code.of("AB")),
    tags: Map<Text, Even> = mapOf(Text.of("t") to Even.of(2)),
    exact: List<Even> = listOf(Even.of(0), Even.of(2)),
    maybe: Offset? = null,
    level: Double = 1.5,
    pad: ByteArray = ByteArray(2),
    word: String = "w",
    steps: List<Long> = listOf(0L, 4L),
): Bundle = Bundle(codes, tags, exact, maybe, level, pad, word, steps, Mode.RUN)

private fun breaks(label: String, rule: Rule, block: () -> Any?) {
    try {
        block()
        failures += "$label: accepted"
    } catch (e: ConstraintViolation) {
        if (e.type != "Bundle" || e.rule != rule) failures += "$label: ${e.type} ${e.rule}, not Bundle $rule"
    }
}

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

fun probe(): List<String> {
    bundle()
    bundle(maybe = Offset.of(-4.75), word = "😀😀", level = 9.0, steps = emptyList())
    breaks("an array below its minimum", Rule.Length) { bundle(codes = emptyList()) }
    breaks("an array above its maximum", Rule.Length) { bundle(codes = List(4) { Code.of("AB") }) }
    breaks("an exact array one short", Rule.Length) { bundle(exact = listOf(Even.of(0))) }
    breaks("an exact array one long", Rule.Length) { bundle(exact = List(3) { Even.of(0) }) }
    breaks("a map above its maximum", Rule.Length) {
        bundle(tags = mapOf(Text.of("a") to Even.of(0), Text.of("b") to Even.of(0), Text.of("c") to Even.of(0)))
    }
    breaks("an inline float below its range", Rule.Range) { bundle(level = -1.5) }
    breaks("an inline float off its step", Rule.Step) { bundle(level = 1.0) }
    breaks("an inline float that is NaN", Rule.Range) { bundle(level = Double.NaN) }
    breaks("inline bytes of the wrong length", Rule.Length) { bundle(pad = ByteArray(3)) }
    breaks("an inline string too long", Rule.Length) { bundle(word = "abc") }
    breaks("an inline string too short", Rule.Length) { bundle(word = "") }
    breaks("an inline integer inside an array", Rule.Range) { bundle(steps = listOf(5L)) }

    val codes = mutableListOf(Code.of("AB"))
    val pad = ByteArray(2)
    val built = bundle(codes = codes, pad = pad)
    codes += Code.of("CD")
    codes += Code.of("EF")
    codes += Code.of("GH")
    pad[0] = 7
    expect("the array was copied in", built.codes.size == 1)
    expect("the bytes were copied in", built.pad[0] == 0.toByte())
    built.pad[1] = 9
    expect("the bytes are copied out", built.pad[1] == 0.toByte())
    expect("equal fields make equal bundles", bundle() == bundle() && bundle().hashCode() == bundle().hashCode())
    expect("different bytes make different bundles", bundle(pad = byteArrayOf(1, 0)) != bundle())
    return failures
}
