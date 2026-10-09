package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.TypesEmitter
import ridl.codegen.v1.ModelOuterClass.ArrayType
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.MapType
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.StreamType
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.TupleCollision
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef

/** What `Types.kt` refuses, and where it puts the file: the facts the corpus does not reach. */
class TypesEmitterTest {
    private val options = Options("kt.demo", WireEncoding.FlatBuffers)

    private fun spelled(name: String) = Spellings.newBuilder().setDeclared(name).setCamel(name.replaceFirstChar(Char::uppercaseChar)).build()

    private fun struct(name: String, vararg fields: Pair<String, Type>): Declaration =
        Declaration.newBuilder().setName(spelled(name)).setStruct(
            Struct.newBuilder().addAllSlots(
                fields.mapIndexed { i, (field, type) ->
                    Slot.newBuilder().setOrdinal(i + 1).setField(Field.newBuilder().setName(spelled(field)).setType(type)).build()
                },
            ),
        ).build()

    private fun model(vararg declarations: Declaration): Model.Builder =
        Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addAllDeclarations(declarations.toList())

    @Test
    fun `the file is Types kt under the kotlin-package`() {
        val emitted = TypesEmitter(model(struct("Empty")).build(), options).emit()
        assertEquals("kt/demo/Types.kt", emitted.path)
        assertTrue(emitted.text!!.contains("package kt.demo"))
    }

    @Test
    fun `a stream is refused, naming the declaration and the story`() {
        val stream = Type.newBuilder().setStream(StreamType.getDefaultInstance()).build()
        val emitted = TypesEmitter(model(struct("Feed", "items" to stream)).build(), options).emit()
        assertNull(emitted.text)
        assertEquals(1, emitted.errors.size)
        assertTrue("kt.demo.Feed" in emitted.errors[0] && "O-K5" in emitted.errors[0], emitted.errors[0])
    }

    @Test
    fun `an unresolved reference is refused, even inside a collection`() {
        val unresolved = Type.newBuilder().setNamed(TypeRef.newBuilder().setReference("Ghost").setResolved(false)).build()
        val array = Type.newBuilder().setArray(ArrayType.newBuilder().setElement(unresolved).setMax(2)).build()
        val emitted = TypesEmitter(model(struct("Haunted", "ghosts" to array)).build(), options).emit()
        assertTrue(emitted.errors.single().contains("`Ghost`"), emitted.errors.toString())
    }

    @Test
    fun `every refusal is reported, not only the first`() {
        val stream = Type.newBuilder().setStream(StreamType.getDefaultInstance()).build()
        val emitted = TypesEmitter(model(struct("A", "s" to stream), struct("B", "s" to stream)).build(), options).emit()
        assertEquals(2, emitted.errors.size)
    }

    @Test
    fun `a bare bytes map key is compared by content, as verify compares it`() {
        // #50: ridl gives a `bytes` key as an inline type today, but the model
        // allows a bare one, whose two keys of one content `verify` refuses.
        fun primitive(p: PrimitiveType) = Type.newBuilder().setPrimitive(p).build()
        val map = Type.newBuilder().setMap(
            MapType.newBuilder()
                .setKey(primitive(PrimitiveType.PRIMITIVE_TYPE_BYTES))
                .setValue(primitive(PrimitiveType.PRIMITIVE_TYPE_BOOLEAN))
                .setMax(4),
        ).build()
        val text = checkNotNull(TypesEmitter(model(struct("Flags", "flags" to map)).build(), options).emit().text)
        assertTrue("keys0[j0].contentEquals(keys0[i0])" in text, text)
        assertTrue("Rule.Unique" in text, text)
    }

    @Test
    fun `two tuples spelling one name are refused`() {
        val collision = TupleCollision.newBuilder().setName("PairRange").build()
        val emitted = TypesEmitter(model().addTupleCollisions(collision).build(), options).emit()
        assertTrue(emitted.errors.single().contains("`PairRange`"))
    }

    @Test
    fun `a refused package is answered with no file and every diagnostic an error`() {
        val stream = Type.newBuilder().setStream(StreamType.getDefaultInstance()).build()
        val request = ridl.codegen.v1.Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA)
            .setModel(model(struct("Feed", "items" to stream))).build()
        val response = Generator.generate(request)
        assertEquals(0, response.filesCount)
        assertEquals(ridl.codegen.v1.Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR, response.getDiagnostics(0).severity)
    }
}
