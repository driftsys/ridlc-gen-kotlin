package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The K1b spike (docs/design.md §4 and §8, O-K1 option A): whether FlatBuffers
 * can stay the payload encoding once ridl-rt-kt has a verifier. The cabin
 * package's six payload codecs, written by hand over `ridl.rt.flatbuffers`
 * (resources/flatbuffers/cabin-codec.kt), are held to the Rust codec the
 * pinned release generates:
 *
 * - `cabin-golden.txt` is what the Rust codec encodes for sixteen sample
 *   values; Kotlin must encode the same bytes and decode them to the same
 *   values;
 * - a mutation corpus derived from those bytes — every truncation, and every
 *   byte set to 0x00, to 0xFF and to itself plus one, and each buffer grown
 *   past its type's size bound — must be refused by `verify` or decoded,
 *   never met with an exception of the JVM's own (§7, "the verifier
 *   refuses"), and each verdict must be the Rust verifier's, as recorded in
 *   `cabin-rust-verdicts.txt`.
 *
 * The corpus is written to `build/spike/cabin-corpus.txt` on every run, and
 * the verdicts file is what the Rust program of the spike record prints for
 * it (docs/k1b-flatbuffers-spike.md).
 */
class SpikeTest {
    @TempDir
    lateinit var work: Path

    private class Golden(val type: String, val label: String, val hex: String)

    private fun golden(): List<Golden> = resource("cabin-golden.txt").lines().filter { it.isNotBlank() }.map {
        val (type, label, hex) = it.split(' ')
        Golden(type, label, hex)
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/flatbuffers/$name")) { "missing resources/flatbuffers/$name" }.readText()

    private fun codec(): ClassLoader {
        val request = Wire.readRequest(Harness.capturedRequest("cabin", work))
        val types = Generator.generate(request).filesList.single { it.path.endsWith("/Types.kt") }
        val compiled = Compiler.compile(
            mapOf(types.path to types.text, "spike/CabinCodec.kt" to resource("cabin-codec.kt")),
            work.resolve("compile"),
        )
        assertTrue(compiled.ok, compiled.messages)
        return compiled.classLoader!!
    }

    /** The corpus: one `type label hex` line per mutant, in a fixed order. */
    private fun corpus(): List<String> = golden().flatMap { g ->
        val bytes = g.hex.chunked(2)
        val mutants = mutableListOf<Pair<String, List<String>>>()
        for (n in 0 until bytes.size) mutants += "trunc$n" to bytes.take(n)
        for (i in bytes.indices) {
            for ((name, byte) in listOf("zero" to "00", "ones" to "ff", "inc" to "%02x".format((bytes[i].toInt(16) + 1) and 0xFF))) {
                if (byte != bytes[i]) mutants += "$name$i" to bytes.toMutableList().also { it[i] = byte }
            }
        }
        mutants += "grown" to bytes + List(64) { "00" }
        mutants.map { (name, mutant) -> "${g.type} ${g.label}/$name ${mutant.joinToString("")}" }
    }

    @Test
    fun `Kotlin encodes the bytes the Rust codec encodes, and decodes them back`() {
        val loader = codec()
        val facade = loader.loadClass("ridl.conformance.spike.CabinCodec")
        val encode = facade.getMethod("encode", String::class.java, String::class.java)
        val verdict = facade.getMethod("verdict", String::class.java, String::class.java)
        for (g in golden()) {
            assertEquals(g.hex, encode.invoke(null, g.type, g.label), "${g.type} ${g.label}")
            val decoded = verdict.invoke(null, g.type, g.hex) as String
            assertTrue(decoded.startsWith("ok "), "${g.type} ${g.label}: $decoded")
        }
    }

    @Test
    fun `every mutant is refused by verify or decoded, as the Rust verifier does`() {
        val loader = codec()
        val verdict = loader.loadClass("ridl.conformance.spike.CabinCodec")
            .getMethod("verdict", String::class.java, String::class.java)
        val corpus = corpus()
        val out = Path.of(System.getProperty("spike.dir", work.toString())).resolve("cabin-corpus.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, corpus.joinToString("\n", postfix = "\n"))

        val escaped = mutableListOf<String>()
        val kotlin = corpus.map { line ->
            val (type, label, hex) = line.split(' ')
            val answer = try {
                verdict.invoke(null, type, hex) as String
            } catch (e: InvocationTargetException) {
                escaped += "$type $label: ${e.targetException}"
                "threw ${e.targetException::class.simpleName}"
            }
            "$type $label $answer"
        }
        assertEquals(emptyList<String>(), escaped, "a malformed buffer met an exception other than VerifyError")

        val rust = resource("cabin-rust-verdicts.txt").lines().filter { it.isNotBlank() }
        assertEquals(corpus.size, rust.size, "the Rust verdicts are for this corpus; regenerate them")
        val disagreements = kotlin.zip(rust).filter { (k, r) -> k != r }.map { (k, r) -> "kotlin: $k\n  rust: $r" }
        assertEquals(emptyList<String>(), disagreements.take(20), "${disagreements.size} disagreements")
        val refused = kotlin.count { " err " in it }
        assertTrue(refused > corpus.size / 2, "the corpus is mostly malformed: $refused of ${corpus.size} refused")
    }
}
