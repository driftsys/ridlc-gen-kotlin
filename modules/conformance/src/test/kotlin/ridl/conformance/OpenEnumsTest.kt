package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path

/**
 * An open enum and an open enum set (typl §8.1, §9, driftsys/ridl#1174, #1177,
 * #1178), over the `open-enums` corpus package, by the hand-written probe of
 * `resources/open/`. `CodecTest` holds the package to Rust's verdicts.
 */
class OpenEnumsTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `an open declaration keeps what it does not name`() {
        val requests = Harness.capturedRequests("open-enums", work.resolve("capture")).values.map(Wire::readRequest)
        val sources = requests.flatMap { request ->
            val response = Generator.generate(request)
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val probe = checkNotNull(javaClass.getResource("/open/open-enums.kt")).readText()
        val compiled = Compiler.compile(sources + ("open/open-enums.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("ridl.conformance.probe.open.OpenEnumsProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
