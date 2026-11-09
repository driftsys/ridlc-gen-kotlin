package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The encodings a package selects (docs/design.md §4). The generated text of
 * every corpus package with `encodings` forced to `flatbuffers` is pinned by
 * `resources/flatbuffers/output-digests.txt`: one line
 * `<package> <path> <sha256>` per generated file. Run with `WRITE_DIGESTS=1`
 * to write the file instead of comparing with it.
 */
class SelectionTest {
    @TempDir
    lateinit var work: Path

    /** The output of every package a corpus package reaches, by package then path. */
    private fun flatBuffersOutputs(name: String): Map<String, Map<String, String>> =
        Harness.capturedRequests(name, work).mapValues { (_, request) -> flatBuffersOf(request) }

    private fun flatBuffersOf(json: String): Map<String, String> {
        val original = Wire.readRequest(json)
        val builder = original.toBuilder().clearOptions()
        for (option in original.optionsList) {
            if (option.key != "encodings") builder.addOptions(option)
        }
        builder.addOptions(Plugin.BackendOption.newBuilder().setKey("encodings").setValue("flatbuffers"))
        val response = Generator.generate(builder.build())
        check(response.diagnosticsList.none { it.severity == Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR }) {
            response.diagnosticsList.toString()
        }
        return response.filesList.associate { it.path to it.text }.toSortedMap()
    }

    /** The output of the corpus package [name] itself, path to text. */
    fun flatBuffersOutput(name: String): Map<String, String> =
        flatBuffersOf(Harness.capturedRequest(name, work))

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val digestFile: Path = Path.of("src/test/resources/flatbuffers/output-digests.txt")

    @TestFactory
    fun `the flatbuffers-only output of every corpus package is unchanged`(): List<DynamicTest> {
        val actual = Harness.packages().flatMap { name ->
            flatBuffersOutputs(name).flatMap { (pkg, files) -> files.map { (path, text) -> "$pkg $path ${sha256(text)}" } }
        }.distinct().sorted()
        if (System.getenv("WRITE_DIGESTS") == "1") {
            Files.createDirectories(digestFile.parent)
            Files.writeString(digestFile, actual.joinToString("\n", postfix = "\n"))
        }
        val expected = Files.readAllLines(digestFile).filter { it.isNotBlank() }
        val byPackage = { lines: List<String> -> lines.groupBy { it.substringBefore(' ') } }
        val want = byPackage(expected)
        val got = byPackage(actual)
        return (want.keys + got.keys).sorted().map { pkg ->
            DynamicTest.dynamicTest(pkg) {
                assertEquals(want[pkg].orEmpty(), got[pkg].orEmpty())
            }
        }
    }

    @TestFactory
    fun `flatbuffers-only output names no podbuf`(): List<DynamicTest> =
        Harness.packages().map { name ->
            DynamicTest.dynamicTest(name) {
                // The corpus package `kt-podbuf` is named so: its own name is not a podbuf item.
                val own = Harness.manifestName(name)
                for ((pkg, files) in flatBuffersOutputs(name)) {
                    for ((path, text) in files) {
                        assertFalse(text.replace(own, "").contains("podbuf", ignoreCase = true), "$pkg $path names podbuf")
                    }
                }
            }
        }
}
