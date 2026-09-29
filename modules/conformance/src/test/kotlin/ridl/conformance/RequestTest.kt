package ridl.conformance

import com.google.protobuf.util.JsonFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.SCHEMA
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin
import java.nio.file.Path

/** docs/design.md §7, "The request is read as the specification says". */
class RequestTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `a request the pinned ridl wrote parses`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val requests = Harness.capturedRequests(name, work.resolve(name))
            assertTrue(Harness.manifestName(name) in requests, "the package's own request is among them")
            for ((dotted, json) in requests) {
                val request = Wire.readRequest(json)
                assertEquals(SCHEMA, request.schema)
                assertEquals(dotted, request.model.name.dotted)
                assertTrue(request.model.declarationsCount > 0, "the model carries the declarations")
                // Nothing is lost: the parsed request renders back to the same message.
                assertEquals(request, Wire.readRequest(JsonFormat.printer().print(request)))
            }
        }
    }

    @Test
    fun `a request with an unknown key parses`() {
        val json = Harness.capturedRequest("cabin", work)
        val extended = json
            .replaceFirst("{", "{\n  \"fromAFutureRidl\": {\"nested\": [1, 2, {\"deeper\": true}]},")
            .replaceFirst("\"model\": {", "\"model\": {\n    \"alsoNew\": \"value\",")
        assertTrue(extended.contains("fromAFutureRidl") && extended.contains("alsoNew"))
        assertEquals(Wire.readRequest(json), Wire.readRequest(extended))
    }

    @Test
    fun `a request nested to 1,000 levels parses`() {
        val (request, json) = Deep.request(1000)
        assertEquals(1000, Deep.jsonDepth(json))
        assertEquals(request, Wire.readRequest(json))
    }

    @Test
    fun `a request with a wrong schema gets exactly one error diagnostic`() {
        val request = Plugin.CodegenRequest.newBuilder().setSchema("ridl.codegen.v2").build()
        val response = Generator.generate(request)
        assertEquals(0, response.filesCount)
        assertEquals(1, response.diagnosticsCount)
        val diagnostic = response.getDiagnostics(0)
        assertEquals(Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR, diagnostic.severity)
        assertTrue("ridl.codegen.v1" in diagnostic.message && "ridl.codegen.v2" in diagnostic.message, diagnostic.message)
    }
}
