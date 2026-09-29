package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.v1.ModelOuterClass
import ridl.codegen.v1.Plugin

/** docs/design.md §2, step 3: the two known keys and the refusal of every other. */
class OptionsTest {
    private fun request(vararg options: Pair<String, String>): Plugin.CodegenRequest =
        Plugin.CodegenRequest.newBuilder()
            .setSchema(SCHEMA)
            .setModel(ModelOuterClass.Model.newBuilder().setName(ModelOuterClass.DottedName.newBuilder().setDotted("veh.cabin")))
            .addAllOptions(options.map { (k, v) -> Plugin.BackendOption.newBuilder().setKey(k).setValue(v).build() })
            .build()

    private fun refused(parsed: Options.Parsed): List<String> = (parsed as Options.Parsed.Refused).messages

    @Test
    fun `no option means the package's dotted name and flatbuffers`() {
        assertEquals(
            Options.Parsed.Ok(Options("veh.cabin", WireEncoding.FlatBuffers)),
            Options.parse(request()),
        )
    }

    @Test
    fun `kotlin-package and wire-encoding are read`() {
        assertEquals(
            Options.Parsed.Ok(Options("com.example.cabin", WireEncoding.FlatBuffers)),
            Options.parse(request("kotlin-package" to "com.example.cabin", "wire-encoding" to "flatbuffers")),
        )
    }

    @Test
    fun `an unknown key is one error naming it`() {
        val messages = refused(Options.parse(request("colour" to "blue")))
        assertEquals(1, messages.size)
        assertTrue("`colour`" in messages[0], messages[0])
    }

    @Test
    fun `an unknown encoding is refused until O-K1 admits it`() {
        assertTrue("`proto3`" in refused(Options.parse(request("wire-encoding" to "proto3")))[0])
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
