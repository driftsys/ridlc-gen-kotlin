package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin
import java.nio.file.Path
import kotlin.io.path.readText

/** docs/design.md §2: what the installed executable does, observed from outside. */
class ProcessTest {
    @TempDir
    lateinit var work: Path

    private fun response(run: Harness.Run): Plugin.CodegenResponse =
        Plugin.CodegenResponse.newBuilder().also {
            com.google.protobuf.util.JsonFormat.parser().merge(run.stdout, it)
        }.build()

    @Test
    fun `the launcher script ends in exec`() {
        val lines = Harness.plugin.readText().trimEnd().lines()
        assertEquals("exec \"\$JAVACMD\" \"\$@\"", lines.last())
    }

    @Test
    fun `a request is answered with exit 0 and a response`() {
        val run = Harness.plugin(Harness.capturedRequest("cabin", work))
        assertEquals(0, run.exit, run.stderr)
        assertEquals(0, response(run).diagnosticsCount)
        assertTrue("request from ridlc" in run.stderr, "the toolchain is logged to standard error")
    }

    @Test
    fun `a wrong schema is one error diagnostic and exit 0`() {
        val request = Harness.capturedRequest("cabin", work).replace("\"ridl.codegen.v1\"", "\"ridl.codegen.v9\"")
        val run = Harness.plugin(request)
        assertEquals(0, run.exit, run.stderr)
        val diagnostics = response(run).diagnosticsList
        assertEquals(1, diagnostics.size)
        assertEquals(Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR, diagnostics[0].severity)
    }

    @Test
    fun `an unknown option is an error diagnostic`() {
        val request = Wire.readRequest(Harness.capturedRequest("cabin", work)).toBuilder()
            .addOptions(Plugin.BackendOption.newBuilder().setKey("colour").setValue("blue"))
            .build()
        val run = Harness.plugin(com.google.protobuf.util.JsonFormat.printer().print(request))
        assertEquals(0, run.exit, run.stderr)
        val diagnostics = response(run).diagnosticsList
        assertEquals(1, diagnostics.size)
        assertTrue("colour" in diagnostics[0].message, diagnostics[0].message)
    }

    @Test
    fun `the installed plugin reads a request nested to 1,000 levels`() {
        val run = Harness.plugin(Deep.request(1000).second)
        assertEquals(0, run.exit, run.stderr)
    }

    @Test
    fun `input that is not a request is exit 3 with the stack on standard error`() {
        val run = Harness.plugin("this is not JSON")
        assertEquals(3, run.exit)
        assertEquals("", run.stdout)
        assertTrue("\tat " in run.stderr, run.stderr)
    }
}
