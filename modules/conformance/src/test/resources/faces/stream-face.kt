// The stream faces of face.stream over the loopback: every form the face
// carries, each client call served by the generated `serveAsync`. Compiled with
// the generated face/stream sources.
@file:JvmName("StreamFaceProbe")

package ridl.conformance.probe.faces.stream

import face.stream.Docs
import face.stream.DocsAsyncClient
import face.stream.DocsProvider
import face.stream.Id
import face.stream.Info
import face.stream.Level
import face.stream.LevelCodec
import face.stream.Log
import face.stream.LogAsyncClient
import face.stream.LogProvider
import face.stream.RawQueries
import face.stream.RawQueriesAsyncClient
import face.stream.RawQueriesProvider
import face.stream.Reading
import face.stream.Summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ridl.rt.contract.Ordinal
import ridl.rt.error.ClientError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.flatbuffers.Reader
import ridl.rt.loopback.Loopback
import ridl.rt.payload.Rule
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.SendError
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private val failures = mutableListOf<String>()

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private fun reading(level: Int) = Reading(Level.of(level.toLong()), level % 2 == 0)

private class Logs : LogProvider {
    val cleared = mutableListOf<Level>()
    val uploaded = mutableListOf<Reading>()
    val stoppedBy = mutableListOf<Throwable>()
    var uploadDone = Job()
    var uploadStarted = Job()
    var failAfter: Int? = null
    var collectedAll = AtomicBoolean(false)

    override fun clear(level: Level) {
        cleared += level
    }

    override fun history(limit: Level): Flow<Reading> = flow {
        for (i in 1..limit.value.toInt()) {
            if (i == failAfter) error("the source failed")
            emit(reading(i))
        }
        collectedAll.set(true)
    }

    override suspend fun upload(args: face.stream.LogUploadParams, data: Flow<Reading>) {
        try {
            data.collect {
                uploaded += it
                uploadStarted.complete()
            }
        } catch (e: Throwable) {
            stoppedBy += e
            throw e
        } finally {
            uploadDone.complete()
        }
    }

    override suspend fun summarize(args: face.stream.LogSummarizeParams, data: Flow<Reading>): Summary {
        val levels = data.toList().map { it.level.value }
        return Summary(levels.size.toLong(), levels.sum())
    }

    override fun pipe(args: face.stream.LogPipeParams, data: Flow<Reading>): Flow<Reading> = data.map { Reading(it.level, !it.flag) }

    override suspend fun checked(args: face.stream.LogCheckedParams, data: Flow<Reading>): Level = data.toList().last().level
}

private class Documents : DocsProvider {
    var bytes = ByteArray(0)
    var noted = mutableListOf<String>()
    val done = Job()

    override fun fetch(id: Id): Flow<ByteArray> = flowOf(ByteArray(40_000) { it.toByte() })

    override fun text(id: Id): Flow<String> = flowOf("héllo ", "wörld")

    override suspend fun put(info: Info, data: Flow<ByteArray>) {
        bytes = data.fold(ByteArray(0)) { all, chunk -> all + chunk }
        done.complete()
    }

    override suspend fun note(args: face.stream.DocsNoteParams, data: Flow<String>) {
        data.collect { noted += it }
        done.complete()
    }
}

private class Raw : RawQueriesProvider {
    override suspend fun sum(args: face.stream.RawQueriesSumParams, data: Flow<ByteArray>): Summary {
        val chunks = data.toList()
        return Summary(chunks.size.toLong(), chunks.sumOf { it.size }.toLong())
    }

    override fun pipe(args: face.stream.RawQueriesPipeParams, data: Flow<String>): Flow<Reading> = data.map { reading(it.length) }
}

private suspend fun <T> outcome(block: suspend () -> T): Throwable? = try {
    block()
    null
} catch (e: ClientError) {
    e
}

private fun CoroutineScope.serving(block: suspend () -> Unit): Job = launch(Dispatchers.Default) { block() }

