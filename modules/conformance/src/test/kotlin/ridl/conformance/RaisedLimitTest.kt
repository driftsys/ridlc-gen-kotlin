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
 * A member that a `[limits.interaction]` entry raises above 16,128 bytes:
 * since ridl 0.17.0 an entry may give a member of any kind up to 65,280
 * (ADR-0030 decision 18 as amended, driftsys/ridl#1268). A payload of 40,000
 * bytes goes through the generated face of a signal, an event, a command and
 * a query on the loopback, which takes the limits from `setLimit`, since it
 * holds no catalog descriptor.
 */
class RaisedLimitTest {
    @TempDir
    lateinit var work: Path

    private val source = """
        package probe.raised

        /// A payload above the default ceiling of every kind but a fixed.
        type Blob : bytes [0..40000]

        /// One member of each kind, each raised to 65,280 bytes.
        interface Up {
          /// The latest blob.
          signal latest : Blob @1s
          /// A blob that arrived.
          event arrived : Blob @[100ms..1s]
          /// Stores a blob.
          command put(blob: Blob) @[..50ms]
          /// Returns the blob it is given.
          query echo(blob: Blob): Blob @[..50ms]
        }
    """.trimIndent()

    private val limits = """

        [limits.interaction]
        "Up.latest" = 65280
        "Up.arrived" = 65280
        "Up.put" = 65280
        "Up.echo" = 65280
    """.trimIndent()

    private val probe = """
        @file:JvmName("RaisedProbe")

        package probe.raised.check

        import probe.raised.Blob
        import probe.raised.Up
        import probe.raised.UpPollClient
        import probe.raised.UpProvider
        import probe.raised.UpPublisher
        import probe.raised.commit
        import probe.raised.nextEvent
        import probe.raised.subscribeArrived
        import ridl.rt.contract.Ordinal
        import ridl.rt.loopback.Loopback
        import java.nio.ByteBuffer

        private class Echo : UpProvider {
            val stored = mutableListOf<Blob>()

            override fun put(blob: Blob) {
                stored += blob
            }

            override fun echo(blob: Blob): Blob = blob
        }

        fun probe(): List<String> {
            val failures = mutableListOf<String>()
            fun <T> expectEqual(label: String, expected: T, actual: T) {
                if (expected != actual) failures += "${'$'}label: expected ${'$'}expected, got ${'$'}actual"
            }
            val big = Blob.of(ByteArray(40_000) { it.toByte() })
            val rt = Loopback(Up.catalog)
            for (ord in 1u..4u) rt.setLimit(Up.number, Ordinal(ord), 65_280)
            val client = UpPollClient(rt)
            val publisher = UpPublisher(rt)
            val provider = Echo()
            val buffer = ByteBuffer.allocate(Up.sizes(rt.encoding).maxBufferSize)

            publisher.latest(big)
            publisher.commit()
            expectEqual("the signal", big, client.latest().value)

            client.subscribeArrived()
            publisher.arrived(big)
            when (val event = client.nextEvent()) {
                is Up.Event.Arrived -> expectEqual("the event", Result.success(big), event.occurrence.payload)
                null -> failures += "the event is received"
            }

            val put = client.put(big)
            expectEqual("dispatch settles the command", 1, Up.dispatch(rt, provider, buffer))
            expectEqual("the command is acknowledged", Result.success(Unit), client.putAck(put))
            expectEqual("the provider has the blob", listOf(big), provider.stored)

            val echo = client.echo(big)
            expectEqual("dispatch settles the query", 1, Up.dispatch(rt, provider, buffer))
            expectEqual("the reply", Result.success(big), client.echoReply(echo))
            return failures
        }
    """.trimIndent()

    @Test
    fun `a payload above 16,128 bytes round-trips on a member raised to 65,280`() {
        val pkg = work.resolve("src-raised").createDirectories()
        pkg.resolve("ridl.toml").writeText(Harness.manifest("probe.raised") + limits + "\n")
        pkg.resolve("raised.ridl").writeText(source + "\n")
        val requests = Harness.capturedRequests(pkg, "raised", work, "--emit", "codegen-model")
        val sources = requests.values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val compiled = Compiler.compile(sources + ("probe/raised/check/Probe.kt" to probe), work.resolve("compile-raised"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("probe.raised.check.RaisedProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
