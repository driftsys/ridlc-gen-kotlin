package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path

/**
 * The spec's §8: the clients and `serve` generated for the cabin package,
 * driven over ridl-rt-kt-loopback by the probe of `resources/clients/cabin.kt`.
 */
class ClientsTest {
    @TempDir
    lateinit var work: Path

    private fun sources(): Map<String, String> =
        Harness.capturedRequests("cabin", work.resolve("capture")).values.map(Wire::readRequest).flatMap { r ->
            Generator.generate(r).filesList.map { it.path to it.text }
        }.toMap()

    @Test
    fun `the clients round-trip on the loopback`() {
        val probe = checkNotNull(javaClass.getResource("/clients/cabin.kt")).readText()
        val compiled = Compiler.compile(sources() + ("clients/cabin.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("ridl.conformance.probe.clients.cabin.CabinClientsProbe")
            .getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
