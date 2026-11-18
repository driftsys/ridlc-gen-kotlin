package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin.CodegenResponse
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * driftsys/ridlc-gen-kotlin#12: a `match` pattern the pinned `ridl` accepts —
 * ECMA-262 syntax (TYPL-106) the Rust `regex` crate compiles (TYPL-220) — that
 * `java.util.regex` cannot compile is refused by the plugin, naming the type,
 * the pattern and Java's reason, where the generated `Regex(…)` threw
 * `PatternSyntaxException` when its class loaded. A pattern Java compiles
 * still generates, and its class loads.
 */
class PatternsTest {
    @TempDir
    lateinit var work: Path

    /** The plugin's response for package `probe.<name>`; `ridl build` accepting [source] is checked on the way. */
    private fun response(name: String, source: String): CodegenResponse {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText(Harness.manifest("probe.$name"))
        pkg.resolve("$name.ridl").writeText("package probe.$name\n\n$source")
        val requests = Harness.capturedRequests(pkg, name, work, "--emit", "codegen-model")
        return Generator.generate(Wire.readRequest(requests.getValue("probe.$name")))
    }

    @Test
    fun `a pattern java util regex cannot compile refuses its declaration`() {
        val response = response(
            "refused",
            """
            const GREEK_LETTERS = /^\p{Greek}+/

            type Greek    : string [1..8 match GREEK_LETTERS]
            type Letters  : string [1..8 match /^\p{Letter}+/]
            type Escaped  : string [1..8 match /^\u{41}/]
            type Fine     : string [1..8 match /^\p{L}+/]

            struct Tagged {
              emoji : string [1..8 match /\p{Emoji}/]
              fine  : Fine
            }
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), response.filesList.map { it.path })
        assertEquals(
            listOf(
                "ridlc-gen-kotlin: `probe.refused.Greek` cannot be generated: its pattern `/^\\p{Greek}+/` does not " +
                    "compile under java.util.regex: Unknown character property name {Greek}",
                "ridlc-gen-kotlin: `probe.refused.Letters` cannot be generated: its pattern `/^\\p{Letter}+/` does not " +
                    "compile under java.util.regex: Unknown character property name {Letter}",
                "ridlc-gen-kotlin: `probe.refused.Escaped` cannot be generated: its pattern `/^\\u{41}/` does not " +
                    "compile under java.util.regex: Illegal Unicode escape sequence",
                "ridlc-gen-kotlin: `probe.refused.Tagged` cannot be generated: its pattern `/\\p{Emoji}/` does not " +
                    "compile under java.util.regex: Unknown character property name {Emoji}",
            ),
            response.diagnosticsList.map { it.message },
        )
    }

    /**
     * Patterns that pass `ridl check` and that Java compiles too, among them
     * the constructs where the three engines' syntaxes differ most: Unicode
     * classes, a script by `sc=`, a POSIX class, a named group, `\x{..}`, a
     * class intersection and `\z`. Each type's class is initialized, which is
     * when its `Regex` compiles.
     */
    @Test
    fun `a pattern java util regex compiles generates and its class loads`() {
        val patterns = listOf(
            """^\p{L}+""", """^\P{L}""", """^\p{Lu}""", """^\pN{1,3}""", """^\p{sc=Greek}""", """^\p{gc=L}""",
            """^[[:alpha:]]""", """^(?<n>a)""", """^\x{41}""", """^\x41""", """^[a-z&&[^aeiou]]""", """^a\z""",
            """^\w{1,8}""", """^(?i:a)""", """^[\s\S]""", """^\d+(\.\d+)?""", """^[\d-]""", """^\/""",
        )
        val source = patterns.withIndex().joinToString("\n") { (i, p) -> "type P$i : string [1..8 match /$p/]" }
        val response = response("compiled", source)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        for (i in patterns.indices) Class.forName("probe.compiled.P$i", true, compiled.classLoader)
    }

    /**
     * driftsys/ridlc-gen-kotlin#155: a generated check accepts the strings the
     * Rust output's `regex::Regex::is_match` accepts. Under the `regex`
     * defaults, `.` is any scalar value except `\n`, `$` is the end of the
     * text only, and `\d` is a Unicode digit. The `java.util.regex` defaults
     * differ on all three: `.` also refuses `\r`, U+0085, U+2028 and U+2029,
     * `$` also matches before a final line terminator, and `\d` is ASCII.
     */
    @Test
    fun `a pattern accepts the strings the Rust regex crate accepts`() {
        val response = response(
            "rust",
            """
            type Glyph   : string [0..4 match /^.$/]
            type Digited : string [0..16 match /\d/]
            type Code    : string [1..8 match /^[A-Z$]+$/]
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        fun accepts(type: String, value: String): Boolean {
            val owner = Class.forName("probe.rust.$type", true, compiled.classLoader)
            val companion = owner.getField("Companion").get(null)
            val ofOrNull = companion.javaClass.methods.single { it.name.startsWith("ofOrNull") }
            return ofOrNull.invoke(companion, value) != null
        }
        val cases = listOf(
            "Glyph" to "a", "Glyph" to "😀", "Glyph" to "�", "Glyph" to "\r", "Glyph" to "\u0085",
            "Glyph" to " ", "Glyph" to " ", "Glyph" to "", "Glyph" to "ab", "Glyph" to "\n",
            "Glyph" to "a\n", "Digited" to "1", "Digited" to "x٣", "Digited" to "x",
            "Code" to "AB", "Code" to "A$", "Code" to "AB\n", "Code" to "ab",
        )
        val verdicts = cases.map { (type, value) ->
            "$type(${value.map { "U+%04X".format(it.code) }.joinToString(" ")}) ${accepts(type, value)}"
        }
        assertEquals(
            listOf(
                "Glyph(U+0061) true", "Glyph(U+D83D U+DE00) true", "Glyph(U+FFFD) true", "Glyph(U+000D) true",
                "Glyph(U+0085) true", "Glyph(U+2028) true", "Glyph(U+2029) true", "Glyph() false",
                "Glyph(U+0061 U+0062) false", "Glyph(U+000A) false", "Glyph(U+0061 U+000A) false",
                "Digited(U+0031) true", "Digited(U+0078 U+0663) true", "Digited(U+0078) false",
                "Code(U+0041 U+0042) true", "Code(U+0041 U+0024) true", "Code(U+0041 U+0042 U+000A) false",
                "Code(U+0061 U+0062) false",
            ),
            verdicts,
        )
    }

    @Test
    fun `vertical whitespace and unsupported class operators are refused`() {
        val patterns = listOf(
            """\v""", """[\v]""", "[a-z--b]", "[a-z~~b]",
            "[a-z&&[a-z--b]]", "[a-z&&[a-z~~b]]",
        )
        for ((i, pattern) in patterns.withIndex()) {
            val response = response("unsupported$i", "type Value : string [1..8 match /$pattern/]")
            assertEquals(emptyList<String>(), response.filesList.map { it.path })
            val message = response.diagnosticsList.single().message
            assertTrue("`probe.unsupported$i.Value`" in message && "/$pattern/" in message, message)
            assertTrue("does not translate" in message, message)
        }
    }

    @Test
    fun `escaped backslashes and operators outside classes remain literals`() {
        val response = response(
            "literals",
            """
            type Slash : string [1..8 match /\\v/]
            type Dash  : string [1..8 match /a--b/]
            type Tilde : string [1..8 match /a~~b/]
            type Class : string [1..8 match /[a\-\-b]/]
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        fun accepts(type: String, value: String): Boolean {
            val owner = Class.forName("probe.literals.$type", true, compiled.classLoader)
            val companion = owner.getField("Companion").get(null)
            val ofOrNull = companion.javaClass.methods.single { it.name.startsWith("ofOrNull") }
            return ofOrNull.invoke(companion, value) != null
        }
        val cases = listOf(
            Triple("Slash", "\\v", true), Triple("Slash", "v", false), Triple("Slash", "\u000B", false),
            Triple("Dash", "a--b", true), Triple("Dash", "ab", false),
            Triple("Tilde", "a~~b", true), Triple("Tilde", "ab", false),
            Triple("Class", "-", true), Triple("Class", "c", false),
        )
        for ((type, value, expected) in cases) assertEquals(expected, accepts(type, value), "$type($value)")
    }

    /** A pattern that turns on the multi-line flag `m` in a group is refused, not translated. */
    @Test
    fun `a pattern with a multi-line group is refused`() {
        val response = response("multiline", "type Multi : string [1..8 match /^(?m:a$)/]")
        assertEquals(emptyList<String>(), response.filesList.map { it.path })
        val message = response.diagnosticsList.single().message
        assertTrue("`probe.multiline.Multi`" in message && "multi-line flag `m`" in message, message)
    }

    /**
     * An escaped `$` stays a literal, `\w`, `\s` and `\b` are Unicode
     * classes, and a `$` inside a nested class stays a member of the class.
     * Each verdict is the one the Rust `regex` crate gives.
     */
    @Test
    fun `an escaped dollar, a Unicode class and a nested class match as the Rust regex crate does`() {
        val response = response(
            "classes",
            """
            type Price  : string [1..8 match /^a\$/]
            type Word   : string [1..8 match /^\w+$/]
            type Space  : string [1..8 match /^\s$/]
            type Edge   : string [1..8 match /\bé/]
            type Nested : string [1..8 match /^[a[$]]+$/]
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        fun accepts(type: String, value: String): Boolean {
            val owner = Class.forName("probe.classes.$type", true, compiled.classLoader)
            val companion = owner.getField("Companion").get(null)
            val ofOrNull = companion.javaClass.methods.single { it.name.startsWith("ofOrNull") }
            return ofOrNull.invoke(companion, value) != null
        }
        val cases = listOf(
            Triple("Price", "a$", true), Triple("Price", "a", false), Triple("Price", "ab", false),
            Triple("Word", "é", true), Triple("Word", "a-", false),
            Triple("Space", "\u00A0", true), Triple("Space", "\u2003", true), Triple("Space", "x", false),
            Triple("Edge", " é", true), Triple("Edge", "aé", false),
            Triple("Nested", "a$", true), Triple("Nested", "$", true), Triple("Nested", "b", false),
        )
        val wrong = cases.filter { (type, value, expected) -> accepts(type, value) != expected }
        assertEquals(emptyList<Triple<String, String, Boolean>>(), wrong)
    }
}
