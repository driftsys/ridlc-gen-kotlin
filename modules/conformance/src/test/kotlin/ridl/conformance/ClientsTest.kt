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

    @Test
    fun `a signal-only interface has a client, an async client for its states, and no poll face or serve`() {
        val faces = sources().getValue("veh/cabin/Faces.kt")
        assertTrue("public class HornClient<" in faces, "Horn keeps its public client")
        // #76: each signal's state extends the suspending client.
        assertTrue("public class HornAsyncClient<P : SignalReader>(" in faces, "Horn has an async client over a SignalReader alone")
        assertTrue("HornAsyncClient<P>.active: SignalState<Health>" in faces, "Horn's signal is a state")
        assertFalse("HornPollClient" in faces, "Horn has no poll face")
        val horn = faces.substringAfter("public object Horn : Interface {").substringBefore("\n}\n")
        assertFalse("fun <H> serve(" in horn, "Horn has no serve")
    }
}
