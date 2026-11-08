package ridl.rt.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.ClientError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.loopback.CallerHandle
import ridl.rt.loopback.HandlerHandle
import ridl.rt.loopback.Loopback
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.Encoded
import ridl.rt.port.Interest
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.SettleError
import ridl.rt.port.StreamCaller
import ridl.rt.port.StreamHandler
import ridl.rt.port.Wakeable
import ridl.rt.sample.Duration
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private val IFACE = InterfaceNo(1u)
private val ORD = Ordinal(1u)
private val CHUNKS = byteChunks(16)
private const val BOUND = 5_000L

/** A caller whose every waiting task a test can wake by hand, as a spurious wake of a runtime does. */
private class Poked(private val caller: CallerHandle) : StreamCaller by caller, Clock by caller, Wakeable by caller, Encoded by caller {
    private val wakers = CopyOnWriteArrayList<Waker>()

    override val catalog: CatalogRef get() = caller.catalog
    override val encoding get() = caller.encoding

    override fun wakeOn(what: Interest, waker: Waker) {
        wakers += waker
        caller.wakeOn(what, waker)
    }

    fun poke() = wakers.toList().also { wakers.clear() }.forEach(Waker::wake)
}

/** A handler that records each outcome it is offered and refuses with [refusals], in order, then accepts. */
private class Refusing(private val handler: HandlerHandle, refusals: List<SettleError>) : StreamHandler by handler, Wakeable by handler {
    private val left = ArrayDeque(refusals)
    val outcomes = mutableListOf<Result<ByteBuffer>>()

    override val catalog: CatalogRef get() = handler.catalog

    override fun settle(claim: ClaimId, outcome: Result<ByteBuffer>) {
        outcomes += outcome
        left.removeFirstOrNull()?.let { throw it }
    }
}

/** A handler that wakes its first [times] `ClaimStream` registrations at once, as a spurious wake may, and goes on storing them. */
private class AtOnce(private val handler: HandlerHandle, times: Int) : StreamHandler by handler, Wakeable by handler {
    val left = AtomicInteger(times)

    override val catalog: CatalogRef get() = handler.catalog

    override fun wakeOn(what: Interest, waker: Waker) {
        handler.wakeOn(what, waker)
        if (what is Interest.ClaimStream && left.getAndDecrement() > 0) waker.wake()
    }
}

/** The streams of a call driven by the provider's side and the caller's, over the loopback. */
class StreamDriveTest {
    private fun runtime() = Loopback(CatalogRef("drive", CatalogHash(ByteArray(CatalogHash.SIZE))))

    private fun call(rt: Loopback, max: Duration? = null) = call(rt.caller(), max)

    private fun <P> call(port: P, max: Duration? = null) where P : StreamCaller, P : Clock, P : Wakeable =
        StreamCall(port, Mutex(), IFACE, ORD, true, max) { ByteBuffer.allocate(0) }

