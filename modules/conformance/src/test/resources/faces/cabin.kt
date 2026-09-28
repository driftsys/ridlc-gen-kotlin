// docs/design.md §7, "The face round-trips on the loopback": the cabin
// package's four interaction kinds through its generated face over
// ridl-rt-kt-loopback, and dispatch's settlement table. Raw calls are sent to
// the loopback's Caller directly, past the client's own `require`, so what
// dispatch checks is reached. Compiled with the generated veh/cabin sources.
@file:JvmName("CabinFaceProbe")

package ridl.conformance.probe.faces.cabin

import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.loopback.Loopback
import ridl.rt.payload.Rule
import ridl.rt.payload.Violation
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.sample.Cause
import ridl.rt.sample.Detection
import ridl.rt.sample.Provenance
import veh.cabin.Average
import veh.cabin.AverageCodec
import veh.cabin.Cabin
import veh.cabin.CabinPollClient
import veh.cabin.CabinProvider
import veh.cabin.CabinPublisher
import veh.cabin.Health
import veh.cabin.Horn
import veh.cabin.HornClient
import veh.cabin.HornPublisher
import veh.cabin.Level
import veh.cabin.LevelCodec
import veh.cabin.Temperature
import veh.cabin.Warning
import veh.cabin.WarningCodec
import veh.cabin.Window
import veh.cabin.WindowCodec
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private class Recorder : CabinProvider {
    val levels = mutableListOf<Level>()
    var reply: Average = Average.of(250)

    override fun setLevel(level: Level) {
        levels += level
    }

    override fun average(window: Window): Average = reply
}

private fun <T> bytes(codec: ridl.rt.payload.Payload<T, *>, value: T): ByteBuffer =
    ByteBuffer.allocate(codec.maxSize).also { codec.encode(value, it) }.flip()

/** [buffer]'s bytes with the first byte equal to [from] replaced by [to]: a value no constructor admits. */
private fun patched(buffer: ByteBuffer, from: Int, to: Int): ByteBuffer {
    val array = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    val at = array.indexOfLast { it == from.toByte() }
    check(at >= 0) { "no byte $from to patch" }
    array[at] = to.toByte()
    return ByteBuffer.wrap(array)
}

private val corrupt: ByteBuffer get() = ByteBuffer.wrap(byteArrayOf(0x7F, 0, 0, 0, 1, 2))

