package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.v1.ModelOuterClass
import ridl.codegen.v1.Plugin

/** docs/design.md §2, step 3: the two known keys, `encodings` read as the Rust backend reads it, and the refusal of every other. */
class OptionsTest {
    private fun request(vararg options: Pair<String, String>): Plugin.CodegenRequest =
        Plugin.CodegenRequest.newBuilder()
            .setSchema(SCHEMA)
            .setModel(
                ModelOuterClass.Model.newBuilder().setName(ModelOuterClass.DottedName.newBuilder().setDotted("veh.cabin"))
                    .setCatalog(ModelOuterClass.Catalog.newBuilder().setHash(com.google.protobuf.ByteString.copyFrom(ByteArray(32)))),
            )
            .addAllOptions(options.map { (k, v) -> Plugin.BackendOption.newBuilder().setKey(k).setValue(v).build() })
            .build()

    private fun refused(parsed: Options.Parsed): List<String> = (parsed as Options.Parsed.Refused).messages

    @Test
    fun `no option means the package's dotted name and no encoding`() {
        assertEquals(Options.Parsed.Ok(Options("veh.cabin", emptyList())), Options.parse(request()))
    }

    @Test
    fun `with no encoding the package is its types alone, and with flatbuffers its codec too`() {
        // #83: the family's rule, as the Rust backend applies it since ridl 0.9 (driftsys/ridl#851).
        val bare = Generator.generate(request()).filesList.map { it.path }
        assertEquals(listOf("veh/cabin/Types.kt"), bare)
        val coded = Generator.generate(request("encodings" to "flatbuffers")).filesList.map { it.path }
        assertEquals(listOf("veh/cabin/Types.kt", "veh/cabin/Codec.kt"), coded)
    }

    @Test
    fun `kotlin-package and encodings are read`() {
        assertEquals(
            Options.Parsed.Ok(Options("com.example.cabin", listOf(WireEncoding.FlatBuffers))),
            Options.parse(request("kotlin-package" to "com.example.cabin", "encodings" to "flatbuffers")),
        )
    }

    @Test
    fun `wire-encoding is no longer a key`() {
        assertTrue("`wire-encoding`" in refused(Options.parse(request("wire-encoding" to "flatbuffers")))[0])
    }

    @Test
    fun `an unknown key is one error naming it`() {
        val messages = refused(Options.parse(request("colour" to "blue")))
        assertEquals(1, messages.size)
        assertTrue("`colour`" in messages[0], messages[0])
    }

    @Test
    fun `an encoding with no Kotlin codec is refused by name`() {
        for (name in listOf("proto3", "repr-c")) {
            assertTrue("no `$name` codec" in refused(Options.parse(request("encodings" to name)))[0])
        }
        assertTrue("no `proto3` codec" in refused(Options.parse(request("encodings" to "flatbuffers,proto3")))[0])
    }

    @Test
    fun `a name that is no encoding is refused`() {
        assertTrue("`cbor`, which is not an encoding" in refused(Options.parse(request("encodings" to "cbor")))[0])
    }

    @Test
    fun `an empty element is refused`() {
        for (bad in listOf("", "flatbuffers,", ",flatbuffers")) {
            assertTrue("empty element" in refused(Options.parse(request("encodings" to bad)))[0], bad)
        }
    }

    @Test
    fun `an encoding named twice is refused`() {
        assertTrue("twice" in refused(Options.parse(request("encodings" to "flatbuffers,flatbuffers")))[0])
    }

    @Test
    fun `a kotlin-package that is not a package name is refused`() {
        for (bad in listOf("", "veh..cabin", "veh.1cabin", "veh-cabin", ".veh")) {
            refused(Options.parse(request("kotlin-package" to bad)))
        }
    }

    @Test
    fun `every bad option is reported, not only the first`() {
        assertEquals(2, refused(Options.parse(request("a" to "1", "b" to "2"))).size)
    }

    @Test
    fun `a refused option is an error diagnostic and no file`() {
        val response = Generator.generate(request("colour" to "blue"))
        assertEquals(0, response.filesCount)
        assertEquals(Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR, response.getDiagnostics(0).severity)
    }
}
