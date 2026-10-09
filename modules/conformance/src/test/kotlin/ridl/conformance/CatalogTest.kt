package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path

/**
 * #48: every generated descriptor carries the catalog of the model the pinned
 * `ridl` hands the plugin, name and hash, and never a hash of 32 zero bytes.
 * The faces tests attach their ports to the generated `<Iface>.catalog`, so
 * they pass with any hash; this test compares the hash itself. The Rust
 * backend reads the same `Catalog` from the same model byte for byte, so the
 * model is the reference, and no ridl formatting is read (#65).
 */
class CatalogTest {
    @TempDir
    lateinit var work: Path

    /** A catalog: its name and its 32 hash bytes, unsigned. */
    private data class Catalog(val name: String, val hash: List<Int>)

    private val literal = Regex("""CatalogRef\(\s*"([^"]*)",\s*CatalogHash\(\s*byteArrayOf\(([^)]*)\)\s*\)\s*\)""")

    private fun bytes(list: String): List<Int> =
        list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() and 0xff }

    /**
     * For each package the build of corpus package [name] hands the plugin:
     * the model's catalog, and the catalogs its generated `Faces.kt` names, or
     * none without one.
     */
    private fun catalogs(name: String): Map<String, Pair<Catalog, Set<Catalog>>> =
        Harness.capturedRequests(name, work.resolve(name)).values.map(Wire::readRequest).associate { request ->
            val model = request.model
            val response = Generator.generate(request)
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message }, model.name.dotted)
            val faces = response.filesList.singleOrNull { it.path.endsWith("/Faces.kt") }?.text.orEmpty()
            val generated = literal.findAll(faces).map { Catalog(it.groupValues[1], bytes(it.groupValues[2])) }.toSet()
            val expected = Catalog(model.catalog.`package`, model.catalog.hash.toByteArray().map { it.toInt() and 0xff })
            model.name.dotted to (expected to generated)
        }

    @TestFactory
    fun `every descriptor carries the model's catalog`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            for ((pkg, catalogs) in catalogs(name)) {
                val (expected, generated) = catalogs
                if (generated.isEmpty()) continue
                assertEquals(setOf(expected), generated, pkg)
                assertEquals(32, expected.hash.size, "$pkg: ${expected.hash}")
                assertTrue(expected.hash.any { it != 0 }, "$pkg: the catalog hash is 32 zero bytes")
            }
        }
    }

    @Test
    fun `the cabin catalog is read from its descriptors`() {
        // So the pattern cannot match nothing and pass.
        val (expected, generated) = catalogs("cabin").getValue("veh.cabin")
        assertEquals("veh.cabin", expected.name)
        assertEquals(setOf(expected), generated)
    }
}
