package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Model
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * docs/design.md §7, "The codec round-trips and the verifier refuses", held to
 * the Rust codec the pinned release generates, for every corpus package.
 *
 * The generated `Codec.kt` encodes sample values of every public root
 * ([RoundTrip]); a corpus of those buffers and their mutants — every
 * truncation of each minimal sample, and each of its bytes set to 0x00, 0xFF
 * and itself plus one — is run through `verify`, `decode` and `encode` again,
 * by Kotlin here and by the Rust codec once, whose verdicts are checked in as
 * `resources/flatbuffers/<package>-codec-rust-verdicts.txt`. A verdict is
 * `ok:<first 16 hex digits of the SHA-256 of the re-encoded bytes>` or
 * `err:<the VerifyError>`.
 *
 * Every sample must be `ok` in Rust with the bytes Kotlin encoded, no mutant
 * may meet an exception other than `VerifyError`, and every verdict must be
 * Rust's — except that Kotlin refuses a string off its pattern, which the
 * Rust verifier checks only under its `validate-pattern` feature. Any other
 * difference fails.
 */
class CodecTest {
    @TempDir
    lateinit var work: Path

    private fun compact(verdict: String): String = when {
        verdict.startsWith("ok ") -> "ok:" + digest(verdict.removePrefix("ok "))
        verdict.startsWith("err ") -> "err:" + verdict.removePrefix("err ")
        else -> "?:$verdict"
    }

    private fun digest(hex: String): String =
        MessageDigest.getInstance("SHA-256").digest(hex.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun corpus(samples: List<String>): List<String> = samples.flatMap { line ->
        val (type, label, hex) = line.split(' ')
        val lines = mutableListOf(line)
        if (label == "minimal" || label.startsWith("arm-")) {
            val bytes = hex.chunked(2)
            for (n in 0 until bytes.size) lines += "$type $label/trunc$n ${bytes.take(n).joinToString("")}"
            for (i in bytes.indices) {
                for ((name, byte) in listOf("zero" to "00", "ones" to "ff", "inc" to "%02x".format((bytes[i].toInt(16) + 1) and 0xFF))) {
                    if (byte != bytes[i]) lines += "$type $label/$name$i ${bytes.toMutableList().also { it[i] = byte }.joinToString("")}"
                }
            }
        }
        lines
    }

    @TestFactory
    fun `the codec agrees with the Rust codec`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val requests = Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)
            val sources = requests.flatMap { r -> Generator.generate(r).filesList.map { it.path to it.text } }.toMap()
            val models: List<Model> = requests.map { it.model }
            val compiled = Compiler.compile(sources + ("roundtrip/RoundTrip.kt" to RoundTrip.source(models)), work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
            val facade = compiled.classLoader!!.loadClass("ridl.conformance.roundtrip.RoundTrip")
            @Suppress("UNCHECKED_CAST")
            val samples = facade.getMethod("samples").invoke(null) as List<String>
            val verdict = facade.getMethod("verdict", String::class.java, String::class.java)

            val corpus = corpus(samples)
            val out = Path.of(System.getProperty("spike.dir", work.toString())).resolve("$name-codec-corpus.txt")
            Files.createDirectories(out.parent)
            Files.writeString(out, corpus.joinToString("\n", postfix = "\n"))

            val escaped = mutableListOf<String>()
            val kotlin = corpus.map { line ->
                val (type, label, hex) = line.split(' ')
                try {
                    compact(verdict.invoke(null, type, hex) as String)
                } catch (e: InvocationTargetException) {
                    escaped += "$type $label: ${e.targetException}"
                    "threw:${e.targetException::class.simpleName}"
                }
            }
            assertEquals(emptyList<String>(), escaped.take(10), "${escaped.size} buffers met an exception other than VerifyError")

            val resource = javaClass.getResource("/flatbuffers/$name-codec-rust-verdicts.txt")
                ?: error("no resources/flatbuffers/$name-codec-rust-verdicts.txt: run the Rust round trip over build/spike/$name-corpus.txt")
            val rust = resource.readText().lines().filter { it.isNotBlank() }
            assertEquals(corpus.size, rust.size, "the Rust verdicts are for another corpus; regenerate them")

            for ((index, line) in corpus.withIndex()) {
                val (_, label, hex) = line.split(' ')
                if ('/' !in label) assertEquals("ok:" + digest(hex), rust[index], "$line: Rust re-encodes the sample Kotlin encoded")
            }
            // Kotlin's own refusals: a pattern, which the Rust verifier
            // checks only under its `validate-pattern` feature. Since ridl
            // 0.5.1 (driftsys/ridl#654) the Rust verifier checks every step,
            // every non-finite float and every inline constraint, as Kotlin does.
            val violation = Regex("""err:Contract\(Violation \{ type_name: "([^"]+)", rule: (\w+) \}\)""")
            fun kotlinOnly(verdict: String): Boolean = violation.matchEntire(verdict)?.destructured?.component2() == "Pattern"
            val stricter = mutableListOf<String>()
            val disagreements = mutableListOf<String>()
            for ((index, line) in corpus.withIndex()) {
                val k = kotlin[index]
                val r = rust[index]
                when {
                    k == r -> {}
                    r.startsWith("ok:") && kotlinOnly(k) -> stricter += k
                    else -> disagreements += "${line.substringBeforeLast(' ')}\n  kotlin: $k\n  rust:   $r"
                }
            }
            assertEquals(emptyList<String>(), disagreements.take(10), "${disagreements.size} disagreements")
            val reasons = stricter.groupingBy { it.removePrefix("err:") }.eachCount()
            println("$name: ${corpus.size} buffers, ${kotlin.count { it.startsWith("err:") }} refused, ${stricter.size} refused by Kotlin alone: $reasons")
        }
    }
}
