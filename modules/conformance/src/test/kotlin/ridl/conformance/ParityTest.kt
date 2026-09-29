package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.v1.Plugin
import java.nio.file.Path

/**
 * docs/design.md §7, "The plugin is the same plugin through the host": the
 * parity test of `codegen-plugins.md` §6, run from outside the ridl
 * repository against the pinned release.
 *
 * For every corpus package, `ridl build --plugin kotlin=<script>` writes
 * exactly the files of the same build without the plugin (the default emit)
 * plus the files of the responses the script gives when invoked directly on
 * the requests that build hands it, byte for byte.
 */
class ParityTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `the plugin through ridl writes what the plugin answers`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            // The dynamic tests of one factory share its temporary directory.
            val work = work.resolve(name)
            val answered = Harness.capturedRequests(name, work).values.flatMap { request ->
                val direct = Harness.plugin(request)
                assertEquals(0, direct.exit, direct.stderr)
                val response = Plugin.CodegenResponse.newBuilder().also {
                    com.google.protobuf.util.JsonFormat.parser().merge(direct.stdout, it)
                }.build()
                assertTrue(response.diagnosticsList.isEmpty(), response.diagnosticsList.toString())
                response.filesList.map { file ->
                    file.path to if (file.hasText()) file.text.toByteArray() else file.binary.toByteArray()
                }
            }.toMap()

            val baseline = Harness.files(Harness.build(Harness.copyOf(name, work.resolve("a")), work.resolve("out-a")))
            val hosted = Harness.files(
                Harness.build(
                    Harness.copyOf(name, work.resolve("b")),
                    work.resolve("out-b"),
                    "--plugin",
                    "kotlin=${Harness.plugin}",
                ),
            )
            assertTrue(answered.keys.none { it in baseline }, "the plugin writes no path the default emit writes")
            val expected = baseline + answered
            assertEquals(expected.keys, hosted.keys)
            for ((path, bytes) in expected) {
                assertTrue(bytes.contentEquals(hosted.getValue(path)), "$path differs through the host")
            }
        }
    }
}
