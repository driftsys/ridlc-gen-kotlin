// The face of kt.values.Probe over the loopback: what cabin cannot show — an
// ensure clause that fails, a float clause, a signal's own `= value` init,
// and an event over an enum. Compiled with the generated kt/values sources.
@file:JvmName("ValuesFaceProbe")

package ridl.conformance.probe.faces.values

import kt.values.Even
import kt.values.Mode
import kt.values.Offset
import kt.values.Probe
import kt.values.ProbePollClient
import kt.values.ProbeOffset
import kt.values.ProbeProvider
import kt.values.ProbePublisher
import ridl.rt.error.Contract
import ridl.rt.loopback.Loopback
import ridl.rt.port.SendError
import ridl.rt.sample.Provenance
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private class Halver : ProbeProvider {
    val bumps = mutableListOf<Even>()

    override fun bump(by: Even) {
        bumps += by
    }

    // Past 8 it answers its argument, which breaks `ensure result < 8`.
    override fun half(of: Even): Even = if (of.value >= 8) of else Even.of(of.value / 2)

    override fun scale(by: Offset): Offset = Offset.of(by.value / 2)
}

private fun refusedBy(label: String, block: () -> Unit) {
    try {
        block()
        failures += "$label: sent"
    } catch (e: SendError.Contract) {
        expectEqual(label, Contract.PreconditionFailed, e.contract)
    }
}

fun probe(): List<String> {
    val rt = Loopback(Probe.catalog)
    val client = ProbePollClient(rt)
    val provider = Halver()
    val buffer = ByteBuffer.allocate(Probe.MAX_BUFFER_SIZE)

    expectEqual("the channel init is the signal's own `= 1.5`", Offset.of(1.5), ProbeOffset.init())
    client.offset().let {
        expectEqual("an unpublished signal reads its own init", Offset.of(1.5), it.value)
        expectEqual("and is Init", Provenance.Init, it.provenance)
    }
    ProbePublisher(rt).let { it.offset(Offset.of(-2.25)); it.commit() }
    expectEqual("a float signal round-trips", Offset.of(-2.25), client.offset().value)

    client.subscribeMoved()
    ProbePublisher(rt).moved(Mode.RUN)
    expectEqual("an enum event round-trips", Result.success(Mode.RUN), (client.nextEvent() as Probe.Event.Moved).occurrence.payload)

    refusedBy("`require by != 0` refuses 0") { client.bump(Even.of(0)) }
    val bumped = client.bump(Even.of(4))
    val passes = client.half(Even.of(6))
    val breaks = client.half(Even.of(10))
    refusedBy("`require by >= -4.5` refuses -5.0") { client.scale(Offset.of(-5.0)) }
    val scaled = client.scale(Offset.of(-4.5))
    expectEqual("dispatch settles four calls", 4, Probe.dispatch(rt, provider, buffer))
    expectEqual("the command is acknowledged", Result.success(Unit), client.bumpAck(bumped))
    expectEqual("a reply within `ensure result < 8` is read", Result.success(Even.of(3)), client.halfReply(passes))
    expectEqual("a reply that breaks `ensure` is ContractBroken", Result.failure<Even>(Contract.ContractBroken), client.halfReply(breaks))
    expectEqual("a float reply is read", Result.success(Offset.of(-2.25)), client.scaleReply(scaled))
    expectEqual("the provider saw the command", listOf(Even.of(4)), provider.bumps)
    return failures
}
