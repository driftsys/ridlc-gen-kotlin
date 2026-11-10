package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.snakeCase
import ridl.codegen.v1.DeploymentOuterClass
import ridl.codegen.v1.ModelOuterClass
import ridl.codegen.v1.Plugin

/**
 * #137: the instance constants of a system, read from the request's
 * deployment section, under the Rust backend's `instances` module path
 * (`crates/ridl-backend-rust/src/instances.rs` at the pinned release).
 */
class InstancesEmitterTest {
    /** One placed instance: its component, its instance name, its number, and whether the number is provisional. */
    private data class Placed(val component: String, val instance: String, val number: Int, val provisional: Boolean = false)

    private fun request(deployment: List<Placed>?, vararg options: Pair<String, String>): Plugin.CodegenRequest =
        Plugin.CodegenRequest.newBuilder()
            .setSchema(SCHEMA)
            .setModel(
                ModelOuterClass.Model.newBuilder().setName(ModelOuterClass.DottedName.newBuilder().setDotted("veh.adas"))
                    .setCatalog(ModelOuterClass.Catalog.newBuilder().setHash(com.google.protobuf.ByteString.copyFrom(ByteArray(32)))),
            )
            .addAllOptions(options.map { (k, v) -> Plugin.BackendOption.newBuilder().setKey(k).setValue(v).build() })
            .apply {
                if (deployment != null) {
                    setDeployment(
                        DeploymentOuterClass.Deployment.newBuilder().setSystem("veh.adas.Car").setName("Bench").addAllInstances(
                            deployment.map {
                                DeploymentOuterClass.Instance.newBuilder().setComponent(it.component).setInstance(it.instance)
                                    .setMachine("Box").setNumber(it.number).setProvisional(it.provisional).build()
                            },
                        ),
                    )
                }
            }
            .build()

    private fun generated(deployment: List<Placed>?, vararg options: Pair<String, String>): Map<String, String> {
        val response = Generator.generate(request(deployment, *options))
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        return response.filesList.associate { it.path to it.text }
    }

    @Test
    fun `snake case is ridl's`() {
        // `ridl_ir::name::snake_case` at the pinned release.
        val cases = mapOf(
            "Cruise" to "cruise", "LaneKeep" to "lane_keep", "HTTPServer" to "http_server", "primaryUnit" to "primary_unit",
            "parseHTTPResponse" to "parse_http_response", "Unit" to "unit", "v2Mode" to "v2_mode", "2nd" to "2nd",
            "already_snake" to "already_snake", "ABC" to "abc",
        )
        for ((name, snake) in cases) assertEquals(snake, snakeCase(name), name)
    }

    @Test
    fun `every numbered instance is a constant under its component path`() {
        val text = generated(
            listOf(
                Placed("veh.adas.Cruise", "primary", 1),
                Placed("veh.adas.Cruise", "backup", 2),
                Placed("veh.ego.status", "Unit", 3, provisional = true),
            ),
        ).getValue("veh/adas/Instances.kt")
        assertTrue("object Instances_" in text, text)
        assertTrue("object veh" in text, text)
        assertTrue("object cruise" in text, text)
        assertTrue("object status" in text, text)
        assertTrue("const val PRIMARY: Int = 1" in text, text)
        assertTrue("const val BACKUP: Int = 2" in text, text)
        assertTrue("const val UNIT: Int = 3" in text, text)
        assertTrue("`veh.adas.Cruise/primary` in `instances.lock`." in text, text)
        assertEquals(1, Regex("Provisional:").findAll(text).count(), text)
        // Sorted by name, as the Rust module is: BACKUP before PRIMARY.
        assertTrue(text.indexOf("BACKUP") < text.indexOf("PRIMARY"), text)
    }

    @Test
    fun `the file is generated with and without an encoding, in the kotlin-package`() {
        val placed = listOf(Placed("veh.adas.Cruise", "primary", 1))
        assertTrue("veh/adas/Instances.kt" in generated(placed).keys)
        assertTrue("com/example/Instances.kt" in generated(placed, "kotlin-package" to "com.example", "encodings" to "flatbuffers").keys)
    }

    @Test
    fun `no deployment section, or no numbered instance, generates no file`() {
        assertEquals(listOf("veh/adas/Types.kt"), generated(null).keys.toList())
        assertEquals(listOf("veh/adas/Types.kt"), generated(emptyList()).keys.toList())
        // `0`: the system IR carries no number for the placement, and the Rust module has no constant for it.
        assertEquals(listOf("veh/adas/Types.kt"), generated(listOf(Placed("veh.adas.Cruise", "primary", 0, true))).keys.toList())
    }

    @Test
    fun `a component named like its parent keeps both segments`() {
        val text = generated(listOf(Placed("veh.adas.Adas", "Unit", 1), Placed("veh.adas.Cruise", "Unit", 2)))
            .getValue("veh/adas/Instances.kt")
        assertEquals(2, Regex("""object adas \{""").findAll(text).count(), text)
    }

    @Test
    fun `two instances mapping to one constant are refused, naming both`() {
        val response = Generator.generate(
            request(listOf(Placed("veh.adas.LaneKeep", "Unit", 1), Placed("veh.adas.lane_keep", "Unit", 2))),
        )
        assertEquals(emptyList<Plugin.GeneratedFile>(), response.filesList)
        val message = response.diagnosticsList.single().message
        assertTrue("veh.adas.LaneKeep/Unit" in message, message)
        assertTrue("veh.adas.lane_keep/Unit" in message, message)
    }

    @Test
    fun `a name Kotlin cannot write bare is escaped`() {
        val text = generated(listOf(Placed("object.in.Is", "2nd", 1))).getValue("veh/adas/Instances.kt")
        assertTrue("object `object`" in text, text)
        assertTrue("object `in`" in text, text)
        assertTrue("object `is`" in text, text)
        assertTrue("const val `2ND`: Int = 1" in text, text)
    }
}
