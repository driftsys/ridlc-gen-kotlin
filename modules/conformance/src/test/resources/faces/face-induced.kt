// docs/design.md §7, "The face round-trips on the loopback", for the calls
// whose payload is a struct the lowering induced (driftsys/ridl#713): two
// parameters, none, and a tuple reply, each sent as one named type through the
// generated face over ridl-rt-kt-loopback. Compiled with the generated
// face/induced sources.
@file:JvmName("InducedFaceProbe")

package ridl.conformance.probe.faces.induced

import face.induced.Average
import face.induced.Level
import face.induced.Pump
import face.induced.PumpBothParams
import face.induced.PumpBothParamsCodec
import face.induced.PumpDrainParams
import face.induced.PumpDrainReply
import face.induced.PumpDrainReplyCodec
import face.induced.PumpPollClient
import face.induced.PumpProvider
import face.induced.PumpStatusParams
import face.induced.PumpStatusParamsCodec
import face.induced.Window
import face.induced.WindowCodec
import ridl.rt.contract.reservation
import ridl.rt.encoding.Encoding
import ridl.rt.loopback.Loopback
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private class Recorder : PumpProvider {
    val served = mutableListOf<Any>()

    override fun one(level: Level) {
        served += level
    }

    override fun both(args: PumpBothParams) {
        served += args
    }

    override fun status(args: PumpStatusParams): Average {
        served += args
        return Average.of(7)
    }

    override fun drain(args: PumpDrainParams): PumpDrainReply {
        served += args
        return PumpDrainReply(Level.of(3), Window.of(9))
    }
}

fun probe(): List<String> {
    val rt = Loopback(Pump.catalog)
    val client = PumpPollClient(rt)
    val provider = Recorder()
    val buffer = ByteBuffer.allocate(Pump.sizes(Encoding.FlatBuffers).maxBufferSize)

    // One named parameter is sent as it is; nothing is induced for it.
    val one = client.one(Level.of(5))
    expectEqual("dispatch settles `one`", 1, Pump.dispatch(rt, provider, buffer))
    expectEqual("`one` is acknowledged", Result.success(Unit), client.oneAck(one))

    // Two parameters: the request is the induced struct.
    val args = PumpBothParams(Level.of(42), Window.of(500))
    val both = client.both(args)
    expectEqual("dispatch settles `both`", 1, Pump.dispatch(rt, provider, buffer))
    expectEqual("`both` is acknowledged", Result.success(Unit), client.bothAck(both))

    // No parameter: the request is an induced struct with no field.
    val status = client.status(PumpStatusParams())
    expectEqual("dispatch settles `status`", 1, Pump.dispatch(rt, provider, buffer))
    expectEqual("`status` replies", Result.success(Average.of(7)), client.statusReply(status))

    // A tuple reply: the reply is an induced struct too.
    val drain = client.drain(PumpDrainParams())
    expectEqual("dispatch settles `drain`", 1, Pump.dispatch(rt, provider, buffer))
    expectEqual("`drain` replies", Result.success(PumpDrainReply(Level.of(3), Window.of(9))), client.drainReply(drain))

    expectEqual(
        "the provider is handed each argument as it was sent",
        listOf(Level.of(5), args, PumpStatusParams(), PumpDrainParams()),
        provider.served,
    )

    // The size table reserves each induced struct's own bound, as Rust's names its `MAX_SIZE`.
    expectEqual("`both` reserves its argument struct", PumpBothParamsCodec.maxSize.toULong(), reservation(face.induced.PumpBoth, Encoding.FlatBuffers))
    expectEqual(
        "`status` reserves its argument struct and its reply",
        PumpStatusParamsCodec.maxSize.toULong() + face.induced.AverageCodec.maxSize.toULong(),
        reservation(face.induced.PumpStatus, Encoding.FlatBuffers),
    )
    expectEqual(
        "`drain` reserves both induced structs",
        face.induced.PumpDrainParamsCodec.maxSize.toULong() + PumpDrainReplyCodec.maxSize.toULong(),
        reservation(face.induced.PumpDrain, Encoding.FlatBuffers),
    )
    // An induced struct's fields are the parameters, in order, as their own codecs bound them.
    expectEqual("the argument struct holds a window", true, PumpBothParamsCodec.maxSize > WindowCodec.maxSize)
    return failures.toList()
}
