package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Options
import ridl.codegen.kotlin.Wire
import ridl.codegen.kotlin.WireEncoding
import ridl.codegen.kotlin.types.CodecEmitter
import ridl.codegen.kotlin.types.PodbufCodecEmitter
import ridl.codegen.kotlin.types.Proto3CodecEmitter
import ridl.codegen.v1.ModelOuterClass.Model
import java.nio.file.Path

/** Owner reporting is tested directly, before Generator combines the encodings. */
class CodecOwnersTest {
    @TempDir
    lateinit var work: Path

    private fun model(): Model = Wire.readRequest(Harness.capturedRequest("face-induced", work)).model

    private fun checkOwners(encoding: WireEncoding) {
        val original = model()
        // The empty status request still has a projected tuple, but no root bound.
        val status = original.tuplesList.indexOfFirst { it.name.rust == "PumpStatusParams" }
        assertTrue(status >= 0)
        val builder = original.toBuilder()
        val roots = original.flatbuffers.rootsList.filterNot { it.hasTuple() && it.tuple == status }
        builder.flatbuffersBuilder.clearRoots().addAllRoots(roots)
        for (face in builder.interfacesBuilderList) {
            for (slot in face.slotsBuilderList) {
                val interaction = slot.interactionBuilder
                if (interaction.hasQuery() && interaction.query.hasRequestTuple() && interaction.query.requestTuple.index == status) {
                    interaction.queryBuilder.clearRequestSizes()
                }
            }
        }
        val model = builder.build()
        val options = Options("face.induced", listOf(encoding))
        val emitted = when (encoding) {
            WireEncoding.FlatBuffers -> CodecEmitter(model, options).emit()
            WireEncoding.Proto3 -> Proto3CodecEmitter(model, options).emit()
            WireEncoding.Podbuf -> PodbufCodecEmitter(model, options).emit()
        }
        assertEquals(emptyList<String>(), emitted.errors)
        val expected = setOf("Level", "Window", "Average", "Nested", "PumpBothParams", "PumpDrainParams", "PumpDrainReply")
        assertEquals(expected, emitted.owners.map { it.name }.toSet())
        assertEquals(expected.size, emitted.owners.size)
        // Both inline tuples remain helper types, including the array element.
        assertTrue(model.tuplesList.count { it.name.rust.startsWith("Nested") } >= 2)
    }

    @Test
    fun `flatbuffers reports declaration and bounded call owners only`() = checkOwners(WireEncoding.FlatBuffers)

    @Test
    fun `proto3 reports declaration and bounded call owners only`() = checkOwners(WireEncoding.Proto3)

    @Test
    fun `podbuf reports declaration and bounded call owners only`() = checkOwners(WireEncoding.Podbuf)
}