    /** Hands every claim of [IFACE] to [provider], each in its own coroutine, as `serveAsync` does. */
    private fun CoroutineScope.serving(handler: HandlerHandle, provider: suspend (Claim) -> Unit): Job = launch(Dispatchers.Default) {
        handler.serve(IFACE, emptyList())
        while (true) {
            val claim = await(handler, Interest.Claim(IFACE)) { handler.nextClaim(ByteBuffer.allocate(64)) }
            launch { provider(claim) }
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun running(block: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.Default) { withTimeout(BOUND * 2) { block() } }

    @Test
    fun `a forget stops a source suspended between its elements, and its slot comes back`() = running {
        val rt = runtime()
        val handler = rt.handler()
        val stopped = AtomicInteger()
        val server = serving(handler) { claim ->
            driveReply(
                handler,
                claim.id,
                CHUNKS,
                flow {
                    try {
                        emit(bytes(1))
                        awaitCancellation()
                    } finally {
                        stopped.incrementAndGet()
                    }
                },
            )
        }
        val calls = Loopback.SLOTS * 2
        repeat(calls) { call(rt).reply(CHUNKS).first() }
        while (stopped.get() < calls) delay(1)
        server.cancelAndJoin()
    }

    @Test
    fun `a quiet source is not stopped while its caller reads`() = running {
        val rt = runtime()
        val handler = rt.handler()
        val count = Loopback.STREAM_WINDOW * 3
        val server = serving(handler) { claim ->
            driveReply(
                handler,
                claim.id,
                CHUNKS,
                flow {
                    repeat(count) {
                        emit(bytes(it))
                        delay(2)
                    }
                },
            )
        }
        assertEquals(List(count) { it.toByte() }, call(rt).reply(CHUNKS).toList().map { it.single() })
        server.cancelAndJoin()
    }

    @Test
    fun `a registration woken at once on an open stream does not stop it`() = running {
        val rt = runtime()
        val handler = rt.handler()
        val spurious = AtOnce(handler, Loopback.STREAM_WINDOW)
        val first = CompletableDeferred<Unit>()
        val server = serving(handler) { claim ->
            driveReply(
                spurious,
                claim.id,
                CHUNKS,
                flow {
                    first.await()
                    emit(bytes(9))
                },
            )
        }
        val reply = async { call(rt).reply(CHUNKS).toList().map { it.single() } }
        while (spurious.left.get() > 0) delay(1)
        first.complete(Unit)
        assertEquals(listOf<Byte>(9), reply.await())
        server.cancelAndJoin()
    }

    @Test
    fun `an empty chunk carries no value`() = running {
        val rt = runtime()
        val handler = rt.handler()
        val server = serving(handler) { claim ->
            handler.pushClaim(claim.id, REPLY_STREAM, ByteBuffer.allocate(0))
            handler.pushClaim(claim.id, REPLY_STREAM, ByteBuffer.wrap(bytes(5)))
            handler.endClaim(claim.id, REPLY_STREAM)
        }
        assertEquals(listOf<Byte>(5), call(rt).reply(CHUNKS).toList().map { it.single() })
        server.cancelAndJoin()
    }

    @Test
    fun `an early reply stops a suspended upload and is returned`() = running {
        val rt = runtime()
        val handler = rt.handler()
        val stopped = CompletableDeferred<Unit>()
        val server = serving(handler) { claim ->
            settleUpload(handler, claim.id) {
                claimStream(handler, claim.id, CHUNKS).first()
                ByteBuffer.wrap(bytes(7))
            }
        }
        val upload: Flow<ByteArray> = flow {
            try {
                emit(bytes(1))
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        assertEquals(7.toByte(), call(rt).uploadQuery(CHUNKS, upload, 1) { it.get() })
        stopped.await()
        server.cancelAndJoin()
    }

    /** Makes [calls] upload queries, each answered with two bytes, and returns what each threw. */
    private suspend fun CoroutineScope.refused(rt: Loopback, calls: Int, before: () -> Unit = {}): List<Throwable> {
        val handler = rt.handler()
        val server = serving(handler) { claim ->
            settleUpload(handler, claim.id) {
                claimStream(handler, claim.id, CHUNKS).toList()
                ByteBuffer.wrap(bytes(1, 2))
            }
        }
        val thrown = List(calls) {
            before()
            try {
                error("a reply of ${call(rt).uploadQuery(CHUNKS, flowOf(bytes(1)), 2) { it.get() }}")
            } catch (e: ClientError) {
                e
            }
        }
        server.cancelAndJoin()
        return thrown
    }

    @Test
    fun `a reply above the limit settles the call with Corrupt, and its slot comes back`() = running {
        val rt = runtime()
        rt.setLimit(IFACE, ORD, 1)
        val calls = Loopback.SLOTS * 2
        assertEquals(List(calls) { ClientError.Call(Transport.Corrupt) }, refused(rt, calls))
    }

    @Test
    fun `a reply refused as too large for any reason settles the call with Corrupt`() = running {
        val rt = runtime()
        val calls = Loopback.SLOTS * 2
        assertEquals(List(calls) { ClientError.Call(Transport.Corrupt) }, refused(rt, calls) { rt.failNextSettle() })
    }

    /** The outcomes `settleUpload` offers a handler that refuses with [refusals], in order. */
    private suspend fun offered(vararg refusals: SettleError, block: suspend () -> ByteBuffer): List<Result<ByteBuffer>> {
        val handler = Refusing(runtime().handler(), refusals.toList())
        settleUpload(handler, ClaimId(1), block)
        return handler.outcomes
    }

    @Test
    fun `a refused reply is settled again once, and not a third time`() = running {
        val tooLarge = SettleError.TooLarge(0)
        val reply = ByteBuffer.wrap(bytes(1))
        assertEquals(listOf(Result.success(reply), Result.failure(Transport.Corrupt)), offered(tooLarge, tooLarge, tooLarge) { reply })
    }

    @Test
    fun `a failed outcome refused as too large is left to the handler`() = running {
        val failure = Result.failure<ByteBuffer>(Contract.ContractBroken)
        assertEquals(listOf(failure), offered(SettleError.TooLarge(0)) { throw Contract.ContractBroken })
    }

    @Test
    fun `a reply refused for another reason is left to the handler`() = running {
        val reply = ByteBuffer.wrap(bytes(1))
        assertEquals(listOf(Result.success(reply)), offered(SettleError.UnknownClaim) { reply })
    }

    private fun CoroutineScope.outcome(reply: Flow<ByteArray>) = async {
        try {
            reply.toList()
            null
        } catch (e: ClientError) {
            e
        }
    }

    /** What the caller of [collect] reads when the provider drives the flow [source] gives it. */
    private suspend fun CoroutineScope.stoppedBy(
        collect: (Loopback) -> Flow<ByteArray> = { call(it).reply(CHUNKS) },
        source: (HandlerHandle, Claim) -> Flow<ByteArray>,
    ): ClientError? {
        val rt = runtime()
        val handler = rt.handler()
        val server = serving(handler) { claim -> driveReply(handler, claim.id, CHUNKS, source(handler, claim)) }
        val error = outcome(collect(rt)).await()
        server.cancelAndJoin()
        return error
    }

    @Test
    fun `a provider flow failing with a call error stops the stream with that error`() = running {
        val error = stoppedBy { _, _ -> flow { throw Contract.ContractBroken } }
        assertEquals(ClientError.Call(Contract.ContractBroken), error)
    }

    @Test
    fun `a provider flow failing with anything else stops the stream with Interrupted`() = running {
        val error = stoppedBy { _, _ -> flow { error("the provider failed") } }
        assertEquals(ClientError.Call(Transport.Interrupted), error)
    }

    @Test
    fun `a duplex flow that passes on a refused chunk stops the stream with that refusal`() = running {
        val refusing = object : StreamChunks<ByteArray> by CHUNKS {
            override fun decode(chunk: ByteBuffer): ByteArray = throw Transport.Corrupt
        }
        val error = stoppedBy({ call(it).duplex(CHUNKS, flowOf(bytes(1)), CHUNKS) }) { handler, claim ->
            claimStream(handler, claim.id, refusing)
        }
        assertEquals(ClientError.Call(Transport.Corrupt), error)
    }

    @Test
    fun `each collection of a reply stream has its own deadline`() = running {
        val rt = runtime()
        val handler = rt.handler()
        val server = serving(handler) { awaitCancellation() }
        val port = Poked(rt.caller())
        val reply = call(port, Duration(200_000)).reply(CHUNKS)
        val first = outcome(reply)
        delay(100)
        rt.advance(Duration(150_000))
        val second = outcome(reply)
        delay(100)
        rt.advance(Duration(100_000))
        port.poke()
        assertEquals(ClientError.Call(Transport.Timeout), first.await())
        second.cancelAndJoin()
        server.cancelAndJoin()
    }
}
