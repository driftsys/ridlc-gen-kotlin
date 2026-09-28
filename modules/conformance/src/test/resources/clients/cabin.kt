@file:JvmName("CabinClientsProbe")

package ridl.conformance.probe.clients.cabin

import ridl.rt.contract.Ordinal
import ridl.rt.error.ClientError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.loopback.Loopback
import ridl.rt.port.SendError
import ridl.rt.sample.Duration
import ridl.rt.task.noopWaker
import veh.cabin.Average
import veh.cabin.Cabin
import veh.cabin.CabinAverageCall
import veh.cabin.CabinProvider
import veh.cabin.CabinSetLevelCall
import veh.cabin.Level
import veh.cabin.Window
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private inline fun <reified E : Throwable> expectThrows(label: String, block: () -> Unit): E? = try {
    block()
    failures += "$label: nothing was thrown"
    null
} catch (e: Throwable) {
    if (e is E) e else { failures += "$label: threw $e"; null }
}

/** Commands [rt] accepts before `SendError.Busy`, sent raw; the table holds `Loopback.SLOTS`. */
private fun sendsUntilBusy(rt: Loopback): Int {
    var sent = 0
    while (true) {
        try { rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0)) } catch (_: SendError.Busy) { return sent }
        sent += 1
    }
}

/** Fills the table with settled calls nobody forgot, and returns their correlations. */
private fun fill(rt: Loopback) = List(Loopback.SLOTS) {
    val c = rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0))
    rt.settle(rt.nextClaim(ByteBuffer.allocate(64))!!.id, Result.success(ByteBuffer.allocate(0)))
    c
}

private class Recorder : CabinProvider {
    val levels = mutableListOf<Level>()
    override fun setLevel(level: Level) { levels += level }
    override fun average(window: Window): Average = Average.of(250)
}

private val waker = noopWaker()

fun probe(): List<String> {
    callObjects()
    return failures
}

private fun callObjects() {
    // A require that fails sends nothing.
    Loopback(Cabin.catalog).let { rt ->
        val e = expectThrows<ClientError.Send>("a failing require throws Send") { CabinSetLevelCall(rt, Level.of(100)) }
        expectEqual("with PreconditionFailed", SendError.Contract(Contract.PreconditionFailed), e?.error)
        expectEqual("and nothing is sent", Loopback.SLOTS, sendsUntilBusy(rt))
    }
    // Busy, then a slot freed, then the call sent.
    Loopback(Cabin.catalog).let { rt ->
        val calls = fill(rt)
        val call = CabinSetLevelCall(rt, Level.of(1))
        expect("a full table leaves the call unsent", !call.sent())
        expectEqual("an unsent call waits", null, call.poll(waker))
        rt.forget(calls[0])
        expectEqual("the freed slot lets the poll send it", null, call.poll(waker))
        expect("and it is sent", call.sent())
    }
    // The deadline, unsent: setLevel's max is 50 ms.
    Loopback(Cabin.catalog).let { rt ->
        fill(rt)
        val call = CabinSetLevelCall(rt, Level.of(1))
        rt.advance(Duration(50_000))
        expectEqual("a call at exactly max is within it", null, call.poll(waker))
        rt.advance(Duration(1))
        val e = expectThrows<ClientError.Send>("an unsent call past its deadline throws Send") { call.poll(waker) }
        expectEqual("with Busy", SendError.Busy, e?.error)
    }
    // The deadline, sent: a command is Undelivered, a query Timeout, and the slot comes back.
    Loopback(Cabin.catalog).let { rt ->
        val command = CabinSetLevelCall(rt, Level.of(1))
        val query = CabinAverageCall(rt, Window.of(10))
        rt.advance(Duration(200_001))
        expectEqual("a sent command past its deadline", Transport.Undelivered,
            expectThrows<ClientError.Call>("a sent command past its deadline throws Call") { command.poll(waker) }?.error)
        expectEqual("a sent query past its deadline", Transport.Timeout,
            expectThrows<ClientError.Call>("a sent query past its deadline throws Call") { query.poll(waker) }?.error)
        expectEqual("both calls were forgotten", Loopback.SLOTS, sendsUntilBusy(rt))
    }
    // An outcome taken forgets the call, and a finished call polled again throws.
    Loopback(Cabin.catalog).let { rt ->
        val call = CabinAverageCall(rt, Window.of(10))
        val recorder = Recorder()
        Cabin.dispatch(rt, recorder, ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE))
        expectEqual("the reply is returned", Average.of(250), call.poll(waker))
        expectEqual("the call was forgotten", Loopback.SLOTS, sendsUntilBusy(rt))
        expectThrows<IllegalStateException>("a finished call polled again throws") { call.poll(waker) }
    }
    // cancel forgets a sent call once, and a cancelled call polled again throws.
    Loopback(Cabin.catalog).let { rt ->
        val call = CabinSetLevelCall(rt, Level.of(1))
        call.cancel()
        call.cancel()
        expectEqual("cancel forgot the call", Loopback.SLOTS, sendsUntilBusy(rt))
        expectThrows<IllegalStateException>("a cancelled call polled again throws") { call.poll(waker) }
    }
}
