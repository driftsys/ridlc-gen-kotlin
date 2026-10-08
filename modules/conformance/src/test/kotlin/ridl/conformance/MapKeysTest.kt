package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * #38 beyond the corpus: `payload-constraints` has one map per struct, keyed
 * by a float carried as f64 and by bytes. This package adds a struct with two
 * maps whose keys need the check, and a map keyed by an inline float carried
 * as f32, built through the pinned `ridl` so the model is the release's.
 */
class MapKeysTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `every map a constructor accepts is one verify accepts`() {
        val pkg = work.resolve("kt-keys").createDirectories()
        pkg.resolve("ridl.toml").writeText("[package]\nname = \"kt.keys\"\nversion = \"1.0.0\"\n")
        pkg.resolve("keys.ridl").writeText(
            """
            package kt.keys

            type Whole : integer [0..9]

            struct TwoMaps {
              floats : [float : Whole; 0..3]
              blobs : [bytes : Whole; 0..3]
            }

            struct NarrowKeys { entries : [float [0.0..2.0 step 0.25] : Whole; 0..3] }
            """.trimIndent() + "\n",
        )
        val request = Wire.readRequest(Harness.capturedRequests(pkg, "kt-keys", work).getValue("kt.keys"))
        val response = Generator.generate(request)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val probe = checkNotNull(javaClass.getResource("/keys/collisions.kt")).readText()
        val sources = response.filesList.associate { it.path to it.text } + ("keys/collisions.kt" to probe)
        val compiled = Compiler.compile(sources, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("ridl.conformance.probe.keys.KeyCollisionsProbe")
            .getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
