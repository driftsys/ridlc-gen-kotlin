package ridl.rt.payload

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `isWellFormedString` over the unpaired surrogates a Kotlin `String` can
 * hold and UTF-8 cannot carry (typl §4.4). The generated checks that call it
 * are run in the conformance module, compiled from the plugin's output.
 */
class WellFormedTest {
    @Test
    fun `an unpaired surrogate anywhere is ill-formed`() {
        for (value in listOf("x\uD800y", "\uDC00", "a\uD800", "\uD800", "\uDC00\uD800", "\uD83D😀\uDE00")) {
            assertFalse(isWellFormedString(value), value.map { "%04X".format(it.code) }.toString())
        }
    }

    @Test
    fun `a surrogate pair and text without surrogates are well-formed`() {
        for (value in listOf("", "abc", "héé", "�", "😀", "a😀b😀", "𐀀", "􏿿")) {
            assertTrue(isWellFormedString(value), value)
        }
    }
}
