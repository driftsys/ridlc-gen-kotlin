package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * #48: every generated descriptor carries the catalog the Rust backend of the
 * pinned release writes, name and hash, and never a hash of 32 zero bytes. The
 * faces tests attach their ports to the generated `<Iface>.catalog`, so they
 * pass with any hash; this test compares the hash itself. One `ridl build`
 * writes both the Rust crate and the plugin's files, and each side's
 * `CatalogRef` literals are read from the source text.
 */
class CatalogTest {
    @TempDir
    lateinit var work: Path

    /** A catalog: its name and its 32 hash bytes, unsigned. */
    private data class Catalog(val name: String, val hash: List<Int>)

    private val rust = Regex(
        """CatalogRef \{\s*name: "([^"]+)",\s*hash: ::ridl_rt::contract::CatalogHash\(\[([^\]]*)]\)""",
    )
    private val kotlin = Regex("""CatalogRef\(\s*"([^"]+)",\s*CatalogHash\(\s*byteArrayOf\(([^)]*)\)\s*\)\s*\)""")

    private fun bytes(list: String): List<Int> =
        list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() and 0xff }

    /** Every catalog the files whose path ends with [suffix] name, by [pattern]. */
    private fun catalogs(files: Map<String, ByteArray>, suffix: String, pattern: Regex): Set<Catalog> =
        files.filterKeys { it.endsWith(suffix) }.values
            .flatMap { pattern.findAll(it.decodeToString()).toList() }
            .map { Catalog(it.groupValues[1], bytes(it.groupValues[2])) }
            .toSet()

    /** The Rust and Kotlin catalogs of one corpus package's build. */
    private fun build(name: String): Pair<Set<Catalog>, Set<Catalog>> {
        val work = work.resolve(name)
        val out = Harness.build(
            Harness.copyOf(name, work),
            work.resolve("out"),
            "--emit",
            "rust",
            "--plugin",
            "kotlin=${Harness.plugin}",
        )
        val files = Harness.files(out)
        return catalogs(files, ".rs", rust) to catalogs(files, ".kt", kotlin)
    }

    @TestFactory
    fun `every descriptor carries the Rust backend's catalog`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val (rust, kotlin) = build(name)
            assertEquals(rust, kotlin)
            for (catalog in kotlin) {
                assertEquals(32, catalog.hash.size, "${catalog.name}: ${catalog.hash}")
                assertTrue(catalog.hash.any { it != 0 }, "${catalog.name}: the catalog hash is 32 zero bytes")
            }
        }
    }

    @Test
    fun `the cabin catalog is read from both sides`() {
        // So the regular expressions cannot both match nothing and pass.
        val (rust, kotlin) = build("cabin")
        assertEquals(listOf("veh.cabin"), rust.map { it.name })
        assertEquals(rust, kotlin)
    }
}
