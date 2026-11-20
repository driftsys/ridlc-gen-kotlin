package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.Proto3CodecEmitter
import ridl.codegen.v1.ModelOuterClass.AbsentCause
import ridl.codegen.v1.ModelOuterClass.SizeAbsent
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Proto3Field
import ridl.codegen.v1.ModelOuterClass.Proto3Message
import ridl.codegen.v1.ModelOuterClass.Proto3Projection
import ridl.codegen.v1.ModelOuterClass.Proto3RefusalCause
import ridl.codegen.v1.ModelOuterClass.Proto3Refused
import ridl.codegen.v1.ModelOuterClass.Proto3Scalar
import ridl.codegen.v1.ModelOuterClass.Proto3ScalarType
import ridl.codegen.v1.ModelOuterClass.Proto3Wire
import ridl.codegen.v1.ModelOuterClass.Proto3WireType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Scope
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.Type

/**
 * `Proto3Codec.kt`'s refusals and its reading of the projection, over
 * hand-built models: what the corpus does not reach. `Proto3CodecTest` in
 * `modules/conformance` holds the generated code to the Rust codec.
 */
class Proto3CodecEmitterTest {
    private val options = Options("kt.demo", listOf(WireEncoding.Proto3))

    private fun spell(name: String) =
        Spellings.newBuilder().setDeclared(name).setSnake(name).setCamel(name.replaceFirstChar(Char::uppercaseChar))

