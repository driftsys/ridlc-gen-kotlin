package ridl.rt.payload

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `Steps` over constants the plugin would pass. The generated checks over
 * the extreme lattices of driftsys/ridl's `payload_constraints.rs` are run in
 * the conformance module, compiled from the plugin's output.
 */
class StepsTest {
    /** `[0.0..1.0 step 0.1]`: phase 0, the step split into its double and residual. */
    private fun decimal(value: Double, f32: Boolean = false): Boolean {
        val step = BigDecimal("0.1")
        val high = step.toDouble()
        val low = step.subtract(BigDecimal(high)).toDouble()
        return Steps.offGrid(value, f32, 1.0, high, high, low, 0.0, 0.0)
    }

    @Test
    fun `a decimal lattice admits its points and refuses half steps`() {
        for (on in listOf(0.0, 0.1, 0.3, 0.7, 1.0, -0.0)) assertFalse(decimal(on), "$on")
        for (off in listOf(0.35, 0.05, 0.31)) assertTrue(decimal(off), "$off")
    }

    @Test
    fun `a value crossed the wire as a binary32 keeps its verdict`() {
        assertFalse(decimal(0.3f.toDouble(), f32 = true))
        assertFalse(decimal(0.3, f32 = true))
        assertTrue(decimal(0.35f.toDouble(), f32 = true))
    }

    @Test
    fun `no non-finite value is on a lattice`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(decimal(value), "$value")
            assertTrue(Steps.offPoints(value, false, 0.0), "$value")
        }
    }

    @Test
    fun `listed points admit themselves alone`() {
        assertFalse(Steps.offPoints(0.0, false, -1e308, 0.0, 1e308))
        assertFalse(Steps.offPoints(-0.0, false, 0.0))
        assertFalse(Steps.offPoints(1e308, false, -1e308, 0.0, 1e308))
        assertTrue(Steps.offPoints(1.0, false, -1e308, 0.0, 1e308))
        assertTrue(Steps.offPoints(0.5e308, false, -1e308, 0.0, 1e308))
    }
}
