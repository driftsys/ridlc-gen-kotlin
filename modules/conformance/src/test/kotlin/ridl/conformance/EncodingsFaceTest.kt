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
 * The face over each encoding its package carries (docs/design.md §5): one
 * package selecting `flatbuffers` and `podbuf`, compiled with warnings as
 * errors, drives a signal, an event, a command and a query whose arguments
 * are an induced struct over a FlatBuffers loopback and over a podbuf one,
 * and checks the bytes on each port are that encoding's. A query whose reply
 * is a stream is served by `serveAsync`, whose `drive` writes each element,
 * and collected by the async client, which reads each one: the element codec
 * is the one use of the face no descriptor holds, chosen inline from the
 * port's encoding on each side. A port of an encoding the package does not
 * carry is refused when the face is bound. The same package selecting
 * `podbuf` alone is driven over a podbuf port. The corpus package `cabin`,
 * which carries both encodings, is driven the same way over each.
 */
class EncodingsFaceTest {
    @TempDir
    lateinit var work: Path

    private val source = """
        package probe.both

        /// A level.
        type Level : integer [0..100]

        /// A reading.
        struct Reading {
          level : Level
          note : string [0..16]
        }

        /// One member of each kind.
        interface Tank {
          /// The level.
          signal level : Level @1s
          /// A spill.
          event spilled : Reading @[100ms..1s]
          /// Fills to a level.
          command fill(level: Level) @[..50ms]
          /// Mixes two levels.
          query mix(a: Level, b: Level): Reading @[..50ms]
          /// The readings since a level.
          query history(level: Level): <Reading> @[..50ms]
        }
    """.trimIndent()

