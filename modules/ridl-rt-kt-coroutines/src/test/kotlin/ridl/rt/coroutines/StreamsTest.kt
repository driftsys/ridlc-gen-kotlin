package ridl.rt.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.ClientError
import ridl.rt.error.Transport
import ridl.rt.loopback.Loopback
import ridl.rt.port.Clock
import ridl.rt.port.Interest
import ridl.rt.port.Pull
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.StreamCaller
import ridl.rt.port.Wakeable
import ridl.rt.sample.Duration
import ridl.rt.task.Waker
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class StreamsTest {
    private val catalog = CatalogRef("streams", CatalogHash(ByteArray(32)))
    private val iface = InterfaceNo(1u)
    private val ord = Ordinal(1u)

    private fun call(rt: Loopback) = StreamCall(rt.caller(), Mutex(), iface, ord, true, null) { ByteBuffer.allocate(0) }

    @Test
    fun `forgetting a reply cancels a suspended source and releases its slot`() = runTest {
        val rt = Loopback(catalog)
        val handler = rt.handler().also { it.serve(iface, listOf(ord)) }
        repeat(Loopback.SLOTS * 2) {
            var stopped = false
            val reading = async { call(rt).reply(byteChunks(4)).first().toList() }
            runCurrent()
            val claim = checkNotNull(handler.nextClaim(ByteBuffer.allocate(0)))
            val serving = backgroundScope.launch {
                driveReply(handler, claim.id, byteChunks(4), flow {
                    try {
                        emit(byteArrayOf(7))
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                })
            }
            runCurrent()
            assertEquals(listOf<Byte>(7), reading.await())
            runCurrent()
            assertTrue(stopped, "forget must cancel the source without another emission")
            assertTrue(serving.isCompleted, "the claim task must settle and finish")
        }
    }

    @Test
    fun `a source that cancels itself interrupts its reply instead of ending successfully`() = runTest {
        val rt = Loopback(catalog)
        val handler = rt.handler().also { it.serve(iface, listOf(ord)) }
        val correlation = rt.query(iface, ord, ByteBuffer.allocate(0), null)
        val claim = checkNotNull(handler.nextClaim(ByteBuffer.allocate(0)))
        val serving = backgroundScope.launch {
            driveReply(handler, claim.id, byteChunks(4), flow {
                throw CancellationException("the source cancelled itself")
            })
        }
        runCurrent()
        assertEquals(Pull.Failed(Transport.Interrupted), rt.pull(correlation, REPLY_STREAM, ByteBuffer.allocate(4)))
        assertTrue(serving.isCancelled, "the provider task must propagate the cancellation")
        rt.forget(correlation)
    }

    @Test
    fun `an early query outcome cancels a suspended upload and returns the reply`() = runTest {
        val rt = Loopback(catalog)
        val handler = rt.handler().also { it.serve(iface, listOf(ord)) }
        var stopped = false
        val result = backgroundScope.async {
            call(rt).uploadQuery(byteChunks(4), flow {
                try {
                    emit(byteArrayOf(7))
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }, 1) { it.get().toInt() }
        }
        runCurrent()
        val claim = checkNotNull(handler.nextClaim(ByteBuffer.allocate(0)))
        assertEquals(listOf<Byte>(7), claimStream(handler, claim.id, byteChunks(4)).first().toList())
        settleUpload(handler, claim.id) { ByteBuffer.wrap(byteArrayOf(9)) }
        runCurrent()
        assertTrue(result.isCompleted, "the outcome must be read while the source is suspended")
        assertEquals(9, result.await())
        assertTrue(stopped, "the source must be cancelled on an early reply")
    }

    @Test
    fun `a refused upload settlement records an error and releases its slot`() = runTest {
        for (injected in listOf(false, true)) {
            val rt = Loopback(catalog)
            rt.setLimit(iface, ord, 1)
            val handler = rt.handler().also { it.serve(iface, listOf(ord)) }
            repeat(Loopback.SLOTS * 2) {
                val result = backgroundScope.async {
                    try {
                        call(rt).uploadQuery(byteChunks(1), flowOf(byteArrayOf(7)), 2) { it.get().toInt() }
                        null
                    } catch (e: ClientError) {
                        e
                    }
                }
                runCurrent()
                val claim = checkNotNull(handler.nextClaim(ByteBuffer.allocate(0)))
                if (injected) rt.failNextSettle()
                settleUpload(handler, claim.id) { ByteBuffer.wrap(ByteArray(if (injected) 1 else 2)) }
                runCurrent()
                assertTrue(result.isCompleted, "a refused settlement must still produce an outcome")
                assertEquals(ClientError.Call(Transport.Interrupted), result.await())
            }
        }
    }

    @Test
    fun `concurrent collections retain separate first chunk deadlines`() = runTest {
        val rt = Loopback(catalog)
        val caller = rt.caller()
        val wakers = mutableMapOf<Interest, Waker>()
        val port = object : StreamCaller by caller, Clock by caller, Wakeable {
            override fun wakeOn(what: Interest, waker: Waker) {
                wakers[what] = waker
                caller.wakeOn(what, waker)
            }
        }
        val reply = StreamCall(port, Mutex(), iface, ord, true, Duration(200_000)) { ByteBuffer.allocate(0) }
            .reply(byteChunks(4))
        suspend fun collect(): ClientError? = try {
            reply.first()
            null
        } catch (e: ClientError) {
            e
        }
        val first = backgroundScope.async { collect() }
        runCurrent()
        rt.advance(Duration(150_000))
        val second = backgroundScope.async { collect() }
        runCurrent()
        rt.advance(Duration(100_000))
        wakers.filterKeys { it is Interest.Stream }.values.toList().forEach { it.wake() }
        runCurrent()
        assertTrue(first.isCompleted, "the first collection's 200 ms bound has expired")
        assertEquals(ClientError.Call(Transport.Timeout), first.await())
        assertFalse(second.isCompleted, "the second collection still has 100 ms")
    }
}
