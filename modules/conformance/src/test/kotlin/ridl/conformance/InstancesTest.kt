package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * #137: the instance constants of a system. The pinned `ridl` builds the
 * system of its own corpus entry `instance-numbers`, writes the Rust
 * `instances` module, and hands the plugin each package's request; the Kotlin
 * constants compile and carry the Rust module's values, path for path.
 *
 * The plugin reads the numbers from the deployment section, which the Rust
 * module does not need. A deployment places every instance of the system
 * (RSDL-701), so whichever one is selected the constants are the same; with
 * none selected there is no section, and no Kotlin file, where the Rust
 * module is still written.
 */
class InstancesTest {
    @TempDir
    lateinit var work: Path

    private val systems: Path = Path.of(System.getProperty("systems.dir"))

    /** A fresh copy of the system fixture, so a test may change it. */
    private fun fixture(): Path {
        val source = systems.resolve("instance-numbers")
        val target = work.resolve("src-instance-numbers")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val dest = target.resolve(path.relativeTo(source).toString())
                if (Files.isDirectory(path)) dest.createDirectories() else path.copyTo(dest)
            }
        }
        return target
    }

    /** Every generated file of every request, by path; each request's diagnostics must be none. */
    private fun generated(root: Path, vararg args: String): Map<String, String> =
        Harness.capturedRequests(root, "instances", work, "--emit", "rust", *args).values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()

    /** The constants of the Rust module `rust`, by dotted module path and name. */
    private fun rustConstants(rust: String): Map<String, Int> {
        val path = ArrayDeque<String>()
        val constants = sortedMapOf<String, Int>()
        val module = Regex("""^\s*pub mod (\w+) \{$""")
        val constant = Regex("""^\s*pub const (\w+): i32 = (-?\d+);$""")
        for (line in rust.lines()) {
            module.find(line)?.let { path.addLast(it.groupValues[1]) }
            constant.find(line)?.let { constants[(path + it.groupValues[1]).joinToString(".")] = it.groupValues[2].toInt() }
            if (line.trim() == "}") path.removeLastOrNull()
        }
        return constants
    }

    /** The constants nested in the compiled object [cls], by dotted object path and name. */
    private fun kotlinConstants(cls: Class<*>, prefix: List<String> = emptyList()): Map<String, Int> =
        cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { (prefix + it.name).joinToString(".") to it.getInt(null) } +
            cls.declaredClasses.flatMap { kotlinConstants(it, prefix + it.simpleName).entries }.associate { it.toPair() }

    @Test
    fun `the constants compile and are the Rust module's`() {
        val root = fixture()
        val sources = generated(root)
        val rust = rustConstants(work.resolve("capture-out-instances").resolve("__ridl_instances.rs").readText())
        assertEquals(mapOf("veh.adas.cruise.BACKUP" to 2, "veh.adas.cruise.PRIMARY" to 1, "veh.ego.status.UNIT" to 3), rust)

        // Every package's request carries the section, so each package has the constants in its own Kotlin package.
        assertTrue("veh/adas/Instances.kt" in sources && "veh/ego/Instances.kt" in sources, sources.keys.toString())
        val compiled = Compiler.compile(sources, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        for (pkg in listOf("veh.adas", "veh.ego")) {
            assertEquals(rust, kotlinConstants(compiled.classLoader!!.loadClass("$pkg.Instances_")).toSortedMap(), pkg)
        }
    }

    @Test
    fun `with no deployment selected there is no file`() {
        val root = fixture()
        // With two deployments and no `--deployment`, ridl selects none and sends no section.
        root.resolve("adas/car.rsdl").appendText(SECOND_DEPLOYMENT)
        val sources = generated(root)
        assertEquals(emptyList<String>(), sources.keys.filter { it.endsWith("/Instances.kt") })
        // The Rust module is written from the system, deployment or none.
        assertTrue(Files.exists(work.resolve("capture-out-instances").resolve("__ridl_instances.rs")))
    }

    @Test
    fun `another deployment selected gives the same constants`() {
        val root = fixture()
        root.resolve("adas/car.rsdl").appendText(SECOND_DEPLOYMENT)
        val sources = generated(root, "--deployment", "Split")
        val rust = rustConstants(work.resolve("capture-out-instances").resolve("__ridl_instances.rs").readText())
        val compiled = Compiler.compile(sources, work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        assertEquals(rust, kotlinConstants(compiled.classLoader!!.loadClass("veh.adas.Instances_")).toSortedMap())
    }

    private companion object {
        /** A second deployment of the fixture's system, over two machines. */
        const val SECOND_DEPLOYMENT: String =
            "\ndeployment Split for Car {\n  machine Front { Cruise }\n  machine Rear { veh.ego.status }\n}\n"
    }

    @Test
    fun `a package built without a system generates none`() {
        val requests = Harness.capturedRequests("cabin", work).values.map(Wire::readRequest)
        assertTrue(requests.none { it.hasDeployment() })
        val paths = requests.flatMap { request -> Generator.generate(request).filesList.map { it.path } }
        assertEquals(emptyList<String>(), paths.filter { it.endsWith("/Instances.kt") })
    }
}
