package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Model
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * docs/design.md §7 for `Types.kt`: for every corpus package, what the plugin
 * generates from the requests the pinned `ridl` writes compiles against
 * `ridl-rt-kt` with warnings as errors, and its value objects refuse what
 * the model's constraints forbid.
 */
class TypesTest {
    @TempDir
    lateinit var work: Path

    private class Generated(val models: List<Model>, val sources: Map<String, String>)

    /** Every package the build of [name] hands the plugin, generated in process. */
    private fun generate(name: String, facesOnly: Boolean = false): Generated {
        val requests = Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)
            .map { if (facesOnly) Harness.faceEncodings(it) else it }
        val sources = requests.flatMap { request ->
            val response = Generator.generate(request)
            assertEquals(Harness.proto3FaceWarnings(request), response.diagnosticsList, request.model.name.dotted)
            response.filesList.map { it.path to it.text }
        }.toMap()
        return Generated(requests.map { it.model }, sources)
    }

    @TestFactory
    fun `the generated Types kt, Codec kt and Faces kt compile`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val generated = generate(name)
            // A codec file per encoding the unit's manifest selects: `Codec.kt` for FlatBuffers, `Proto3Codec.kt` for
            // proto3, `PodbufCodec.kt` for podbuf.
            val encodings = Harness.encodings(name)
            val codecs = listOfNotNull(
                "Codec.kt".takeIf { "flatbuffers" in encodings },
                "Proto3Codec.kt".takeIf { "proto3" in encodings },
                "PodbufCodec.kt".takeIf { "podbuf" in encodings },
            )
            for (model in generated.models) {
                val dir = model.name.dotted.replace('.', '/')
                val expected = setOf("Types.kt") + codecs + if (model.interfacesCount > 0 && "proto3" !in encodings) setOf("Faces.kt") else emptySet()
                val kinds = generated.sources.keys.filter { it.substringBeforeLast('/') == dir }.map { it.substringAfterLast('/') }.toSet()
                assertEquals(expected, kinds, "${model.name.dotted}: ${generated.sources.keys}")
            }
            val compiled = Compiler.compile(generated.sources, work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
        }
    }

    @Test
    fun `kt-values faces compile without proto3`() {
        val generated = generate("kt-values", facesOnly = true)
        assertTrue("kt/values/Faces.kt" in generated.sources, "kt-values faces must be compiled")
        val compiled = Compiler.compile(generated.sources, work.resolve("compile-kt-values-faces"))
        assertTrue(compiled.ok, compiled.messages)
    }

    /**
     * A deprecated declaration, field or enum value is still encoded, as Rust
     * does under `#[allow(deprecated)]`: every generated file that names one
     * compiles with warnings as errors, in each encoding and in the face.
     */
    @Test
    fun `deprecated declarations and fields compile with warnings as errors`() {
        val pkg = work.resolve("src-deprecated").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"probe.deprecated\"\nversion = \"1.0.0\"\n\n" +
                "[backend.kotlin]\nencodings = [\"flatbuffers\", \"podbuf\"]\n",
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

            interface Pump {
              command push(stale: Stale) @[..50ms]
              command refresh(fresh: Fresh) @[..50ms]
            }
            """.trimIndent(),
        )
        val request = Harness.capturedRequests(pkg, "deprecated", work).values.single().let(Wire::readRequest)
        val response = Generator.generate(request)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile-deprecated"))
        assertTrue(compiled.ok, compiled.messages)
    }

    /**
     * #51, #86: ridl strips the doc tags `@see` and `@since` from the doc
     * (driftsys/ridl#703) and carries them as fields of the model, with the
     * rules (ADR-0028), and the plugin renders each once, after the doc text,
     * as KDoc does. `kt-values` documents `Offset` with both tags and a
     * range and a step.
     */
    @Test
    fun `the doc tags and the rules are rendered once, after the doc text`() {
        val types = generate("kt-values").sources.getValue("kt/values/Types.kt")
        val offset = """
            |/**
            | * An offset, in quarter steps. Its doc tags are the model's, not the text's:
            | * ridl strips them from the doc (#51).
            | *
            | * Rules:
            | * - range -5 ..= 5
            | * - step 0.25
            | *
            | * @see kt.values.Even
            | * @since 1.0
            | */
            |@JvmInline
            |public value class Offset private constructor(
        """.trimMargin()
        assertTrue(offset in types, types)
        assertEquals(1, Regex("@see ").findAll(types).count(), "the tag is rendered once, not left in the text")
    }

    @TestFactory
    fun `the value objects refuse invalid values`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val generated = generate(name)
            val probes = mutableMapOf("probe/Probe.kt" to Probe.source(generated.models))
            val own = javaClass.getResource("/probes/$name.kt")
            if (own != null) probes["probe/$name.kt"] = own.readText()
            val compiled = Compiler.compile(generated.sources + probes, work.resolve("probe-$name"))
            assertTrue(compiled.ok, compiled.messages)
            val failures = mutableListOf<String>()
            failures += run(compiled.classLoader!!, "ridl.conformance.probe.Probe")
            if (own != null) failures += run(compiled.classLoader, "ridl.conformance.probe.values.ValuesProbe")
            assertEquals(emptyList<String>(), failures)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(loader: ClassLoader, className: String): List<String> =
        loader.loadClass(className).getMethod("probe").invoke(null) as List<String>
}