    private fun probe(both: Boolean): String = """
        @file:JvmName("EncodingsProbe")

        package probe.both.check

        import probe.both.Level
        import probe.both.LevelPodbufCodec
        import probe.both.Reading
        import probe.both.Tank
        import probe.both.TankAsyncClient
        import probe.both.TankPollClient
        import probe.both.TankProvider
        import probe.both.TankPublisher
        import probe.both.commit
        import probe.both.subscribeSpilled
        import probe.both.nextEvent
        ${if (both) "import probe.both.LevelCodec" else ""}
        import kotlinx.coroutines.CoroutineScope
        import kotlinx.coroutines.Dispatchers
        import kotlinx.coroutines.cancelAndJoin
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.flow
        import kotlinx.coroutines.flow.toList
        import kotlinx.coroutines.launch
        import kotlinx.coroutines.runBlocking
        import kotlinx.coroutines.withTimeout
        import ridl.rt.contract.Ordinal
        import ridl.rt.encoding.Encoding
        import ridl.rt.loopback.Loopback
        import ridl.rt.payload.Payload
        import java.nio.ByteBuffer

        private class Mixer : TankProvider {
            val filled = mutableListOf<Level>()

            override fun fill(level: Level) {
                filled += level
            }

            override fun mix(args: probe.both.TankMixParams): Reading =
                Reading(level = Level.of((args.a.value + args.b.value) / 2), note = "mixed")

            override fun history(level: Level): Flow<Reading> = flow {
                for (i in 1..level.value) emit(Reading(level = Level.of(i), note = "r${'$'}i"))
            }
        }

        private fun <T, V> bytes(codec: Payload<T, V>, value: T): ByteArray {
            val out = ByteBuffer.allocate(codec.maxSize)
            val n = codec.encode(value, out)
            return out.array().copyOf(n)
        }

        fun probe(): List<String> {
            val failures = mutableListOf<String>()
            fun <T> expectEqual(label: String, expected: T, actual: T) {
                if (expected != actual) failures += "${'$'}label: expected ${'$'}expected, got ${'$'}actual"
            }
            val carried = ${if (both) "listOf(Encoding.FlatBuffers to LevelCodec, Encoding.Podbuf to LevelPodbufCodec)" else "listOf(Encoding.Podbuf to LevelPodbufCodec)"}
            for ((encoding, levelCodec) in carried) {
                val rt = Loopback(Tank.catalog, encoding)
                val client = TankPollClient(rt)
                val publisher = TankPublisher(rt)
                val provider = Mixer()
                val scope = CoroutineScope(Dispatchers.Unconfined)
                val buffer = ByteBuffer.allocate(Tank.sizes(rt.encoding).maxBufferSize)
                expectEqual("${'$'}encoding: the table is the port's", encoding, Tank.sizes(rt.encoding).encoding)

                publisher.level(Level.of(42))
                publisher.commit()
                expectEqual("${'$'}encoding: the signal", Level.of(42), client.level().value)
                val raw = ByteBuffer.allocate(levelCodec.maxSize)
                val sample = rt.read(Tank.number, Ordinal(1u), raw)
                expectEqual(
                    "${'$'}encoding: the signal's bytes are the port's encoding",
                    bytes(levelCodec, Level.of(42)).toList(),
                    raw.array().copyOf(sample.len).toList(),
                )

                client.subscribeSpilled()
                val spill = Reading(level = Level.of(7), note = "spill")
                publisher.spilled(spill)
                when (val event = client.nextEvent()) {
                    is Tank.Event.Spilled -> expectEqual("${'$'}encoding: the event", Result.success(spill), event.occurrence.payload)
                    null -> failures += "${'$'}encoding: the event is received"
                }

                val fill = client.fill(Level.of(80))
                expectEqual("${'$'}encoding: dispatch settles the command", 1, Tank.dispatch(rt, provider, buffer, scope))
                expectEqual("${'$'}encoding: the command is acknowledged", Result.success(Unit), client.fillAck(fill))
                expectEqual("${'$'}encoding: the provider has the level", listOf(Level.of(80)), provider.filled)

                val mix = client.mix(probe.both.TankMixParams(a = Level.of(20), b = Level.of(40)))
                expectEqual("${'$'}encoding: dispatch settles the query", 1, Tank.dispatch(rt, provider, buffer, scope))
                expectEqual("${'$'}encoding: the reply", Result.success(Reading(level = Level.of(30), note = "mixed")), client.mixReply(mix))

                // A reply stream: `serveAsync`'s `drive` encodes each element, the async client decodes it.
                val streamed = Loopback(Tank.catalog, encoding)
                val readings = runBlocking(Dispatchers.Default) {
                    withTimeout(30_000) {
                        val server = launch { Tank.serveAsync(streamed.handler(), provider) }
                        val received = TankAsyncClient(streamed).history(Level.of(3)).toList()
                        server.cancelAndJoin()
                        received
                    }
                }
                expectEqual("${'$'}encoding: the reply stream", (1L..3L).map { Reading(level = Level.of(it), note = "r${'$'}it") }, readings)
            }
            val refused = ${if (both) "listOf(Encoding.Proto3)" else "listOf(Encoding.Proto3, Encoding.FlatBuffers)"}
            for (encoding in refused) {
                try {
                    TankPollClient(Loopback(Tank.catalog, encoding))
                    failures += "a ${'$'}encoding port is refused"
                } catch (e: IllegalArgumentException) {
                    val carries = ${if (both) "\"flatbuffers and podbuf\"" else "\"podbuf only\""}
                    expectEqual(
                        "the refusal of a ${'$'}encoding port",
                        "interface `Tank` has no ${'$'}{encoding.encodingName} codec: its package carries ${'$'}carries",
                        e.message,
                    )
                }
            }
            return failures
        }
    """.trimIndent()

    /** Generates package `probe.both` selecting [encodings], compiles it with the probe, and runs the probe. */
    private fun run(name: String, encodings: String, both: Boolean) {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"probe.both\"\nversion = \"1.0.0\"\n\n[backend.kotlin]\nencodings = [$encodings]\n",
        )
        pkg.resolve("both.ridl").writeText(source + "\n")
        val requests = Harness.capturedRequests(pkg, name, work.resolve("requests-$name").createDirectories(), "--emit", "codegen-model")
        val sources = requests.values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        assertTrue("probe/both/Faces.kt" in sources, sources.keys.toString())
        val compiled = Compiler.compile(sources + ("probe/both/check/Probe.kt" to probe(both)), work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages + "\n" + sources["probe/both/Faces.kt"])
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("probe.both.check.EncodingsProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `a two-encoding face serves a FlatBuffers port and a podbuf port`() {
        run("both", "\"flatbuffers\", \"podbuf\"", both = true)
    }

    @Test
    fun `a podbuf-only face serves a podbuf port and refuses a FlatBuffers one`() {
        run("podbuf", "\"podbuf\"", both = false)
    }