    /** `Feed { level : integer [0..255]; ratio : float f32 }`, with its proto3 state [bound]. */
    private fun feed(bound: SizeState?): Declaration {
        val level = Field.newBuilder().setName(spell("level")).setType(
            Type.newBuilder().setInline(Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8).setVacuous(true)),
        )
        val ratio = Field.newBuilder().setName(spell("ratio")).setType(
            Type.newBuilder().setInline(Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_FLOAT).setFloatWidth(FloatWidth.FLOAT_WIDTH_F32).setVacuous(true)),
        )
        val declaration = Declaration.newBuilder().setName(spell("Feed"))
            .setStruct(Struct.newBuilder().addSlots(Slot.newBuilder().setOrdinal(1).setField(level)).addSlots(Slot.newBuilder().setOrdinal(3).setField(ratio)))
        if (bound != null) declaration.setProto3(bound)
        return declaration.build()
    }

    private fun scalar(type: Proto3ScalarType, wire: Proto3WireType) =
        Proto3Wire.newBuilder().setDefaultIsLegal(true).setScalar(Proto3Scalar.newBuilder().setType(type).setWireType(wire))

    /** `Feed`'s message: `level` a uint32 varint at 1, `ratio` a float of wire type [ratioWire] at 3. */
    private fun message(ratioWire: Proto3WireType = Proto3WireType.PROTO3_WIRE_TYPE_I32): Proto3Message.Builder {
        val level = scalar(Proto3ScalarType.PROTO3_SCALAR_TYPE_UINT32, Proto3WireType.PROTO3_WIRE_TYPE_VARINT)
        val ratio = scalar(Proto3ScalarType.PROTO3_SCALAR_TYPE_FLOAT, ratioWire)
        return Proto3Message.newBuilder().setName("Feed").setStructDeclaration(0)
            .addFields(Proto3Field.newBuilder().setNumber(1).setField("level").setWire(level))
            .addFields(Proto3Field.newBuilder().setNumber(3).setField("ratio").setWire(ratio))
    }

    private fun model(declaration: Declaration, projection: Proto3Projection.Builder?): Model {
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).setScope(Scope.newBuilder().setPackage("kt.demo"))
            .addDeclarations(declaration)
        if (projection != null) model.setProto3(projection)
        return model.build()
    }

    private val bounded: SizeState = SizeState.newBuilder().setBounded(16).build()

    @Test
    fun `each field takes the number and wire type the projection gives it`() {
        val emitted = Proto3CodecEmitter(model(feed(bounded), Proto3Projection.newBuilder().addMessages(message())), options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        val text = checkNotNull(emitted.text)
        // PROTO3_WIRE_TYPE_VARINT is 1 and PROTO3_WIRE_TYPE_I32 is 4: each is mapped by name, to tag bits 0 and 5.
        assertTrue("w.tag(1u, WireType.Varint)" in text, text)
        assertTrue("w.tag(3u, WireType.I32)" in text, text)
        assertTrue("object FeedProto3Codec : Payload<Feed, Verified>" in text, text)
        assertTrue("override val maxSize: Int = 16" in text, text)
    }

    @Test
    fun `internal declarations get internal codec objects`() {
        val declaration = feed(bounded).toBuilder().setVisibility(ridl.codegen.v1.ModelOuterClass.Visibility.VISIBILITY_INTERNAL).build()
        val emitted = Proto3CodecEmitter(model(declaration, Proto3Projection.newBuilder().addMessages(message())), options).emit()
        assertTrue("internal object FeedProto3Codec" in checkNotNull(emitted.text))
    }

    @Test
    fun `I64 and Len wire types are mapped by name`() {
        for ((kind, wire, name) in listOf(
            Triple(ScalarClass.SCALAR_CLASS_FLOAT, scalar(Proto3ScalarType.PROTO3_SCALAR_TYPE_DOUBLE, Proto3WireType.PROTO3_WIRE_TYPE_I64), "I64"),
            Triple(ScalarClass.SCALAR_CLASS_STRING, Proto3Wire.newBuilder().setText(true).setDefaultIsLegal(true), "Len"),
        )) {
            val declaration = feed(bounded).toBuilder()
            declaration.structBuilder.getSlotsBuilder(1).fieldBuilder.setType(
                Type.newBuilder().setInline(Scalar.newBuilder().setClass_(kind).setFloatWidth(FloatWidth.FLOAT_WIDTH_F64).setVacuous(true)),
            )
            val message = message().setFields(1, Proto3Field.newBuilder().setNumber(3).setField("ratio").setWire(wire))
            val emitted = Proto3CodecEmitter(model(declaration.build(), Proto3Projection.newBuilder().addMessages(message)), options).emit()
            assertEquals(emptyList<String>(), emitted.errors)
            assertTrue("w.tag(3u, WireType.$name)" in checkNotNull(emitted.text), emitted.text)
        }
    }

    @Test
    fun `unbounded size state has the Rust refusal text`() {
        val state = SizeState.newBuilder().setUnbounded(ridl.codegen.v1.ModelOuterClass.FbUnbounded.getDefaultInstance()).build()
        val emitted = Proto3CodecEmitter(model(feed(state), Proto3Projection.newBuilder().addMessages(message())), options).emit()
        assertEquals(listOf("ridlc-gen-kotlin: `kt.demo.Feed` has no proto3 codec: no finite bound exists for it"), emitted.errors)
    }

    @Test
    fun `unreachable tuples emit no helpers and their refusals are ignored`() {
        val tuple = ridl.codegen.v1.ModelOuterClass.InducedTuple.newBuilder()
            .setName(ridl.codegen.v1.ModelOuterClass.InducedName.newBuilder().setRust("Unused"))
        val projection = Proto3Projection.newBuilder().addMessages(message())
            .addMessages(Proto3Message.newBuilder().setName("Unused").setTuple(0))
            .addRefused(Proto3Refused.newBuilder().setTuple(0).setMessage("unused refusal"))
        val model = model(feed(bounded), projection).toBuilder().addTuples(tuple).build()
        val emitted = Proto3CodecEmitter(model, options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        assertTrue("Unused" !in checkNotNull(emitted.text), emitted.text)
    }

    @Test
    fun `a wire type the scalar cannot take is refused`() {
        val projection = Proto3Projection.newBuilder().addMessages(message(Proto3WireType.PROTO3_WIRE_TYPE_I64))
        val emitted = Proto3CodecEmitter(model(feed(bounded), projection), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf("ridlc-gen-kotlin: `kt.demo.Feed` has no proto3 codec: the proto3 projection gives a `PROTO3_SCALAR_TYPE_FLOAT` of `Feed` the wire type I64"),
            emitted.errors,
        )
    }

    @Test
    fun `Len in a scalar projection is named in the refusal`() {
        val projection = Proto3Projection.newBuilder().addMessages(message(Proto3WireType.PROTO3_WIRE_TYPE_LEN))
        val emitted = Proto3CodecEmitter(model(feed(bounded), projection), options).emit()
        assertEquals(
            listOf("ridlc-gen-kotlin: `kt.demo.Feed` has no proto3 codec: the proto3 projection gives a `PROTO3_SCALAR_TYPE_FLOAT` of `Feed` the wire type Len"),
            emitted.errors,
        )
    }

    @Test
    fun `a model with no projection is refused`() {
        val emitted = Proto3CodecEmitter(model(feed(bounded), null), options).emit()
        assertEquals(listOf("ridlc-gen-kotlin: the model carries no proto3 projection"), emitted.errors)
    }

    @Test
    fun `a refused declaration refuses the package, with the projection's words`() {
        val refused = Proto3Refused.newBuilder().setDeclaration(0).setCause(Proto3RefusalCause.PROTO3_REFUSAL_CAUSE_FIELD_NUMBER)
            .setMember("Feed.level").setMessage("`Feed.level` has field number 19000, which proto3 reserves or does not admit")
        val emitted = Proto3CodecEmitter(model(feed(bounded), Proto3Projection.newBuilder().addRefused(refused)), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf("`Feed.level` has field number 19000, which proto3 reserves or does not admit"),
            emitted.errors,
        )
    }

    @Test
    fun `a declaration whose proto3 state is not bounded is refused`() {
        val absent = SizeState.newBuilder().setAbsent(SizeAbsent.newBuilder().setCause(AbsentCause.ABSENT_CAUSE_NO_BOUND)).build()
        for ((state, cause) in listOf(
            absent to "a member of it has no finite bound (NoBound)",
            null to "the lowered model carries no proto3 size state for it",
            SizeState.newBuilder().setBounded(-1).build() to "has a proto3 bound of 4294967295 bytes",
        )) {
            val emitted = Proto3CodecEmitter(model(feed(state), Proto3Projection.newBuilder().addMessages(message())), options).emit()
            assertNull(emitted.text, cause)
            assertTrue(emitted.errors.single().contains(cause), emitted.errors.toString())
        }
    }
}
