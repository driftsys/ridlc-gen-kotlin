package ridl.codegen.kotlin.types

import ridl.codegen.v1.ModelOuterClass.Constraint
import java.math.BigInteger

/**
 * Whether 0, the FlatBuffers default of an absent scalar or enum field, is a
 * value of a numeric scalar's declared range and step: `ridl_ir::zero::
 * range_holds_zero` of the pinned release, the rule its Rust codec reads an
 * absent non-optional field by (driftsys/ridl#472).
 *
 * 0 must lie within `[min..max]` and, when a `step` is declared, on the grid
 * `min + n·step` for a whole `n`. A `step` with no `min`, or a bound that is
 * not plain decimal text, is not decided and answers `false`, so the absent
 * field is refused. A constraint with no bound holds 0.
 */
internal fun Constraint.holdsZero(): Boolean {
    val min = if (hasMin()) Decimal.parse(min) ?: return false else null
    val max = if (hasMax()) Decimal.parse(max) ?: return false else null
    val step = if (hasStep()) Decimal.parse(step) ?: return false else null
    if ((min != null && min.units.signum() > 0) || (max != null && max.units.signum() < 0)) return false
    if (step == null) return true
    if (min == null) return false
    val scale = maxOf(min.scale, step.scale)
    val anchor = min.rescaled(scale) ?: return false
    val grid = step.rescaled(scale) ?: return false
    return grid.signum() > 0 && anchor.rem(grid).signum() == 0
}

/**
 * An exact decimal `units / 10^scale`, read as the Rust `Decimal` reads the
 * IR's text: `-`, digits, and an optional `.` followed by digits, at most 30
 * digits in all, with every value held in an `i128`.
 */
private class Decimal(val units: BigInteger, val scale: Int) {
    /** [units] at a larger [scale], or `null` past an `i128`, as Rust's checked arithmetic. */
    fun rescaled(scale: Int): BigInteger? {
        val value = units.multiply(BigInteger.TEN.pow(scale - this.scale))
        return value.takeIf { it.bitLength() <= 127 }
    }

    companion object {
        fun parse(text: String): Decimal? {
            val negative = text.startsWith('-')
            val unsigned = if (negative) text.substring(1) else text
            val point = unsigned.indexOf('.')
            val whole = if (point < 0) unsigned else unsigned.substring(0, point)
            val fraction = if (point < 0) "" else unsigned.substring(point + 1)
            val digits = { part: String -> part.all { it in '0'..'9' } }
            if (whole.isEmpty() || !digits(whole) || !digits(fraction) || (point >= 0 && fraction.isEmpty()) ||
                whole.length + fraction.length > 30
            ) {
                return null
            }
            val units = BigInteger(whole + fraction)
            return Decimal(if (negative) units.negate() else units, fraction.length)
        }
    }
}
