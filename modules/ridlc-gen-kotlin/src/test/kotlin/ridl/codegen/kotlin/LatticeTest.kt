package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.Lattice
import java.math.BigInteger

/** The integer step check, at the steps a `Long` cannot hold. Integer `step` is not a source form (TYPL-105). */
class LatticeTest {
    private val two63 = BigInteger.TWO.pow(63)
    private val min = "(-9223372036854775807L - 1L)"

    private fun check(origin: String?, step: BigInteger) = Lattice.integer(origin, step.toString(), "v").toString()

    @Test
    fun `a step below 2^63 is a floorMod`() {
        assertEquals("java.lang.Math.floorMod(v, 4L) != 1L", check("-3", BigInteger.valueOf(4)))
    }

    @Test
    fun `a step of 2^63 admits two values of a Long`() {
        // #54: -2^63 and 0 are both on the lattice from 0.
        assertEquals("v != $min && v != 0L", check(null, two63))
        assertEquals("v != -2L && v != 9223372036854775806L", check("-2", two63))
    }

    @Test
    fun `a step between 2^63 and 2^64 can still admit two values`() {
        // From -2^63 + 3, the next point is -2^63 + 3 + 2^63 + 10 = 13.
        val step = two63 + BigInteger.TEN
        assertEquals("v != -9223372036854775805L && v != 13L", check("-9223372036854775805", step))
        assertEquals("v != 5L", check("5", step), "5 - step and 5 + step both leave the range")
    }

    @Test
    fun `a step of 2^64 or more admits at most one value`() {
        assertEquals("v != 5L", check("5", BigInteger.TWO.pow(64)))
    }

    @Test
    fun `a lattice that misses the Long range admits none`() {
        val origin = BigInteger.TWO.pow(64) + BigInteger.valueOf(3)
        assertEquals("true", check(origin.toString(), BigInteger.TWO.pow(65)))
    }
}