    private val cabinProbe = """
        @file:JvmName("CabinEncodingsProbe")

        package probe.cabin.check

        import ridl.rt.contract.Ordinal
        import ridl.rt.encoding.Encoding
        import ridl.rt.loopback.Loopback
        import ridl.rt.payload.Payload
        import veh.cabin.Average
        import veh.cabin.Cabin
        import veh.cabin.CabinPollClient
        import veh.cabin.CabinProvider
        import veh.cabin.CabinPublisher
        import veh.cabin.Health
        import veh.cabin.HornClient
        import veh.cabin.HornPublisher
        import veh.cabin.Level
        import veh.cabin.Temperature
        import veh.cabin.TemperatureCodec
        import veh.cabin.TemperaturePodbufCodec
        import veh.cabin.Warning
        import veh.cabin.Window
        import veh.cabin.commit
        import veh.cabin.nextEvent
        import veh.cabin.subscribeWarning
        import java.nio.ByteBuffer

        private class Recorder : CabinProvider {
            val levels = mutableListOf<Level>()

            override fun setLevel(level: Level) {
                levels += level
            }

            override fun average(window: Window): Average = Average.of(window.value / 100)
        }

        private fun <T, V> bytes(codec: Payload<T, V>, value: T): List<Byte> {
            val out = ByteBuffer.allocate(codec.maxSize)
            val n = codec.encode(value, out)
            return out.array().copyOf(n).toList()
        }

        fun probe(): List<String> {
            val failures = mutableListOf<String>()
            fun <T> expectEqual(label: String, expected: T, actual: T) {
                if (expected != actual) failures += "${'$'}label: expected ${'$'}expected, got ${'$'}actual"
            }
            for ((encoding, codec) in listOf(Encoding.FlatBuffers to TemperatureCodec, Encoding.Podbuf to TemperaturePodbufCodec)) {
                val rt = Loopback(Cabin.catalog, encoding)
                val client = CabinPollClient(rt)
                val publisher = CabinPublisher(rt)
                val provider = Recorder()
                val buffer = ByteBuffer.allocate(Cabin.sizes(rt.encoding).maxBufferSize)

                publisher.temperature(Temperature.of(-12))
                publisher.commit()
                expectEqual("${'$'}encoding: the signal", Temperature.of(-12), client.temperature().value)
                val raw = ByteBuffer.allocate(codec.maxSize)
                val sample = rt.read(Cabin.number, Ordinal(1u), raw)
                expectEqual("${'$'}encoding: the signal's bytes", bytes(codec, Temperature.of(-12)), raw.array().copyOf(sample.len).toList())

                client.subscribeWarning()
                val warning = Warning(code = Level.of(7), health = Health.FAIL)
                publisher.warning(warning)
                when (val event = client.nextEvent()) {
                    is Cabin.Event.Warning -> expectEqual("${'$'}encoding: the event", Result.success(warning), event.occurrence.payload)
                    null -> failures += "${'$'}encoding: the event is received"
                }

                val set = client.setLevel(Level.of(42))
                expectEqual("${'$'}encoding: dispatch settles the command", 1, Cabin.dispatch(rt, provider, buffer))
                expectEqual("${'$'}encoding: the command is acknowledged", Result.success(Unit), client.setLevelAck(set))
                expectEqual("${'$'}encoding: the provider has the level", listOf(Level.of(42)), provider.levels)

                val average = client.average(Window.of(5000))
                expectEqual("${'$'}encoding: dispatch settles the query", 1, Cabin.dispatch(rt, provider, buffer))
                expectEqual("${'$'}encoding: the reply", Result.success(Average.of(50)), client.averageReply(average))

                HornPublisher(rt).let { it.active(Health.WARN); it.commit() }
                expectEqual("${'$'}encoding: a second interface's signal", Health.WARN, HornClient(rt).active().value)
            }
            return failures
        }
    """.trimIndent()

    @Test
    fun `cabin, which carries both encodings, serves a FlatBuffers port and a podbuf port`() {
        val requests = Harness.capturedRequests("cabin", work.resolve("requests-cabin")).values
        val sources = requests.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        assertTrue("veh/cabin/PodbufCodec.kt" in sources && "veh/cabin/Codec.kt" in sources, sources.keys.toString())
        val compiled = Compiler.compile(sources + ("probe/cabin/check/Probe.kt" to cabinProbe), work.resolve("compile-cabin"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("probe.cabin.check.CabinEncodingsProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
