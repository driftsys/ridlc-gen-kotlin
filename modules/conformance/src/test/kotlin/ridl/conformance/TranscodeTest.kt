package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin
import ridl.rt.payload.EncodeError
import ridl.rt.payload.TranscodeError
import ridl.rt.payload.VerifyError
import ridl.rt.payload.Violation
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The transcode functions (#136): every corpus package whose `ridl.toml` sets
 * `transcode = true` for both backends, held to the Rust transcode functions
 * of the pinned release.
 *
 * The sources are [RoundTrip]'s samples of each codec. For every function
 * `<Type>Transcode.<s>To<D>` of the generated `Transcode.kt`, each sample of
 * `<Type>` in `s` is transcoded into an output of [CAPACITY] bytes, which
 * must succeed. Its bytes need not be `D`'s own sample of the value: podbuf
 * holds a map in key order, so a map decoded from it, and encoded again,
 * comes in that order, in Rust as here. Then the sample cut to
 * nothing, to half and to one byte short, and the sample with each byte set
 * to 0xFF, each of which `verify` may refuse; then into outputs of no byte,
 * of half and of one byte short of what the function wrote, which must be
 * `Capacity`, and of exactly that, which must not. Each line goes through
 * the Kotlin function and once through the Rust one, whose verdicts are
 * checked in as `resources/transcode/<package>-transcode-rust-verdicts.txt`
 * (`just rust-verdicts`), `ok:<digest16>` or `err:<the TranscodeError>`, and
 * every verdict must be Rust's.
 */
class TranscodeTest {
    @TempDir
    lateinit var work: Path

    private fun digest(hex: String): String =
        MessageDigest.getInstance("SHA-256").digest(hex.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun packages(): List<String> = Harness.packages().filter { Harness.transcode(it) }

    private fun requests(name: String): List<Plugin.CodegenRequest> =
        Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)

    private fun selecting(requests: List<Plugin.CodegenRequest>, encoding: String) =
        requests.filter { request -> request.optionsList.any { it.key == "encodings" && encoding in it.value.split(',') } }.map { it.model }

    /** One function of a generated transcode object: `<s>To<D>` of `<Type>Transcode`. */
    private class Function(val type: String, val source: String, val target: String, val call: (ByteBuffer, ByteBuffer) -> Int)

    /**
     * Every function of every public object of each `Transcode.kt` of
     * [sources], loaded from [loader]. An internal type has no sample, as the
     * Rust program cannot reach a `pub(crate)` one.
     */
    private fun functions(sources: Map<String, String>, loader: ClassLoader): List<Function> =
        sources.filterKeys { it.endsWith("/Transcode.kt") }.flatMap { (path, text) ->
            val pkg = path.substringBeforeLast('/').replace('/', '.')
            Regex("""(?m)^public object (\w+)Transcode""").findAll(text).flatMap { match ->
                val type = "$pkg.${match.groupValues[1]}"
                val owner = loader.loadClass("${type}Transcode")
                val instance = owner.getField("INSTANCE").get(null)
                owner.declaredMethods.sortedBy { it.name }.mapNotNull { method ->
                    val (source, target) = Regex("""([a-z0-9]+)To([A-Z][a-z0-9]*)""").matchEntire(method.name)?.destructured ?: return@mapNotNull null
                    Function(type, source, target.lowercase()) { bytes, out -> method.invoke(instance, bytes, out) as Int }
                }
            }.toList()
        }

    private fun unhex(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun debug(error: VerifyError): String = when (error) {
        is VerifyError.Structure -> "Structure(${error.malformed.name})"
        is VerifyError.Contract -> "Contract(${debug(error.violation)})"
    }

    private fun debug(error: EncodeError): String = when (error) {
        is EncodeError.Capacity -> "Capacity { needed: ${error.needed}, available: ${error.available} }"
        is EncodeError.Contract -> "Contract(${debug(error.violation)})"
        is EncodeError.Unaligned -> "Unaligned"
        is EncodeError.Layout -> "Layout { at: ${error.at} }"
    }

    private fun debug(violation: Violation): String =
        "Violation { type_name: ${violation.typeName?.let { "Some(\"$it\")" } ?: "None"}, rule: ${violation.rule.name} }"

    /** The verdict of [function] over the bytes [hex] spells, into an output of [capacity] bytes, as the Rust program spells it. */
    private fun verdict(function: Function, hex: String, capacity: Int): String {
        val out = ByteBuffer.allocate(capacity)
        val written = try {
            function.call(ByteBuffer.wrap(unhex(hex)), out)
        } catch (e: InvocationTargetException) {
            return when (val error = e.targetException) {
                is TranscodeError.Verify -> "err:Verify(${debug(error.error)})"
                is TranscodeError.Encode -> "err:Encode(${debug(error.error)})"
                else -> throw error
            }
        }
        assertEquals(written, out.position(), "${function.type}: the output advances past the bytes written")
        return "ok:" + digest(out.array().copyOfRange(0, written).joinToString("") { "%02x".format(it) })
    }

    @TestFactory
    fun `every transcode function agrees with the Rust one`(): List<DynamicTest> = packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val requests = requests(name)
            val sources = requests.flatMap { r -> Generator.generate(r).filesList.map { it.path to it.text } }.toMap()
            val roundTrips = mapOf(
                "flatbuffers" to RoundTrip.source(selecting(requests, "flatbuffers"), kotlinPackage = "ridl.conformance.roundtrip.flatbuffers"),
                "podbuf" to RoundTrip.source(selecting(requests, "podbuf"), podbuf = true, kotlinPackage = "ridl.conformance.roundtrip.podbuf"),
            )
            val compiled = Compiler.compile(sources + roundTrips.mapKeys { "roundtrip/${it.key}/RoundTrip.kt" }, work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
            val loader = compiled.classLoader!!
            // `pkg.Type label` to hex, per encoding.
            val samples = roundTrips.keys.associateWith { encoding ->
                @Suppress("UNCHECKED_CAST")
                val lines = loader.loadClass("ridl.conformance.roundtrip.$encoding.RoundTrip").getMethod("samples").invoke(null) as List<String>
                lines.associate { line -> line.substringBeforeLast(' ') to line.substringAfterLast(' ') }
            }

            val functions = functions(sources, loader)
            assertTrue(functions.isNotEmpty(), "$name generates no transcode function")
            val corpus = mutableListOf<String>()
            val kotlin = mutableListOf<String>()
            for (function in functions) {
                val from = samples.getValue(function.source).filterKeys { it.substringBefore(' ') == function.type }
                assertTrue(from.isNotEmpty(), "${function.type} has no ${function.source} sample")
                for ((key, hex) in from) {
                    val label = key.substringAfter(' ') + "/${function.source}-${function.target}"
                    fun line(suffix: String, capacity: Int, bytes: String) {
                        corpus += "${function.type} $label$suffix ${function.source} ${function.target} $capacity $bytes"
                        kotlin += verdict(function, bytes, capacity)
                    }
                    line("", CAPACITY, hex)
                    assertTrue(kotlin.last().startsWith("ok:"), "$key: ${function.source} to ${function.target}: ${kotlin.last()}")
                    val bytes = hex.chunked(2)
                    for (n in setOf(0, bytes.size / 2, bytes.size - 1)) line("/trunc$n", CAPACITY, bytes.take(n).joinToString(""))
                    for (i in bytes.indices) {
                        if (bytes[i] != "ff") line("/ones$i", CAPACITY, bytes.toMutableList().also { it[i] = "ff" }.joinToString(""))
                    }
                    val written = function.call(ByteBuffer.wrap(unhex(hex)), ByteBuffer.allocate(CAPACITY))
                    for (capacity in setOf(0, written / 2, written - 1)) line("/cap$capacity", capacity, hex)
                    line("/exact", written, hex)
                }
            }
            val out = Path.of(System.getProperty("spike.dir", work.toString())).resolve("$name-transcode-corpus.txt")
            Files.createDirectories(out.parent)
            Files.writeString(out, corpus.joinToString("\n", postfix = "\n"))

            for ((index, line) in corpus.withIndex()) {
                val label = line.split(' ')[1]
                when {
                    "/cap" in label -> assertTrue(kotlin[index].startsWith("err:Encode(Capacity {"), "$line: ${kotlin[index]}")
                    "/trunc0" in label -> assertTrue(kotlin[index].startsWith("err:Verify("), "$line: ${kotlin[index]}")
                    "/exact" in label -> assertTrue(kotlin[index].startsWith("ok:"), "$line: ${kotlin[index]}")
                }
            }

            val resource = javaClass.getResource("/transcode/$name-transcode-rust-verdicts.txt")
                ?: error("no resources/transcode/$name-transcode-rust-verdicts.txt: run `just rust-verdicts --write`")
            val rust = resource.readText().lines().filter { it.isNotBlank() }
            assertEquals(corpus.size, rust.size, "the Rust verdicts are for another corpus; regenerate them")
            val disagreements = corpus.indices.filter { kotlin[it] != rust[it] }
                .map { "${corpus[it].substringBeforeLast(' ')}\n  kotlin: ${kotlin[it]}\n  rust:   ${rust[it]}" }
            assertEquals(emptyList<String>(), disagreements.take(10), "${disagreements.size} disagreements")
            println(
                "$name: ${functions.size} transcode functions, ${corpus.size} lines, " +
                    "${kotlin.count { it.startsWith("err:Verify") }} refused on verify, ${kotlin.count { it.startsWith("err:Encode") }} on encode",
            )
        }
    }

    @TestFactory
    fun `with transcode absent or false, nothing transcodes`(): List<DynamicTest> = packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val requests = requests(name)
            for (value in listOf(null, "false")) {
                for (request in requests) {
                    val builder = request.toBuilder().clearOptions()
                    request.optionsList.filter { it.key != "transcode" }.forEach { builder.addOptions(it) }
                    if (value != null) builder.addOptions(Plugin.BackendOption.newBuilder().setKey("transcode").setValue(value))
                    val response = Generator.generate(builder.build())
                    assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
                    for (file in response.filesList) {
                        assertFalse(file.path.endsWith("/Transcode.kt"), file.path)
                        assertFalse("Transcode" in file.text || "transcode" in file.text, "${file.path} names a transcode function")
                    }
                }
            }
        }
    }

    private companion object {
        const val CAPACITY = 1 shl 16
    }

    /**
     * Rust puts `#[allow(deprecated)]` on each transcode `impl`. A deprecated
     * struct, and a struct with a deprecated field, must still give a
     * `Transcode.kt` that compiles with warnings as errors.
     */
    @Test
    fun `a deprecated type's transcode object compiles with warnings as errors`() {
        val pkg = work.resolve("src-deprecated").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"probe.deprecated\"\nversion = \"1.0.0\"\n\n" +
                "[backend.kotlin]\nencodings = [\"flatbuffers\", \"podbuf\"]\ntranscode = true\n",
        )
        pkg.resolve("deprecated.ridl").writeText(
            """
            package probe.deprecated

            /// @deprecated "use Fresh"
            struct Stale {
              level : integer [0..9]
            }

            struct Fresh {
              /// @deprecated "no longer read"
              legacy : integer [1..9] = 5
              level : integer [0..9]
            }
            """.trimIndent(),
        )
        val request = Harness.capturedRequests(pkg, "deprecated", work).values.single().let(Wire::readRequest)
        val response = Generator.generate(request)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val sources = response.filesList.associate { it.path to it.text }
        assertTrue(sources.keys.any { it.endsWith("/Transcode.kt") }, sources.keys.toString())
        val compiled = Compiler.compile(sources, work.resolve("compile-deprecated"))
        assertTrue(compiled.ok, compiled.messages)
    }
}
