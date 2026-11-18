package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A `require` on the ordinary parameter of a call that also takes a parameter
 * stream (#156): since ridl 0.18.0 (driftsys/ridl#1289) the lowering
 * translates it, and the comparison's subject keeps the parameter's declared
 * index, 1 when the stream comes first. The face checks the clause on that
 * parameter with the stream first and with it last, on an upload query, an
 * upload command and a query of both streams, beside a reply stream's query,
 * as driftsys/ridl's `crates/ridl-backend-rust/tests/query_uploads.rs` does
 * for the Rust face. A failed `require` is refused by the client before
 * anything is sent, and settled `PreconditionFailed` by the provider; an
 * accepted one goes through, over the loopback.
 */
class UploadRequireTest {
    @TempDir
    lateinit var work: Path

    private fun source(params: String) = """
        package face.uploads

        /// A level.
        type Level : integer [0..100]

        /// Calls whose `require` reads the parameter beside the stream.
        interface Store {
          /// Uploads levels and replies with one.
          query put($params): Level @[..100ms] [ require limit > 0 ]
          /// Uploads levels.
          command push($params) @[..100ms] [ require limit > 0 ]
          /// The levels up to `limit`.
          query history(limit: Level): <Level> @[..100ms] [ require limit > 0 ]
          /// Returns levels while they are uploaded.
          query pipe($params): <Level> @[..100ms] [ require limit > 0 ]
        }
    """.trimIndent()

    private val probe = """
        @file:JvmName("UploadRequireProbe")

        package face.uploads.check

        import face.uploads.Level
        import face.uploads.LevelCodec
        import face.uploads.Store
        import face.uploads.StoreAsyncClient
        import face.uploads.StoreProvider
        import kotlinx.coroutines.Dispatchers
        import kotlinx.coroutines.Job
        import kotlinx.coroutines.cancelAndJoin
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.flow
        import kotlinx.coroutines.flow.flowOf
        import kotlinx.coroutines.flow.toList
        import kotlinx.coroutines.launch
        import kotlinx.coroutines.runBlocking
        import kotlinx.coroutines.withTimeout
        import kotlinx.coroutines.yield
        import ridl.rt.contract.Ordinal
        import ridl.rt.error.ClientError
        import ridl.rt.error.Contract
        import ridl.rt.loopback.Loopback
        import ridl.rt.port.SendError
        import java.nio.ByteBuffer
        import java.util.Collections

        private class Levels : StoreProvider {
            val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val pushed = Job()

            override suspend fun put(limit: Level, data: Flow<Level>): Level {
                seen += "put ${'$'}{limit.value}"
                return Level.of(data.toList().sumOf { it.value })
            }

            override suspend fun push(limit: Level, data: Flow<Level>) {
                seen += "push ${'$'}{limit.value}"
                data.toList()
                pushed.complete()
            }

            override fun history(limit: Level): Flow<Level> {
                seen += "history ${'$'}{limit.value}"
                return flowOf(Level.of(7))
            }

            override fun pipe(limit: Level, data: Flow<Level>): Flow<Level> {
                seen += "pipe ${'$'}{limit.value}"
                return data
            }
        }

        fun probe(): List<String> = runBlocking(Dispatchers.Default) {
            val failures = mutableListOf<String>()
            fun <T> expectEqual(label: String, expected: T, actual: T) {
                if (expected != actual) failures += "${'$'}label: expected ${'$'}expected, got ${'$'}actual"
            }
            suspend fun refused(label: String, call: suspend () -> Unit) {
                val thrown = try {
                    call()
                    null
                } catch (e: ClientError) {
                    e
                }
                expectEqual(label, ClientError.Send(SendError.Contract(Contract.PreconditionFailed)), thrown)
            }
            withTimeout(30_000) {
                val rt = Loopback(Store.catalog)
                val levels = Levels()
                val server = launch(Dispatchers.Default) { Store.serveAsync(rt.handler(), levels) }
                val client = StoreAsyncClient(rt.caller())
                var collected = false
                val untouched = flow<Level> {
                    collected = true
                    emit(Level.of(1))
                }

                refused("a failed require on an upload query") { client.put(Level.of(0), untouched) }
                refused("a failed require on an upload command") { client.push(Level.of(0), untouched) }
                refused("a failed require on a reply stream") { client.history(Level.of(0)).toList() }
                refused("a failed require on both streams") { client.pipe(Level.of(0), untouched).toList() }
                expectEqual("a refused call does not collect its stream", false, collected)
                expectEqual("a refused call reaches no provider", emptyList<String>(), levels.seen.toList())

                expectEqual("an accepted upload query", Level.of(5), client.put(Level.of(1), flowOf(Level.of(2), Level.of(3))))
                // A command is acknowledged before its provider runs.
                client.push(Level.of(1), flowOf(Level.of(4)))
                levels.pushed.join()
                expectEqual("an accepted reply stream", listOf(Level.of(7)), client.history(Level.of(1)).toList())
                expectEqual("an accepted pipe", listOf(Level.of(8)), client.pipe(Level.of(1), flowOf(Level.of(8))).toList())
                expectEqual("each accepted call reaches its provider", listOf("put 1", "push 1", "history 1", "pipe 1"), levels.seen.toList())

                // Past the client: the provider evaluates the clause on the same parameter.
                fun zero(): ByteBuffer {
                    val buffer = ByteBuffer.allocate(LevelCodec.maxSize)
                    LevelCodec.encode(Level.of(0), buffer)
                    buffer.flip()
                    return buffer
                }
                val query = rt.query(Store.number, Ordinal(1u), zero(), null)
                var reply = rt.reply(query, ByteBuffer.allocate(64))
                while (reply == null) {
                    yield()
                    reply = rt.reply(query, ByteBuffer.allocate(64))
                }
                expectEqual("the provider settles a failed upload query", Result.failure<Unit>(Contract.PreconditionFailed), reply.map { })
                val command = rt.command(Store.number, Ordinal(2u), zero(), null)
                var ack = rt.ack(command)
                while (ack == null) {
                    yield()
                    ack = rt.ack(command)
                }
                expectEqual("the provider settles a failed upload command", Result.failure<Unit>(Contract.PreconditionFailed), ack)
                expectEqual("a settled refusal reaches no provider", 4, levels.seen.size)
                server.cancelAndJoin()
            }
            failures
        }
    """.trimIndent()

    @TestFactory
    fun `a require on the parameter beside a stream is checked on it`(): List<DynamicTest> = listOf(
        "the stream last" to "limit: Level, data: <Level>",
        "the stream first" to "data: <Level>, limit: Level",
    ).mapIndexed { index, (label, params) ->
        DynamicTest.dynamicTest(label) {
            val name = "upload-$index"
            val pkg = work.resolve("src-$name").createDirectories()
            pkg.resolve("ridl.toml").writeText(Harness.manifest("face.uploads"))
            pkg.resolve("uploads.ridl").writeText(source(params) + "\n")
            val sources = Harness.capturedRequests(pkg, name, work).values.flatMap { request ->
                val response = Generator.generate(Wire.readRequest(request))
                assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
                response.filesList.map { it.path to it.text }
            }.toMap()
            val compiled = Compiler.compile(sources + ("face/uploads/check/Probe.kt" to probe), work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
            @Suppress("UNCHECKED_CAST")
            val failures = compiled.classLoader!!.loadClass("face.uploads.check.UploadRequireProbe").getMethod("probe").invoke(null) as List<String>
            assertEquals(emptyList<String>(), failures)
        }
    }
}
