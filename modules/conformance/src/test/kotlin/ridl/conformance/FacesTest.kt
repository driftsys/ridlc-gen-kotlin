package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path

/**
 * docs/design.md §7, "The face round-trips on the loopback": the generated
 * faces of the corpus packages with interfaces, driven over
 * ridl-rt-kt-loopback by the hand-written probes of `resources/faces/`.
 */
class FacesTest {
    @TempDir
    lateinit var work: Path

    private val probes = mapOf(
        "cabin" to "ridl.conformance.probe.faces.cabin.CabinFaceProbe",
        "kt-values" to "ridl.conformance.probe.faces.values.ValuesFaceProbe",
    )

    @TestFactory
    fun `the face round-trips on the loopback`(): List<DynamicTest> = probes.map { (name, facade) ->
        DynamicTest.dynamicTest(name) {
            val requests = Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)
            val sources = requests.flatMap { r ->
                val response = Generator.generate(r)
                assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
                response.filesList.map { it.path to it.text }
            }.toMap()
            val probe = checkNotNull(javaClass.getResource("/faces/$name.kt")).readText()
            val compiled = Compiler.compile(sources + ("faces/$name.kt" to probe), work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
            @Suppress("UNCHECKED_CAST")
            val failures = compiled.classLoader!!.loadClass(facade).getMethod("probe").invoke(null) as List<String>
            assertEquals(emptyList<String>(), failures)
        }
    }
}