fun probe(): List<String> = runBlocking(Dispatchers.Default) {
    withTimeout(30_000) {
        // A reply stream of named elements, in order, ending with the stream.
        val logs = Logs()
        val rt = Loopback(Log.catalog)
        val server = serving { Log.serveAsync(rt.handler().also { it.serve(Log.number, emptyList()) }, logs) }
        val client = LogAsyncClient(rt.caller())
        expectEqual("the reply stream", (1..3).map(::reading), client.history(Level.of(3)).toList())
        expect("the source ran to its end", logs.collectedAll.get())

        // More elements than the window holds: the collector paces the source.
        expectEqual("a stream longer than the window", 20, client.history(Level.of(20)).toList().size)

        // `require` refuses before anything is sent, when the stream is collected.
        val refused = client.history(Level.of(0))
        expectEqual(
            "a failed require throws when the stream is collected",
            ClientError.Send(SendError.Contract(Contract.PreconditionFailed)),
            outcome { refused.toList() },
        )

        // A plain command beside the streams still works.
        client.clear(Level.of(1))
        expectEqual("a plain call beside the streams", listOf(Level.of(1)), logs.cleared)

        // A collector that stops early leaves the slot free.
        expectEqual("an early stop", reading(1), client.history(Level.of(20)).first())
        repeat(Loopback.SLOTS * 2) { client.history(Level.of(2)).first() }

        // An IllegalStateException in the source interrupts the stream after what it sent.
        logs.failAfter = 3
        val received = mutableListOf<Reading>()
        val interrupted = outcome { client.history(Level.of(5)).collect { received += it } }
        expectEqual("a failed source interrupts the stream", ClientError.Call(Transport.Interrupted), interrupted)
        expectEqual("what the source sent before it failed", (1..2).map(::reading), received)
        logs.failAfter = null

        // One failed stream does not end serveAsync.
        expectEqual("the server goes on", 2, client.history(Level.of(2)).toList().size)
        server.cancelAndJoin()

        val rt2 = Loopback(Log.catalog)
        val logs2 = Logs()
        val server2 = serving { Log.serveAsync(rt2.handler(), logs2) }
        val client2 = LogAsyncClient(rt2.caller())

        // A stream parameter on a command, read by the provider to its end.
        client2.upload(face.stream.LogUploadParams(), flowOf(reading(1), reading(2)))
        logs2.uploadDone.join()
        expectEqual("the upload", listOf(reading(1), reading(2)), logs2.uploaded)

        // A stream parameter on a query, with a plain reply after its end.
        val summary = client2.summarize(face.stream.LogSummarizeParams(), flowOf(reading(4), reading(6)))
        expectEqual("the summary", Summary(2, 10), summary)

        // An upload longer than the window.
        val long = client2.summarize(face.stream.LogSummarizeParams(), flow { for (i in 1..50) emit(reading(i % 10 + 1)) })
        expectEqual("an upload longer than the window", 50L, long.count)

        // An `ensure` on the plain reply of a streamed query.
        expectEqual(
            "ensure on an upload's reply",
            ClientError.Call(Contract.ContractBroken),
            outcome { client2.checked(face.stream.LogCheckedParams(), flowOf(reading(60))) },
        )

        // Raw successful settlements still pass the generated reply decoder's checks.
        suspend fun rawReply(bytes: ByteBuffer): Throwable? {
            val rawRuntime = Loopback(Log.catalog)
            val rawHandler = rawRuntime.handler().also { it.serve(Log.number, emptyList()) }
            val replying = serving {
                while (true) {
                    val claim = rawHandler.nextClaim(ByteBuffer.allocate(64))
                    if (claim != null) {
                        rawHandler.settle(claim.id, Result.success(bytes))
                        break
                    }
                    kotlinx.coroutines.yield()
                }
            }
            val error = try {
                LogAsyncClient(rawRuntime.caller()).checked(face.stream.LogCheckedParams(), flowOf(reading(1)))
                null
            } catch (e: Throwable) {
                e
            }
            replying.join()
            return error
        }
        expectEqual(
            "a truncated upload reply is corrupt",
            ClientError.Call(Transport.Corrupt),
            rawReply(ByteBuffer.wrap(byteArrayOf(1))),
        )
        val invalidLevel = ByteBuffer.allocate(LevelCodec.maxSize)
        LevelCodec.encode(Level.of(1), invalidLevel)
        invalidLevel.flip()
        val reader = Reader(invalidLevel)
        invalidLevel.put(checkNotNull(reader.field(reader.root(), 0, 1)), 101.toByte())
        val invalidReply = rawReply(invalidLevel) as? ClientError.Call
        expect("an upload reply constraint becomes InvalidValue", invalidReply?.error is Contract.InvalidValue)

        // Both streams at once.
        val piped = client2.pipe(face.stream.LogPipeParams(), flowOf(reading(1), reading(2), reading(3))).toList()
        expectEqual("both streams at once", listOf(1L, 2L, 3L), piped.map { it.level.value })
        expect("the provider's map ran", piped[0].flag != reading(1).flag)

        // A source that fails interrupts the upload, and the caller sees its own failure.
        // The source waits for the provider: a forget before the claim withdraws the call whole.
        logs2.uploadDone = Job()
        logs2.uploadStarted = Job()
        val source = RuntimeException("the upload's source failed")
        val thrown = try {
            client2.upload(face.stream.LogUploadParams(), flow {
                emit(reading(1))
                logs2.uploadStarted.join()
                throw source
            })
            null
        } catch (e: RuntimeException) {
            e
        }
        expectEqual("a failed upload source", source, thrown)
        logs2.uploadDone.join()
        expect("the provider saw the interruption: ${logs2.stoppedBy}", logs2.stoppedBy.singleOrNull() == Transport.Interrupted)
        server2.cancelAndJoin()

        // A chunk that fails its check, raw over the port: the client refuses the stream.
        val rt3 = Loopback(Log.catalog)
        val client3 = LogAsyncClient(rt3.caller())
        val handler3 = rt3.handler()
        handler3.serve(Log.number, listOf(Ordinal(1u)))
        val bad = launch(Dispatchers.Default) {
            while (true) {
                val claim = handler3.nextClaim(ByteBuffer.allocate(64))
                if (claim != null) {
                    handler3.pushClaim(claim.id, REPLY_STREAM, ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                    break
                }
                kotlinx.coroutines.yield()
            }
        }
        expectEqual("a corrupt chunk refuses the stream", ClientError.Call(Transport.Corrupt), outcome { client3.history(Level.of(1)).toList() })
        bad.join()

        // Bytes and text: a value longer than a chunk is split, and text stays whole characters.
        val documents = Documents()
        val rt4 = Loopback(Docs.catalog)
        val server4 = serving { Docs.serveAsync(rt4.handler(), documents) }
        val docs = DocsAsyncClient(rt4.caller())
        val fetched = docs.fetch(Id.of(1)).toList()
        expect("40,000 bytes take several chunks", fetched.size > 1)
        expectEqual("the bytes", 40_000, fetched.sumOf { it.size })
        expectEqual("the text", "héllo wörld", docs.text(Id.of(1)).toList().joinToString(""))
        docs.put(Info(Id.of(1)), flowOf(byteArrayOf(1, 2), byteArrayOf(3)))
        documents.done.join()
        expectEqual("the uploaded bytes", listOf<Byte>(1, 2, 3), documents.bytes.toList())
        server4.cancelAndJoin()

        val rt5 = Loopback(RawQueries.catalog)
        val server5 = serving { RawQueries.serveAsync(rt5.handler(), Raw()) }
        val raw = RawQueriesAsyncClient(rt5.caller())
        expectEqual("a query over uploaded bytes", Summary(2, 3), raw.sum(face.stream.RawQueriesSumParams(), flowOf(byteArrayOf(1), byteArrayOf(2, 3))))
        expectEqual("text up and elements down", listOf(2L), raw.pipe(face.stream.RawQueriesPipeParams(), flowOf("ab")).toList().map { it.level.value })
        server5.cancelAndJoin()

        // Invalid UTF-8 raw over the port: the receiver refuses it with Rule.Utf8.
        val rt6 = Loopback(Docs.catalog)
        val docs6 = DocsAsyncClient(rt6.caller())
        val handler6 = rt6.handler()
        val bad6 = launch(Dispatchers.Default) {
            while (true) {
                val claim = handler6.nextClaim(ByteBuffer.allocate(64))
                if (claim != null) {
                    handler6.pushClaim(claim.id, REPLY_STREAM, ByteBuffer.wrap(byteArrayOf(0xC3.toByte())))
                    break
                }
                kotlinx.coroutines.yield()
            }
        }
        val utf8 = outcome { docs6.text(Id.of(1)).toList() } as? ClientError.Call
        val invalid = utf8?.error as? Contract.InvalidValue
        expectEqual("bad UTF-8 is refused", Rule.Utf8, invalid?.violation?.rule)
        bad6.join()
    }
    failures
}
