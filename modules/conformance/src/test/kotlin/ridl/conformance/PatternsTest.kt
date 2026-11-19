package ridl.conformance

import com.google.protobuf.ListValue
import com.google.protobuf.util.JsonFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin.CodegenResponse
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A typl `match` pattern is an I-Regexp (RFC 9485) that matches the whole
 * value, and a string may carry several clauses, each of which it must match
 * (driftsys/ridl#597, driftsys/ridlc-gen-kotlin#186). ridl sends each clause's
 * normalised pattern, and the generated code compiles it with
 * `java.util.regex` unchanged and calls `matches`.
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

    /** The generated sources of [source], compiled; `accepts(type, value)` asks the type's `ofOrNull`. */
    private fun compiled(name: String, source: String): (String, String) -> Boolean {
        val response = response(name, source)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages)
        return { type, value ->
            val owner = Class.forName("probe.$name.$type", true, compiled.classLoader)
            val companion = owner.getField("Companion").get(null)
            val ofOrNull = companion.javaClass.methods.single { it.name.startsWith("ofOrNull") }
            ofOrNull.invoke(companion, value) != null
        }
    }

    private fun shown(value: String): String = value.map { "U+%04X".format(it.code) }.joinToString(" ")

    /**
     * ridl's agreement table: the pinned release's
     * `crates/ridl-ir/tests/pattern_agreement.json`, which the Rust and the
     * TypeScript outputs are held to, copied under `resources/patterns/`.
     * Every literal becomes one named string type, and every value gets the
     * table's verdict from the generated `ofOrNull`.
     */
    @Test
    fun `every pair of ridl's agreement table gets the table's verdict`() {
        val table = ListValue.newBuilder()
        val text = checkNotNull(javaClass.getResource("/patterns/pattern-agreement.json")).readText()
        JsonFormat.parser().merge(text, table)
        val rows = table.valuesList.map { it.structValue.fieldsMap }
        val literals = rows.map { it.getValue("literal").stringValue }.distinct()
        val source = literals.withIndex().joinToString("\n") { (i, literal) -> "type P$i : string [0..256 match $literal]" }
        val accepts = compiled("agreement", source)
        val differ = rows.mapNotNull { row ->
            val literal = row.getValue("literal").stringValue
            val value = row.getValue("value").stringValue
            val expected = row.getValue("accepted").boolValue
            val actual = accepts("P${literals.indexOf(literal)}", value)
            "$literal (${shown(value)}): expected $expected, got $actual".takeIf { actual != expected }
        }
        assertEquals(emptyList<String>(), differ)
        assertEquals(125, rows.size, "the table changed size: check it against the pinned release")
    }

    /** The design's whole-value case: `ridl.std.Date`'s pattern, which a search would accept inside a longer text. */
    @Test
    fun `a pattern matches the whole value, not a part of it`() {
        val accepts = compiled("whole", "type Day : string [0..16 match /[0-9]{4}-[0-9]{2}-[0-9]{2}/]")
        val verdicts = listOf("2026-11-18", "x2026-11-18", "2026-11-18\n", "١٢٣٤-١٢-١٢", "2026-11-18x")
            .map { "${shown(it)} ${accepts("Day", it)}" }
        assertEquals(
            listOf("true", "false", "false", "false", "false"),
            verdicts.map { it.substringAfterLast(' ') },
            verdicts.toString(),
        )
    }

    /**
     * A value must match every clause, the first that refuses it is the
     * violation, and an inline field checks its clauses as a named type does.
     */
    @Test
    fun `a value must match every clause of its type`() {
        val source = """
            type Token : string [1..16 match /.*[0-9].*/ match /.*[A-Z].*/]

            struct Login {
              token : string [1..16 match /.*[0-9].*/ match /.*[A-Z].*/]
            }
        """.trimIndent()
        val response = response("clauses", source)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile-clauses"))
        assertTrue(compiled.ok, compiled.messages)
        val token = Class.forName("probe.clauses.Token", true, compiled.classLoader)
        val companion = token.getField("Companion").get(null)
        val violation = companion.javaClass.methods.single { it.name.startsWith("violation") }
        val login = Class.forName("probe.clauses.Login", true, compiled.classLoader)
        fun refusal(value: String): String = try {
            login.constructors.single().newInstance(value)
            "accepted"
        } catch (e: InvocationTargetException) {
            e.targetException.message ?: e.targetException.javaClass.name
        }
        assertEquals(
            listOf("null", "Pattern", "Pattern", "Pattern"),
            listOf("A1", "a1", "AB", "ab").map { "${violation.invoke(companion, it)}" },
        )
        assertEquals("accepted", refusal("A1"))
        for (value in listOf("a1", "AB")) assertTrue("the pattern constraint of Login" in refusal(value), refusal(value))
    }
}