fun probe(): List<String> {
    val rt = Loopback(Cabin.catalog)
    val client = CabinPollClient(rt)
    val publisher = CabinPublisher(rt)
    val provider = Recorder()
    val buffer = ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE)

    // A signal: the init value before any publication, then set and read.
    client.temperature().let {
        expectEqual("an unpublished signal reads its init value", Temperature.of(0), it.value)
        expectEqual("an unpublished signal is Init", Provenance.Init, it.provenance)
    }
    publisher.temperature(Temperature.of(21))
    publisher.commit()
    client.temperature().let {
        expectEqual("a published signal reads back", Temperature.of(21), it.value)
        expectEqual("a published signal is Live", Provenance.Live, it.provenance)
    }
    publisher.invalidateTemperature()
    publisher.commit()
    client.temperature().let {
        expectEqual("an invalidated signal keeps its last good value", Temperature.of(21), it.value)
        expectEqual("an invalidated signal is Invalid(Declared)", Provenance.Invalid(Cause.Declared), it.provenance)
    }
    rt.set(Cabin.number, Ordinal(1u), corrupt)
    rt.commit()
    client.temperature().let {
        expectEqual("corrupt signal bytes read as the init value", Temperature.of(0), it.value)
        expectEqual("corrupt signal bytes are detected", Provenance.Invalid(Cause.Detected(Detection.Corrupt)), it.provenance)
    }
    // No bytes stand for the init value under Init or Invalid(Declared) alone
    // (frame §5.1); under any other provenance they are checked like any
    // bytes, as the Rust face does since driftsys/ridl#517.
    Loopback(Cabin.catalog).let { fresh ->
        CabinPublisher(fresh).run {
            invalidateTemperature()
            commit()
        }
        CabinPollClient(fresh).temperature().let {
            expectEqual("a signal invalidated before any publication reads its init value", Temperature.of(0), it.value)
            expectEqual("a signal invalidated before any publication is Invalid(Declared)", Provenance.Invalid(Cause.Declared), it.provenance)
        }
    }
    Loopback(Cabin.catalog).let { fresh ->
        fresh.set(Cabin.number, Ordinal(1u), ByteBuffer.allocate(0))
        fresh.commit()
        CabinPollClient(fresh).temperature().let {
            expectEqual("no live signal bytes read as the init value", Temperature.of(0), it.value)
            expectEqual("no live signal bytes are detected", Provenance.Invalid(Cause.Detected(Detection.Corrupt)), it.provenance)
        }
    }

    // An event: subscribed, raised, received; a corrupt and an invalid occurrence.
    expect("nothing is waiting before a subscription", client.nextEvent() == null)
    client.subscribeWarning()
    val warning = Warning(Level.of(7), Health.FAIL)
    publisher.warning(warning)
    when (val event = client.nextEvent()) {
        is Cabin.Event.Warning -> expectEqual("an event round-trips", Result.success(warning), event.occurrence.payload)
        null -> failures += "a raised event is received"
    }
    rt.raise(Cabin.number, Ordinal(2u), corrupt)
    (client.nextEvent() as? Cabin.Event.Warning).let {
        expectEqual("a corrupt occurrence is detected", Result.failure<Warning>(Detection.Corrupt), it?.occurrence?.payload)
    }
    rt.raise(Cabin.number, Ordinal(2u), patched(bytes(WarningCodec, Warning(Level.of(100), Health.OK)), 100, 101))
    (client.nextEvent() as? Cabin.Event.Warning).let {
        expectEqual(
            "an occurrence that breaks a constraint is detected",
            Result.failure<Warning>(Detection.InvalidValue(Violation("Level", Rule.Range))),
            it?.occurrence?.payload,
        )
    }

    // A command: sent, dispatched, acknowledged before the provider runs.
    val set = client.setLevel(Level.of(42))
    expect("a command's ack is unknown until dispatch", client.setLevelAck(set) == null)
    expectEqual("dispatch settles the command", 1, Cabin.dispatch(rt, provider, buffer))
    expectEqual("the provider served the command", listOf(Level.of(42)), provider.levels)
    expectEqual("the command is acknowledged", Result.success(Unit), client.setLevelAck(set))
    try {
        client.setLevel(Level.of(100))
        failures += "a failing require is refused by the client"
    } catch (e: SendError.Contract) {
        expectEqual("the client refuses with PreconditionFailed", Contract.PreconditionFailed, e.contract)
    }

    // A query: sent, dispatched, replied.
    val avg = client.average(Window.of(500))
    expectEqual("dispatch settles the query", 1, Cabin.dispatch(rt, provider, buffer))
    expectEqual("the reply is read", Result.success(Average.of(250)), client.averageReply(avg))

    // The settlement table, past the client.
    fun settled(label: String, expected: CallError, call: () -> ridl.rt.port.Correlation, isQuery: Boolean) {
        val c = call()
        expectEqual("$label: one settlement", 1, Cabin.dispatch(rt, provider, buffer))
        val outcome = if (isQuery) rt.reply(c, ByteBuffer.allocate(64))?.map { } else rt.ack(c)
        expectEqual(label, Result.failure<Unit>(expected), outcome)
    }
    val before = provider.levels.size
    settled("a failing require on the provider side", Contract.PreconditionFailed, {
        rt.command(Cabin.number, Ordinal(3u), bytes(LevelCodec, Level.of(100)))
    }, false)
    settled("a failing require of a query", Contract.PreconditionFailed, {
        rt.query(Cabin.number, Ordinal(4u), bytes(WindowCodec, Window.of(0)))
    }, true)
    settled("a corrupt argument buffer", Transport.Corrupt, { rt.command(Cabin.number, Ordinal(3u), corrupt) }, false)
    settled("an argument that breaks its constraint", Contract.InvalidValue(Violation("Level", Rule.Range)), {
        rt.command(Cabin.number, Ordinal(3u), patched(bytes(LevelCodec, Level.of(100)), 100, 101))
    }, false)
    settled("an unknown ordinal", Contract.UnknownInteraction, {
        rt.command(Cabin.number, Ordinal(9u), bytes(LevelCodec, Level.of(1)))
    }, false)
    settled("another interface's number", Contract.UnknownInteraction, {
        rt.command(InterfaceNo(7u), Ordinal(3u), bytes(LevelCodec, Level.of(1)))
    }, false)
    expectEqual("no refused call reached the provider", before, provider.levels.size)

    // A settlement the handler refuses is not counted; a short buffer consumes nothing.
    client.setLevel(Level.of(5))
    rt.failNextSettle()
    expectEqual("a refused settlement is not counted", 0, Cabin.dispatch(rt, provider, buffer))
    val waiting = client.setLevel(Level.of(6))
    expectEqual("a short buffer settles nothing", 0, Cabin.dispatch(rt, provider, ByteBuffer.allocate(1)))
    expectEqual("and the claim waits for the next dispatch", 1, Cabin.dispatch(rt, provider, buffer))
    expectEqual("which acknowledges it", Result.success(Unit), client.setLevelAck(waiting))

    // Horn: a second interface on the same runtime, a signal only.
    HornPublisher(rt).let { it.active(Health.WARN); it.commit() }
    expectEqual("a second interface's signal reads back", Health.WARN, HornClient(rt).active().value)
    expectEqual("its descriptor has its own number", InterfaceNo(2u), Horn.number)
    expect("the reply codec is the descriptor's", AverageCodec.maxSize == Cabin.members[3].payloads[1].maxSize.flatbuffers?.toInt())

    // dispatch lets the handler's read failure through: serve reports it.
    val failing = object : ridl.rt.port.Handler by rt {
        override fun nextClaim(out: ByteBuffer): ridl.rt.port.Claim? = throw ReadError.Detached
    }
    try {
        Cabin.dispatch(failing, provider, buffer)
        failures += "a read failure of the handler reaches dispatch's caller"
    } catch (_: ReadError.Detached) {
    }
    return failures
}
