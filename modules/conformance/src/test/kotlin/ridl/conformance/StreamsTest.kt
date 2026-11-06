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
 * A member that carries a stream: the forms the face carries, and what stays
 * out of it, as the Rust backend leaves them out. Since ridl 0.15 such a member
 * has a request, so nothing but `Interaction.streams` tells it apart. Its
 * descriptor row and its row of the size table stay either way. `FacesTest`
 * drives the forms over the loopback.
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
    fun `a stream before a named parameter and a stream named args compile`() {
        faces(
            "names",
            """
              command put(data: <Sample>, level: Level) @[..50ms]
              command send(args: <Sample>) @[..50ms]
              query lookup(data: <Sample>, level: Level): Sample @[..50ms]
              query pipe(args: <Sample>): <Sample> @[..50ms]
            """.trimIndent(),
        )
    }

    @Test
    fun `a call with one stream parameter is on the async client alone, beside its plain call`() {
        val text = faces(
            "parameter",
            """
              command ping(level: Level) @[..50ms]
              command putBlob(level: Level, data: <bytes>) @[..50ms]
              command replay(samples: <Sample>) @[..50ms]
              query lookup(samples: <Sample>): Sample @[..50ms]
            """.trimIndent(),
        )
        val blocking = text.substring(text.indexOf("class UpClient"), text.indexOf("class UpAsyncClient"))
        assertTrue("fun ping(" in blocking, blocking)
        for (member in listOf("putBlob", "replay", "lookup")) assertFalse("fun $member(" in blocking, "`$member` is on the blocking client")
        assertTrue("suspend fun putBlob(level: Level, `data`: Flow<ByteArray>)" in text || "suspend fun putBlob(level: Level, data: Flow<ByteArray>)" in text, text)
        assertTrue("suspend fun lookup(" in text && "Flow<Sample>): Sample" in text, text)
        for (member in listOf("PutBlob", "Replay", "Lookup")) assertFalse("Up${member}Call" in text, "`$member` has a call")
    }

    @Test
    fun `a member with two stream parameters stays out of the face`() {
        val text = faces(
            "two",
            """
              command ping(level: Level) @[..50ms]
              command splice(left: <Sample>, right: <Sample>) @[..50ms]
            """.trimIndent(),
        )
        assertFalse("fun splice(" in text, text)
        assertFalse("object UpSplice" in text, text)
        assertTrue("Kind.Command, \"splice\"" in text, text)
    }

    @Test
    fun `an interface with a stream has no blocking serve, and one with none keeps it`() {
        val streamed = faces("streamed", "  query history(level: Level): <Sample> @[..50ms]")
        assertFalse("fun <H> serve(" in streamed, streamed)
        assertTrue("suspend fun <H> serveAsync(" in streamed, streamed)
        assertTrue("P : StreamCaller" in streamed && "H : StreamHandler" in streamed, streamed)
        val plain = faces("plain", "  query latest(level: Level): Sample @[..50ms]")
        assertTrue("fun <H> serve(" in plain, plain)
        assertFalse("StreamCaller" in plain || "StreamHandler" in plain, plain)
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
        assertFalse("fun history(" in text.substring(text.indexOf("class UpClient"), text.indexOf("class UpAsyncClient")), text)
        assertTrue("fun history(level: Level): Flow<Sample>" in text, text)
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
    fun `an interface of stream members alone has an async client and a provider`() {
        val text = faces("only", "  command replay(samples: <Sample>) @[..50ms]")
        assertTrue("Kind.Command, \"replay\"" in text, text)
        assertFalse("class UpClient" in text, text)
        assertTrue("class UpAsyncClient" in text, text)
        assertTrue("interface UpProvider" in text, text)
    }
}
