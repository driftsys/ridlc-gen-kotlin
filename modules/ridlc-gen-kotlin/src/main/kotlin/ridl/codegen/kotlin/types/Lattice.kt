package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode
import java.math.BigDecimal

private val STEPS = ClassName("ridl.rt.payload", "Steps")
private val MATH = ClassName("java.lang", "Math")

/**
 * The step checks of typl §4.3, a value on `origin + n·step` with the
 * origin the minimum or 0: the Rust backend's `scalar_step::float_invalid`
 * and its integer check, as of driftsys/ridl#654. Each returns a Kotlin
 * expression that is `true` when [value] is off its lattice.
 *
 * All exact arithmetic happens here, at generation time, over the model's
 * decimal text; the generated code calls `ridl.rt.payload.Steps` with
 * `Double` constants alone.
 */
internal object Lattice {
    /** The largest exponent a decimal may spell, as the Rust reader guards an allocation. */
    private const val MAX_EXPONENT = 10000

    /** `f64::MIN_POSITIVE`, the smallest normal binary64. */
    private const val MIN_NORMAL = 2.2250738585072014E-308

    /** The float check of [value], received at binary32 when [f32]. */
    fun float(origin: String?, step: String, value: String, f32: Boolean): CodeBlock {
        val exactStep = decimal(step)?.takeIf { it.signum() > 0 } ?: return CodeBlock.of("true")
        val exactOrigin = decimal(origin ?: "0") ?: return CodeBlock.of("true")
        // Reducing the origin modulo the step preserves the lattice and avoids
        // cancellation against an unnecessarily large origin.
        val whole = exactOrigin.divideToIntegralValue(exactStep)
        var phase = exactOrigin.subtract(exactStep.multiply(whole))
        if (phase.signum() < 0) phase = phase.add(exactStep)
        val originalStep = exactStep.toDouble()
        val checked = if (f32) CodeBlock.of("%L.toFloat()", value) else CodeBlock.of("%L", value)
        // The exact positive lattice is finer than every binary64 rounding cell.
        if (originalStep == 0.0) return CodeBlock.of("!%L.isFinite()", checked)
        // A subnormal step loses its residual when split directly; an exact
        // power-of-two scale keeps it in the normal range.
        val factor = if (originalStep < MIN_NORMAL) 1.0 / MIN_NORMAL else 1.0
        val exactFactor = BigDecimal(factor)
        val scaledStep = split(exactStep.multiply(exactFactor))
        if (scaledStep == null) {
            // An overflowing step has only finitely many lattice points that
            // round to finite floats: they are listed here, not computed.
            val points = (-2..2).mapNotNull { index ->
                val point = phase.add(exactStep.multiply(BigDecimal(index)))
                val rounded = if (f32) point.toFloat().toDouble() else point.toDouble()
                rounded.takeIf { it.isFinite() }
            }
            if (points.isEmpty()) return CodeBlock.of("true")
            return CodeBlock.of("%T.offPoints(%L, %L, %L)", STEPS, value, f32, points.joinToString { literal(it) })
        }
        val scaledPhase = split(phase.multiply(exactFactor)) ?: return CodeBlock.of("true")
        return CodeBlock.of(
            "%T.offGrid(%L, %L, %L, %L, %L, %L, %L, %L)",
            STEPS, value, f32, literal(factor), literal(minOf(originalStep, Double.MAX_VALUE)),
            literal(scaledStep.first), literal(scaledStep.second), literal(scaledPhase.first), literal(scaledPhase.second),
        )
    }

    /**
     * The integer check of [value]: `(value - origin) % step != 0`, with no
     * overflow. Integer `step` is not a source form (TYPL-105); the Rust
     * backend checks the model shape all the same.
     */
    fun integer(origin: String?, step: String, value: String): CodeBlock {
        val exactStep = step.toBigIntegerOrNull()?.takeIf { it.signum() > 0 } ?: return CodeBlock.of("true")
        val exactOrigin = (origin ?: "0").toBigIntegerOrNull() ?: return CodeBlock.of("true")
        if (exactStep.bitLength() >= Long.SIZE_BITS) {
            // A step of 2^63 or more: `floorMod` cannot take it, and at most two
            // values of a `Long` are on the lattice, so they are listed exactly,
            // from the first point at or above `Long.MIN_VALUE` (#54). None is
            // listed when the lattice misses the `Long` range entirely.
            val min = Long.MIN_VALUE.toBigInteger()
            val max = Long.MAX_VALUE.toBigInteger()
            val points = generateSequence(min + (exactOrigin - min).mod(exactStep)) { it + exactStep }.takeWhile { it <= max }.toList()
            if (points.isEmpty()) return CodeBlock.of("true")
            return points.map { CodeBlock.of("%L != %L", value, Literals.long(it.toString())) }.joinToCode(" && ")
        }
        val phase = exactOrigin.mod(exactStep)
        return CodeBlock.of("%T.floorMod(%L, %LL) != %LL", MATH, value, exactStep, phase)
    }

    /**
     * [text] as an exact decimal, exponent spellings included, or `null`
     * when it is not one or its exponent exceeds [MAX_EXPONENT].
     */
    private fun decimal(text: String): BigDecimal? {
        val exponent = text.substringAfter('e', text.substringAfter('E', "0"))
        if ((exponent.toIntOrNull() ?: return null).let { it > MAX_EXPONENT || it < -MAX_EXPONENT }) return null
        return text.toBigDecimalOrNull()
    }

    /** [value] as a high `Double` and the rounded residual, or `null` when the high part is not finite. */
    private fun split(value: BigDecimal): Pair<Double, Double>? {
        val high = value.toDouble()
        if (!high.isFinite()) return null
        return high to value.subtract(BigDecimal(high)).toDouble()
    }

    /** A `Double` as a Kotlin literal that reads back to the same bits. */
    private fun literal(value: Double): String = when {
        value == 0.0 && 1.0 / value < 0 -> "-0.0"
        else -> value.toString()
    }
}
