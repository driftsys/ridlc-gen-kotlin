package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The float `require` and `ensure` clauses of the pinned `ridl`'s model,
 * compiled and run against an exact-rational oracle, as the Rust face's
 * `tests/float_clauses.rs` runs them (ridl 0.20.0, driftsys/ridl#1301,
 * driftsys/ridl#1358 and driftsys/ridl#1370).
 */
class FloatClausesTest {
    @TempDir
    lateinit var work: Path

    private class Generated(val sources: Map<String, String>, val diagnostics: List<String>)

    /** The generated sources and diagnostics of package `clause.test` holding [source]. */
    private fun generate(source: String): Generated {
        val pkg = work.resolve("src").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"clause.test\"\nversion = \"1.0.0\"\n\n[backend.kotlin]\nencodings = [\"flatbuffers\"]\n",
        )
        pkg.resolve("source.ridl").writeText(source)
        val responses = Harness.capturedRequests(pkg, "clause.test", work).values.map { Generator.generate(Wire.readRequest(it)) }
        return Generated(
            responses.flatMap { r -> r.filesList.map { it.path to it.text } }.toMap(),
            responses.flatMap { r -> r.diagnosticsList.map { it.message } },
        )
    }

    private val ops = listOf("le" to "<=", "gt" to ">", "ge" to ">=", "lt" to "<", "eq" to "==", "ne" to "!=")

    private fun holds(value: BigDecimal, op: String, literal: BigDecimal): Boolean {
        val c = value.compareTo(literal)
        return when (op) {
            "<=" -> c <= 0
            ">" -> c > 0
            ">=" -> c >= 0
            "<" -> c < 0
            "==" -> c == 0
            else -> c != 0
        }
    }

    /** A Kotlin expression for exactly [value], `-0.0` included. */
    private fun spelled(value: Double) = "Double.fromBits(${java.lang.Long.toUnsignedString(value.toRawBits())}UL.toLong())"

    private fun next(value: Double, steps: Long) = Double.fromBits(value.toRawBits() + steps)

    /**
     * Each case is a query per comparison whose `require` and `ensure` compare
     * with the literal; the probe checks both at each value against the exact
     * comparison of the value with the literal.
     */
    private fun run(cases: List<Triple<String, String, BigDecimal>>, comparisons: List<Pair<String, String>>, values: List<Double>) {
        val source = buildString {
            append("package clause.test\n\ntype Rate : float\n\ninterface Bounds {\n")
            for ((suffix, literal, _) in cases) {
                for ((name, op) in comparisons) {
                    append("  query $name$suffix(rate: Rate): Rate @[..1s] [require rate $op $literal\n  ensure result $op $literal]\n")
                }
            }
            append("}\n\ninterface Refused {\n  command set(rate: Rate) @[..1s] [require rate == 0.1]\n}\n")
        }
        val generated = generate(source)
        val note = generated.diagnostics.single()
        for (part in listOf(
            "`clause.test.Refused` has no generated face",
            "cannot translate contract clause `rate == 0.1`",
            "the literal has no exact binary64 value, so `==` and `!=` cannot be checked exactly",
        )) {
            assertTrue(part in note, "missing `$part` in $note")
        }
        val probe = buildString {
            append("package clause.check\n\nimport clause.test.*\n\nobject Probe {\n  @JvmStatic fun probe(): List<String> {\n")
            append("    val failures = mutableListOf<String>()\n")
            for (value in values) {
                val exact = BigDecimal(value)
                append("    run {\n      val value = Rate(${spelled(value)})\n")
                for ((suffix, _, literal) in cases) {
                    for ((name, op) in comparisons) {
                        val member = "Bounds${name.replaceFirstChar(Char::uppercaseChar)}$suffix"
                        val expected = holds(exact, op, literal)
                        append("      if ($member.require(value) != $expected) failures += \"require $member at $value\"\n")
                        append("      if ($member.ensure(value, value) != $expected) failures += \"ensure $member at $value\"\n")
                    }
                }
                append("    }\n")
            }
            append("    return failures\n  }\n}\n")
        }
        val compiled = Compiler.compile(generated.sources + ("clause/check/Probe.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages + "\n" + generated.sources["clause/test/Faces.kt"])
        assertTrue("object Refused" !in generated.sources.getValue("clause/test/Faces.kt"))
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("clause.check.Probe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `generated float clauses match exact rational comparisons`() {
        val tiny = "0.${"0".repeat(323)}1"
        run(
            listOf(
                Triple("Positive", "0.1", BigDecimal("0.1")),
                Triple("Negative", "-0.1", BigDecimal("-0.1")),
                Triple("Tiny", tiny, BigDecimal(tiny)),
                Triple("NegativeTiny", "-$tiny", BigDecimal("-$tiny")),
            ),
            ops.take(4),
            listOf(
                0.0, -0.0, -0.1, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.fromBits(1), -Double.fromBits(1), Double.fromBits(2), -Double.fromBits(2),
                next(-0.1, -1), next(-0.1, 1), next(0.1, -2), next(0.1, -1), 0.1, next(0.1, 1),
            ),
        )
    }

    @Test
    fun `generated exact literal clauses match exact rational comparisons`() {
        run(
            listOf(
                Triple("Half", "0.5", BigDecimal("0.5")),
                Triple("Padded", "1.50", BigDecimal("1.5")),
                Triple("NegFive", "-5", BigDecimal("-5")),
                Triple("Zero", "0", BigDecimal.ZERO),
            ),
            ops,
            listOf(
                0.0, -0.0, 0.25, -0.25, 0.5, next(0.5, -1), next(0.5, 1), 1.5, -5.0,
                next(1.5, 1), next(-5.0, 1), Double.MAX_VALUE, -Double.MAX_VALUE,
            ),
        )
    }
}
