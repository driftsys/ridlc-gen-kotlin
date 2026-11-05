package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A member that carries a stream (#105): the face leaves it out, as the Rust
 * backend does, because a plain call would send it without its stream. Its
 * descriptor row and its row of the size table stay. Since ridl 0.15 such a
 * member has a request, so nothing but `Interaction.streams` tells it apart.
 */
class StreamsTest {
    @TempDir
    lateinit var work: Path

    private val types = """
        type Level : integer [0..10]
        struct Sample {
          level : Level
        }
    """.trimIndent()

    /** `Faces.kt` of package `probe.<name>` over [interfaceBody], with no diagnostic, once it compiles. */
    private fun faces(name: String, interfaceBody: String): String {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText(Harness.manifest("probe.$name"))
        pkg.resolve("$name.ridl").writeText("package probe.$name\n\n$types\n\ninterface Up {\n$interfaceBody\n}\n")
        val requests = Harness.capturedRequests(pkg, name, work, "--emit", "codegen-model")
        val sources = requests.values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val compiled = Compiler.compile(sources, work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages)
        return sources.getValue("probe/$name/Faces.kt")
    }

    @Test
    fun `a call with a stream parameter has no method, and the plain call beside it keeps its own`() {
        val text = faces(
            "parameter",
            """
              command ping(level: Level) @[..50ms]
              command putBlob(level: Level, data: <bytes>) @[..50ms]
              command replay(samples: <Sample>) @[..50ms]
              query lookup(samples: <Sample>): Sample @[..50ms]
            """.trimIndent(),
        )
        assertTrue("fun ping(" in text, text)
        for (member in listOf("putBlob", "replay", "lookup")) assertFalse("fun $member(" in text, "`$member` has a method")
        for (member in listOf("PutBlob", "Replay", "Lookup")) {
            assertFalse("${member}Correlation" in text, "`$member` has a correlation")
            assertFalse("Up${member}Call" in text, "`$member` has a call")
            assertFalse("object Up$member" in text, "`$member` has a typed descriptor")
        }
    }

    @Test
    fun `a call with a stream keeps its descriptor row`() {
        val text = faces(
            "rows",
            """
              command putBlob(level: Level, data: <bytes>) @[..50ms]
              query history(level: Level): <Sample> @[..50ms]
            """.trimIndent(),
        )
        assertTrue("Kind.Command, \"putBlob\"" in text, text)
        assertTrue("Kind.Query, \"history\"" in text, text)
    }

    @Test
    fun `a streamed reply keeps the interface's face, and its row names the request alone`() {
        val text = faces(
            "reply",
            """
              command ping(level: Level) @[..50ms]
              query history(level: Level): <Sample> @[..50ms]
            """.trimIndent(),
        )
        assertTrue("fun ping(" in text, text)
        assertFalse("fun history(" in text, text)
        assertTrue(Regex("""Kind\.Query, "history", .*, listOf\(PayloadInfo\("Level"\)\), """).containsMatchIn(text), text)
        assertTrue(
            Regex("""listOf\(LevelCodec\.maxSize\.toULong\(\), LevelCodec\.maxSize\.toULong\(\)\),""").containsMatchIn(text),
            text,
        )
    }

    @Test
    fun `a member's row names its streams, and a plain member's row has none`() {
        val text = faces(
            "infos",
            """
              command ping(level: Level) @[..50ms]
              command putBlob(level: Level, data: <bytes>) @[..50ms]
              query history(level: Level): <Sample> @[..50ms]
              query logs(level: Level): <string> @[..50ms]
            """.trimIndent(),
        )
        fun row(member: String) = text.lines().first { "\"$member\"" in it && "Member(" in it }
        assertTrue(row("ping").endsWith(", emptyList()),"), row("ping"))
        assertTrue("""listOf(StreamInfo(StreamNo(1u), StreamForm.Bytes, ""))""" in row("putBlob"), row("putBlob"))
        assertTrue("""listOf(StreamInfo(StreamNo(0u), StreamForm.Elements, "probe.infos.Sample"))""" in row("history"), row("history"))
        assertTrue("""listOf(StreamInfo(StreamNo(0u), StreamForm.Text, ""))""" in row("logs"), row("logs"))
    }

    @Test
    fun `the table budget counts a stream member's request`() {
        val text = faces(
            "budget",
            """
              signal levelNow : Level @1s
              command replay(samples: <Sample>) @[..50ms]
            """.trimIndent(),
        )
        assertTrue(Regex("""\n\s+UpReplayParamsCodec\.maxSize,""").containsMatchIn(text), text)
    }

    @Test
    fun `an interface of stream members alone has its descriptors and no client or provider`() {
        val text = faces("only", "  command replay(samples: <Sample>) @[..50ms]")
        assertTrue("Kind.Command, \"replay\"" in text, text)
        assertFalse("class UpClient" in text, text)
        assertFalse("interface UpProvider" in text, text)
    }
}
