package ridl.codegen.kotlin.types

import java.math.BigDecimal

/**
 * The literal of a float `require` or `ensure` comparison, as the Rust face
 * prints it (ridl 0.20.0, driftsys/ridl#1301, driftsys/ridl#1358 and driftsys/ridl#1370).
 *
 * The literal is read as an exact decimal and the bound keeps expr-core's
 * exact rational comparison: `<=` and `>` take the largest binary64 value at
 * or below the literal, `>=` and `<` the smallest at or above it. An exactly
 * representable literal is printed as Kotlin's `Double.toString` spells it, a
 * Kotlin literal that reads back as the same binary64. A float subject is a
 * `Double` whatever its wire width, as it is an `f64` in the Rust face, so a
 * `f32` subject takes the same binary64 bound.
 *
 * Refused, with the Rust face's reasons: `==` and `!=` over a literal with no
 * exact binary64 value, a text that is not an exact decimal or whose exponent
 * has a magnitude above 10000, and a literal outside the finite binary64
 * range, which is not clamped.
 */
internal object FloatClauses {
    /** Which binary64 neighbour of an inexact literal the comparison takes. */
    enum class Direction {
        /** `<=` and `>`: the largest value at or below the literal. */
        DOWN,

        /** `>=` and `<`: the smallest value at or above the literal. */
        UP,

        /** `==` and `!=`: the literal itself, so an inexact one is refused. */
        EXACT,
    }

    /** The bound's Kotlin literal, or the reason it is refused. */
    sealed interface Bound {
        data class Literal(val text: String) : Bound

        data class Refused(val reason: String) : Bound
    }

    const val UNREADABLE = "the literal cannot be read as an exact decimal"
    const val OUT_OF_RANGE = "the literal is outside the finite binary64 range"
    const val INEXACT = "the literal has no exact binary64 value, so `==` and `!=` cannot be checked exactly"

    /** The largest exponent magnitude read, as the Rust face guards its allocation. */
    private const val EXPONENT_LIMIT = 10000

    private val MAX = BigDecimal(Double.MAX_VALUE)
    private val COEFFICIENT = Regex("[+-]?[0-9][0-9_]*")

    fun bound(text: String, direction: Direction): Bound {
        val exact = decimal(text) ?: return Bound.Refused(UNREADABLE)
        if (exact.abs() > MAX) return Bound.Refused(OUT_OF_RANGE)
        // Start at the nearest binary64 value; only the exact comparison
        // below decides which way an inexact literal moves.
        val nearest = exact.toDouble()
        val ordering = BigDecimal(nearest).compareTo(exact)
        if (ordering == 0) return Bound.Literal(nearest.toString())
        val bound = when (direction) {
            Direction.EXACT -> return Bound.Refused(INEXACT)
            Direction.DOWN -> if (ordering > 0) Math.nextDown(nearest) else nearest
            Direction.UP -> if (ordering < 0) Math.nextUp(nearest) else nearest
        }
        return Bound.Literal(bound.toString())
    }

    /** The exact value of a decimal text, read as the Rust face's `decimal` reads it. */
    fun decimal(text: String): BigDecimal? {
        val split = text.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = if (split < 0) text else text.substring(0, split)
        val exponent = (if (split < 0) "0" else text.substring(split + 1)).toIntOrNull() ?: return null
        val point = mantissa.indexOf('.')
        val whole = if (point < 0) mantissa else mantissa.substring(0, point)
        val fraction = if (point < 0) "" else mantissa.substring(point + 1)
        // The digits follow the grammar of Rust's `BigInt`: an optional sign, then ASCII digits with `_`
        // between or after them. Kotlin's reader refuses `_` and a leading `+`, and accepts non-ASCII digits.
        val digits = "$whole$fraction"
        if (!COEFFICIENT.matches(digits)) return null
        val coefficient = digits.replace("_", "").removePrefix("+").toBigInteger()
        if (exponent < -EXPONENT_LIMIT || exponent > EXPONENT_LIMIT) return null
        val scale = fraction.length.toLong() - exponent
        if (scale !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return BigDecimal(coefficient, scale.toInt())
    }
}
