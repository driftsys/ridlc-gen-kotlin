// The face of kt.values.Probe over the loopback, through its two clients: what
// cabin cannot show — an ensure clause that fails, a float clause, a signal's
// own `= value` init, an event over an enum — and every way a settled call
// fails reaching the client as `ClientError.Call`. Compiled with the
// generated kt/values sources.
@file:JvmName("ValuesFaceProbe")

package ridl.conformance.probe.faces.values

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kt.values.Even
import kt.values.EvenCodec
import kt.values.Mode
import kt.values.Offset
import kt.values.Probe
import kt.values.ProbeAsyncClient
import kt.values.ProbeClient
import kt.values.ProbeOffset
import kt.values.ProbeProvider
import kt.values.ProbePublisher
import ridl.rt.error.CallError
import ridl.rt.error.ClientError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.loopback.Loopback
import ridl.rt.payload.Rule
import ridl.rt.payload.Violation
import ridl.rt.port.SendError
import ridl.rt.sample.Provenance
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val failures = mutableListOf<String>()

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private class Halver : ProbeProvider {
    val bumps = mutableListOf<Even>()

    override fun bump(by: Even) {
        synchronized(this) { bumps += by }
    }

    // Past 8 it answers its argument, which breaks `ensure result < 8`.
    override fun half(of: Even): Even = if (of.value >= 8) of else Even.of(of.value / 2)

    override fun scale(by: Offset): Offset = Offset.of(by.value / 2)
}

private fun refusedBy(label: String, block: () -> Unit) {
    try {
        block()
        failures += "$label: sent"
    } catch (e: ClientError.Send) {
        expectEqual(label, SendError.Contract(Contract.PreconditionFailed), e.error)
    }
}

private fun failedWith(label: String, expected: CallError, block: () -> Unit) {
    try {
        block()
        failures += "$label: returned"
    } catch (e: ClientError.Call) {
        expectEqual(label, expected, e.error)
    }
}

fun probe(): List<String> {
    blocking()
    async()
    return failures
}

/** The blocking client, with `serve` on a thread of its own. */
private fun blocking() {
    val rt = Loopback(Probe.catalog)
    val handler = rt.handler()
    val provider = Halver()
    // serve returns at each short timeout, so the flag stops it within one.
    val running = AtomicBoolean(true)
    val serving = thread { while (running.get()) Probe.serve(handler, provider, 100.milliseconds) }
    val client = ProbeClient(rt, timeout = 5.seconds)

    expectEqual("the channel init is the signal's own `= 1.5`", Offset.of(1.5), ProbeOffset.init())
    client.offset().let {
        expectEqual("an unpublished signal reads its own init", Offset.of(1.5), it.value)
        expectEqual("and is Init", Provenance.Init, it.provenance)
    }
    ProbePublisher(rt).let { it.offset(Offset.of(-2.25)); it.commit() }
    expectEqual("a float signal round-trips", Offset.of(-2.25), client.offset().value)

    client.subscribeMoved()
    ProbePublisher(rt).moved(Mode.RUN)
    expectEqual("an enum event round-trips", Result.success(Mode.RUN), (client.nextEvent() as? Probe.Event.Moved)?.occurrence?.payload)

    refusedBy("`require by != 0` refuses 0") { client.bump(Even.of(0)) }
    refusedBy("`require by >= -4.5` refuses -5.0") { client.scale(Offset.of(-5.0)) }
    client.bump(Even.of(4))
    expectEqual("a reply within `ensure result < 8` is returned", Even.of(3), client.half(Even.of(6)))
    failedWith("a reply that breaks `ensure` is ContractBroken", Contract.ContractBroken) { client.half(Even.of(10)) }
    expectEqual("a float reply is returned", Offset.of(-2.25), client.scale(Offset.of(-4.5)))
    expectEqual("the provider saw the command", listOf(Even.of(4)), synchronized(provider) { provider.bumps.toList() })

    running.set(false)
    serving.join()
}

private fun even(value: Long): ByteBuffer =
    ByteBuffer.allocate(EvenCodec.maxSize).also { EvenCodec.encode(Even.of(value), it) }.flip()

/** [buffer]'s bytes with the last byte equal to [from] replaced by [to]: a value no constructor admits. */
private fun patched(buffer: ByteBuffer, from: Int, to: Int): ByteBuffer {
    val array = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    val at = array.indexOfLast { it == from.toByte() }
    check(at >= 0) { "no byte $from to patch" }
    array[at] = to.toByte()
    return ByteBuffer.wrap(array)
}

/** The async client, with `serveAsync`, and each settlement a provider could send reaching it. */
private fun async() = runBlocking {
    withTimeout(10_000) {
        Loopback(Probe.catalog).let { rt ->
            val client = ProbeAsyncClient(rt)
            ProbePublisher(rt).let { it.offset(Offset.of(0.75)); it.commit() }
            expectEqual("an async client's signal reads", Offset.of(0.75), client.offset().value)

            val provider = Halver()
            val serving = launch(Dispatchers.Default) { Probe.serveAsync(rt.handler(), provider) }
            client.bump(Even.of(2))
            expectEqual("an async reply is returned", Even.of(3), client.half(Even.of(6)))
            try {
                client.half(Even.of(10))
                failures += "an async reply that breaks `ensure`: returned"
            } catch (e: ClientError.Call) {
                expectEqual("an async reply that breaks `ensure` is ContractBroken", Contract.ContractBroken, e.error)
            }
            serving.cancelAndJoin()
            expectEqual("the provider saw the async command", listOf(Even.of(2)), synchronized(provider) { provider.bumps.toList() })
        }

        // A query settled by hand with each outcome a provider or a runtime can send.
        Loopback(Probe.catalog).let { rt ->
            val client = ProbeAsyncClient(rt)
            val handler = rt.handler()
            suspend fun settledWith(outcome: Result<ByteBuffer>): Result<Even> {
                val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.half(Even.of(2)) } }
                val claim = checkNotNull(handler.nextClaim(ByteBuffer.allocate(Probe.MAX_BUFFER_SIZE))) { "the query was not sent" }
                handler.settle(claim.id, outcome)
                return call.await()
            }
            expectEqual("a reply settled by hand is returned", Result.success(Even.of(5)), settledWith(Result.success(even(5))))
            for ((label, outcome, expected) in listOf(
                Triple("a corrupt reply is Call(Corrupt)", Result.success(ByteBuffer.wrap(byteArrayOf(0x7F, 0, 0, 0, 1))), Transport.Corrupt),
                Triple(
                    "a reply outside its constraint is Call(InvalidValue)",
                    Result.success(patched(even(10), 10, 11)),
                    Contract.InvalidValue(Violation("Even", Rule.Range)),
                ),
                Triple("a provider's failed require is Call(PreconditionFailed)", Result.failure(Contract.PreconditionFailed), Contract.PreconditionFailed),
                Triple("an unknown interaction is Call(UnknownInteraction)", Result.failure(Contract.UnknownInteraction), Contract.UnknownInteraction),
            )) {
                val error = settledWith(outcome).exceptionOrNull()
                expectEqual(label, expected, (error as? ClientError.Call)?.error)
            }
        }
    }
}
