package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.holdsZero
import ridl.codegen.v1.ModelOuterClass.Constraint

/** Whether 0 is legal for a range and a step: the table of `ridl_ir::zero`'s own test, case for case. */
class ZeroTest {
    private fun constraint(min: String?, max: String?, step: String?): Constraint = Constraint.newBuilder().apply {
        min?.let { setMin(it) }
        max?.let { setMax(it) }
        step?.let { setStep(it) }
    }.build()

    @Test
    fun `whether 0 is legal follows the range and the step`() {
        val cases = listOf(
            Triple(null, null, null) to true,
            Triple("0", "10", null) to true,
            Triple("1", "10", null) to false,
            Triple("-10", "-1", null) to false,
            // A bound of exactly 0 holds it, from either side.
            Triple("-10", "0", null) to true,
            Triple("0", "0", null) to true,
            Triple("-1.5", "1.5", "1.0") to false,
            Triple("-1.0", "1.0", "1.0") to true,
            Triple("0.0", "1.0", "0.01") to true,
            Triple("-0.25", "1", "0.125") to true,
            Triple("-0.3", "1", "0.2") to false,
            // A step with no lower bound has zero as its origin.
            Triple(null, "10", "1") to true,
            Triple(null, null, "0.5") to true,
            Triple(null, null, "0") to false,
            Triple(null, null, "-0.5") to false,
            // Text this reader does not accept is not decided either.
            Triple("-1e3", "10", null) to false,
            Triple("-1.", "10", null) to false,
        )
        for ((bounds, legal) in cases) {
            val (min, max, step) = bounds
            assertEquals(legal, constraint(min, max, step).holdsZero(), "min $min, max $max, step $step")
        }
    }

    @Test
    fun `a zero origin holds 0 at any step precision`() {
        val fine = "0." + "0".repeat(399) + "1"
        val large = "1" + "0".repeat(400) + ".0"
        for (step in listOf(fine, large)) {
            assertEquals(true, constraint("0.0", null, step).holdsZero(), step)
            assertEquals(true, constraint(null, "1.0", step).holdsZero(), step)
            assertEquals(false, constraint(null, null, "-$step").holdsZero(), step)
        }
    }

    @Test
    fun `a bound past an i128 once rescaled is not decided`() {
        // 30 digits rescaled by 30 more places overflows the i128 the Rust rule computes in.
        assertEquals(false, constraint("-" + "9".repeat(30), "1", "0." + "0".repeat(29) + "1").holdsZero())
    }
}
