package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.FloatClauses
import ridl.codegen.kotlin.types.FloatClauses.Bound
import ridl.codegen.kotlin.types.FloatClauses.Direction
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The float clause literal, as the Rust face's `clauses.rs` tests pin it
 * (ridl 0.20.0). Kotlin spells a double as `Double.toString` does, so
 * `5e-324` is `4.9E-324` and `1e22` is `1.0E22`.
 */
class FloatClausesTest {
    private val all = Direction.entries

    private fun literal(text: String) = Bound.Literal(text)

    @Test
    fun `float inequalities round in the refusing direction`() {
        for ((text, down, up) in listOf(
            Triple("0.1", "0.09999999999999999", "0.1"),
            Triple("1e-1", "0.09999999999999999", "0.1"),
            Triple("1E-1", "0.09999999999999999", "0.1"),
            Triple("-0.1", "-0.1", "-0.09999999999999999"),
            Triple("1e-324", "0.0", "4.9E-324"),
            Triple("-1e-324", "-4.9E-324", "-0.0"),
            Triple("-1e-10000", "-4.9E-324", "-0.0"),
            Triple("1.5e-10000", "0.0", "4.9E-324"),
            Triple("0.00000000001e-10000", "0.0", "4.9E-324"),
        )) {
            assertEquals(literal(down), FloatClauses.bound(text, Direction.DOWN), text)
            assertEquals(literal(up), FloatClauses.bound(text, Direction.UP), text)
        }
    }

    @Test
    fun `exact float literals are printed as Kotlin double literals`() {
        for ((text, expected) in listOf(
            "0.5" to "0.5", "+0.5" to "0.5", ".5" to "0.5", "-.5" to "-0.5", "+.5" to "0.5",
            "5." to "5.0", "-5." to "-5.0", "+5" to "5.0", "1.e3" to "1000.0", "1.50" to "1.5",
            "100.0" to "100.0", "100" to "100.0", "100e0" to "100.0", "1e2" to "100.0",
            "1e22" to "1.0E22", "0.0000152587890625" to "1.52587890625E-5", "1E+2" to "100.0",
            "-0.0" to "0.0", "-0.5" to "-0.5",
        )) {
            for (direction in all) assertEquals(literal(expected), FloatClauses.bound(text, direction), "$direction $text")
        }
    }

    @Test
    fun `the binary64 range boundary is exact`() {
        val max = BigDecimal(Double.MAX_VALUE).toBigIntegerExact()
        for (sign in listOf("", "-")) {
            for (direction in all) {
                assertEquals(literal("${sign}1.7976931348623157E308"), FloatClauses.bound("$sign$max", direction))
                for (offset in listOf(
                    BigInteger.ONE,
                    BigInteger.TEN.pow(291),
                    BigInteger.ONE.shiftLeft(970) - BigInteger.ONE,
                    BigInteger.ONE.shiftLeft(970) + BigInteger.ONE,
                )) {
                    assertEquals(Bound.Refused(FloatClauses.OUT_OF_RANGE), FloatClauses.bound("$sign${max + offset}", direction))
                }
            }
        }
    }

    @Test
    fun `literals outside the finite range are refused, not clamped`() {
        for (text in listOf("1e999", "-1e999", "1e10000", "-1.5e10000", "15e9999")) {
            for (direction in all) assertEquals(Bound.Refused(FloatClauses.OUT_OF_RANGE), FloatClauses.bound(text, direction), text)
        }
    }

    @Test
    fun `inexact float equality is refused`() {
        assertEquals(Bound.Refused(FloatClauses.INEXACT), FloatClauses.bound("0.1", Direction.EXACT))
    }

    @Test
    fun `the exponent limit is judged on the exponent, not on the scale`() {
        // `0.1e10001` has scale -10000 but exponent 10001.
        for (text in listOf("0.1e10001", "1e10001", "1e-10001", "1e2147483648", "", ".", "e5", "1e", "0x1p3")) {
            for (direction in all) assertEquals(Bound.Refused(FloatClauses.UNREADABLE), FloatClauses.bound(text, direction), text)
        }
    }
}
