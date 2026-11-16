package ridl.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * #148: a `WrongIdentifier` message shows the identifier as Rust's `{:?}`
 * shows `String::from_utf8_lossy` of it: `<str as Debug>` over
 * `char::escape_debug_ext`. Each expected value is what Rust prints.
 */
class QuotedTest {
    private fun quoted(vararg bytes: Int): String = quoted(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `printable ASCII is shown as it is`() {
        assertEquals("\"RDLC\"", quoted(*"RDLC".map { it.code }.toIntArray()))
    }

    @Test
    fun `NUL, a double quote and a backslash are escaped, a single quote is not`() {
        assertEquals("\"\\0\\\"\\\\'\"", quoted(0, '"'.code, '\\'.code, '\''.code))
    }

    @Test
    fun `tab, carriage return and line feed have their own escapes`() {
        assertEquals("\"\\t\\r\\nA\"", quoted(9, 13, 10, 'A'.code))
    }

    @Test
    fun `any other control character is a unicode escape in lowercase hex`() {
        assertEquals("\"\\u{1}\\u{1f}\\u{7f} \"", quoted(0x01, 0x1F, 0x7F, ' '.code))
        assertEquals("\"\\u{85}AB\"", quoted(0xC2, 0x85, 'A'.code, 'B'.code))
    }

    @Test
    fun `each maximal ill-formed subsequence is one replacement character`() {
        // ED takes a second byte of 80 to 9F only, so ED, A0 and 80 are each refused alone.
        assertEquals("\"\uFFFD\uFFFD\uFFFDA\"", quoted(0xED, 0xA0, 0x80, 'A'.code))
        // F0 9F 98 starts a four-byte character that A cuts short: one subsequence.
        assertEquals("\"\uFFFDA\"", quoted(0xF0, 0x9F, 0x98, 'A'.code))
        assertEquals("\"\uFFFDDLD\"", quoted(0xFF, 'D'.code, 'L'.code, 'D'.code))
    }

    @Test
    fun `a printable character past ASCII is shown, a combining mark, a space and a format character are escaped`() {
        assertEquals("\"\uD83D\uDE00\"", quoted(0xF0, 0x9F, 0x98, 0x80))
        assertEquals("\"\u00E9\\u{301}\"", quoted(0xC3, 0xA9, 0xCC, 0x81))
        assertEquals("\"\\u{a0}\\u{ad}\"", quoted(0xC2, 0xA0, 0xC2, 0xAD))
    }
}
