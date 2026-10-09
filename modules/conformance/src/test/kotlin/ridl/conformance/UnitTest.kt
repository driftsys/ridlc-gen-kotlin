package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.Interface
import ridl.rt.contract.InterfaceNo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeTo
import kotlin.io.path.toPath

/**
 * Since ridl 0.7.0 a catalog is one unit, the source packages under one
 * `ridl.toml` (#55): `resources/units/kt-unit` holds `kt.unit` and its
 * subpackage `kt.unit.cluster`. Each source package is generated on its own,
 * and the descriptors of both name the unit's one catalog, with the numbers of
 * the unit's lock. The unit is kept out of `corpus/`, whose every package needs
 * Rust codec verdicts.
 */
class UnitTest {
    @TempDir
    lateinit var work: Path

    /** A copy of the unit, so a build never writes into the build's resources. */
    private fun unit(): Path {
        val source = checkNotNull(javaClass.getResource("/units/kt-unit")).toURI().toPath()
        val target = work.resolve("src-kt-unit")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val dest = target.resolve(path.relativeTo(source).toString())
                if (Files.isDirectory(path)) dest.createDirectories() else path.copyTo(dest)
            }
        }
        return target
    }

    @Test
    fun `the packages of one unit share its catalog and its numbers`() {
        val requests = Harness.capturedRequests(unit(), "kt-unit", work).mapValues { Wire.readRequest(it.value) }
        assertEquals(listOf("kt.unit", "kt.unit.cluster"), requests.keys.toList())
        assertEquals(listOf("kt.unit", "kt.unit"), requests.values.map { it.model.catalog.`package` })
        val hash = requests.values.map { it.model.catalog.hash }.distinct().single()
        assertNotEquals(32, hash.toByteArray().count { it == 0.toByte() }, "the unit's catalog hash is all zero")

        val sources = requests.values.flatMap { request ->
            val response = Generator.generate(request)
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        assertTrue("kt/unit/cluster/Faces.kt" in sources, sources.keys.toString())
        val compiled = Compiler.compile(sources, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)

        fun descriptor(name: String) = compiled.classLoader!!.loadClass(name).getField("INSTANCE").get(null) as Interface
        val catalog = CatalogRef("kt.unit", CatalogHash(hash.toByteArray()))
        val climate = descriptor("kt.unit.Climate")
        val speed = descriptor("kt.unit.cluster.SpeedDisplay")
        assertEquals(catalog, climate.catalog)
        assertEquals(catalog, speed.catalog)
        // `interfaces.lock`: `Climate 1`, `cluster.SpeedDisplay 2`. The face keeps the short name.
        assertEquals(InterfaceNo(1u), climate.number)
        assertEquals(InterfaceNo(2u), speed.number)
        assertEquals("SpeedDisplay", speed.name)
    }
}
