// The float `step` check generated value objects and codecs call: the
// run-time half of the Rust backend's `scalar_step::float_invalid`.
package ridl.rt.payload

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a float lies off its declared decimal lattice `origin + n·step`
 * (typl §4.3), the check behind [Rule.Step]. Kotlin's own, with no Rust item:
 * the Rust backend inlines the same arithmetic into every generated check
 * (driftsys/ridl#469). The plugin does the exact decimal work when it
 * generates the check — it reduces the origin modulo the step to a phase in
 * `[0, step)` and splits the step and the phase each into a high and a low
 * `Double` — and passes the results here, so this object holds only
 * floating-point operations.
 *
 * With [f32], the value is first rounded to binary32, the width it crosses
 * the wire at, so a value and the image it decodes to get one verdict.
 */
public object Steps {
    private const val F32_EPSILON: Double = 1.1920928955078125E-7
    private const val F64_EPSILON: Double = 2.220446049250313E-16

    /**
     * Whether [value] is off the lattice whose step is `(step + stepLow) /
     * factor` and whose phase is `(phase + phaseLow) / factor`. [factor] is a
     * power of two that lifts a subnormal step into the normal range, else 1;
     * [originalStep] is the step rounded to a `Double`, at most
     * `Double.MAX_VALUE`. A value is on the lattice when a lattice point,
     * rounded to the value's precision, lies within `4·ε·scale` of it, at
     * most a quarter step; a step finer than the value's rounding cell
     * admits every finite value.
     */
    @JvmStatic
    public fun offGrid(
        value: Double,
        f32: Boolean,
        factor: Double,
        originalStep: Double,
        step: Double,
        stepLow: Double,
        phase: Double,
        phaseLow: Double,
    ): Boolean {
        val v = if (f32) value.toFloat().toDouble() else value
        if (!v.isFinite()) return true
        // Adjacent values define the actual rounding cell. Signed zero has
        // the same two adjacent magnitudes as positive zero.
        if (originalStep < if (f32) cell(v.toFloat()) else cell(v)) return false
        val epsilon = if (f32) F32_EPSILON else F64_EPSILON
        val scaledValue = v * factor
        val delta = scaledValue - phase
        val quotient = if (delta.isFinite()) delta / step else scaledValue / step - phase / step
        // Ties round away from zero, as the Rust check's do.
        val fraction = quotient % 1.0
        val index = quotient - fraction + if (fraction >= 0.5) 1.0 else if (fraction <= -0.5) -1.0 else 0.0
        val scale = max(max(abs(v), abs(phase / factor)), abs(originalStep))
        val tolerance = min(4.0 * epsilon * scale, originalStep / 4.0)
        // Scale large terms before reconstructing: a rounded high sum can
        // otherwise overflow before its negative decimal residual brings the
        // exact point back into finite range.
        val rescale = if (max(max(abs(scaledValue), abs(phase)), abs(step)) > Double.MAX_VALUE / 4.0) 0.25 else 1.0
        var distance = Double.POSITIVE_INFINITY
        // The estimate can select a neighbouring lattice index through
        // division rounding, so both neighbours are checked too.
        for (candidate in doubleArrayOf(index - 1.0, index, index + 1.0)) {
            val right = step * rescale
            val product = candidate * right
            val productError = productError(candidate, right, product)
            val scaledPhase = phase * rescale
            val sum = product + scaledPhase
            val part = sum - product
            val sumError = (product - (sum - part)) + (scaledPhase - part)
            val low = candidate * (stepLow * rescale) + phaseLow * rescale + productError + sumError
            var nearest = (sum + low) / factor / rescale
            if (f32) nearest = nearest.toFloat().toDouble()
            if (nearest.isFinite()) distance = min(distance, abs(v - nearest))
        }
        return !quotient.isFinite() || distance > tolerance
    }

    /**
     * Whether [value] is none of [points], the lattice points of a step too
     * large for a `Double` that round to a finite one, within `4·ε` of the
     * larger magnitude.
     */
    @JvmStatic
    public fun offPoints(value: Double, f32: Boolean, vararg points: Double): Boolean {
        val v = if (f32) value.toFloat().toDouble() else value
        val epsilon = if (f32) F32_EPSILON else F64_EPSILON
        return !v.isFinite() || points.none { abs(v - it) <= 4.0 * epsilon * max(abs(v), abs(it)) }
    }

    /** The mean of the gaps to the two binary64 neighbours of `|value|`. */
    private fun cell(value: Double): Double {
        val represented = abs(value)
        val bits = represented.toRawBits()
        val below = if (bits == 0L) -Double.fromBits(1L) else Double.fromBits(bits - 1)
        val above = Double.fromBits(bits + 1)
        val lower = represented - below
        val upper = if (above.isFinite()) above - represented else lower
        return (lower + upper) / 2.0
    }

    /** The mean of the gaps to the two binary32 neighbours of `|value|`, in binary32 arithmetic. */
    private fun cell(value: Float): Double {
        val represented = abs(value)
        val bits = represented.toRawBits()
        val below = if (bits == 0) -Float.fromBits(1) else Float.fromBits(bits - 1)
        val above = Float.fromBits(bits + 1)
        val lower = (represented - below).toDouble()
        val upper = if (above.isFinite()) (above - represented).toDouble() else lower
        return (lower + upper) / 2.0
    }

    /**
     * The rounding error of `left * right` = [product], by Dekker's
     * two-product over halves split by clearing the low 27 mantissa bits,
     * which cannot overflow as the multiplying splitter can.
     */
    private fun productError(left: Double, right: Double, product: Double): Double {
        val mask = ((1L shl 27) - 1).inv()
        val leftHigh = Double.fromBits(left.toRawBits() and mask)
        val rightHigh = Double.fromBits(right.toRawBits() and mask)
        val leftLow = left - leftHigh
        val rightLow = right - rightHigh
        return ((leftHigh * rightHigh - product) + leftHigh * rightLow + leftLow * rightHigh) + leftLow * rightLow
    }
}
