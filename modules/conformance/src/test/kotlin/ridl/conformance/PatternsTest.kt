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
        pkg.resolve("ridl.toml").writeText("[package]\nname = \"probe.$name\"\nversion = \"1.0.0\"\n")
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
}
