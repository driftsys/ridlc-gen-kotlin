// #38 and its review on #42: every map a struct's constructor accepts is one
// `verify` accepts, compiled with the generated kt/keys sources (MapKeysTest).
// `TwoMaps` holds two maps whose keys need the check, in one constructor;
// `NarrowKeys` keys its map with an inline float carried as f32, so two
// doubles one f32 rounds to are one key on the wire.
@file:JvmName("KeyCollisionsProbe")

package ridl.conformance.probe.keys

import kt.keys.NarrowKeys
import kt.keys.NarrowKeysCodec
import kt.keys.TwoMaps
import kt.keys.TwoMapsCodec
import kt.keys.Whole
import ridl.rt.payload.ConstraintViolation
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private fun refused(label: String, rule: Rule, block: () -> Any?) {
    try {
        block()
        failures += "$label: accepted"
    } catch (e: ConstraintViolation) {
        expectEqual("$label: the rule", rule, e.rule)
    }
}

private fun <T> verdict(codec: Payload<T, *>, value: T): Any? {
    val out = ByteBuffer.allocate(codec.maxSize)
    codec.encode(value, out)
    out.flip()
    return try {
        codec.verify(out)
        null
    } catch (e: VerifyError.Contract) {
        e.violation.rule
    } catch (e: VerifyError.Structure) {
        e.malformed
    }
}

fun probe(): List<String> {
    val one = Whole.of(1)
    refused("two maps in one struct: the float keys are checked", Rule.Unique) {
        TwoMaps(floats = mapOf(0.0 to one, -0.0 to one), blobs = emptyMap())
    }
    refused("two maps in one struct: the bytes keys are checked", Rule.Unique) {
        TwoMaps(floats = emptyMap(), blobs = mapOf(byteArrayOf(1) to one, byteArrayOf(1) to one))
    }
    expectEqual("two maps in one struct verify once encoded", null,
        verdict(TwoMapsCodec, TwoMaps(floats = mapOf(0.5 to one), blobs = mapOf(byteArrayOf(1) to one))))

    refused("two doubles one f32 rounds to are one key", Rule.Unique) {
        NarrowKeys(mapOf(1.0 to one, 1.0000000001 to one))
    }
    expectEqual("f32 keys that stay apart verify once encoded", null,
        verdict(NarrowKeysCodec, NarrowKeys(mapOf(1.0 to one, 1.25 to one))))
    return failures
}
