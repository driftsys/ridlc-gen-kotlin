package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.TypesEmitter
import ridl.codegen.v1.ModelOuterClass.ArrayType
import ridl.codegen.v1.ModelOuterClass.Constraint
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.MapType
import ridl.codegen.v1.ModelOuterClass.Match
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.StreamType
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.TupleCollision
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef

/** What `Types.kt` refuses, and where it puts the file: the facts the corpus does not reach. */
class TypesEmitterTest {
    private val options = Options("kt.demo", listOf(WireEncoding.FlatBuffers))

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
        assertEquals(
            listOf("$PLUGIN: `kt.demo.Feed` cannot be generated: a stream is only a member's parameter or reply (O-K5, driftsys/ridl#336)"),
            emitted.errors,
        )
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

    /** An inline string of at most 8 scalar values, with one `match` clause per pattern. */
    private fun matching(vararg patterns: String): Type = Type.newBuilder().setInline(
        Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_STRING).setConstraint(
            Constraint.newBuilder().setLenMax(8).addAllMatches(patterns.map { Match.newBuilder().setPattern(it).build() }),
        ),
    ).build()

    @Test
    fun `every match clause is checked against the whole value, in source order`() {
        val emitted = TypesEmitter(model(struct("Token", "text" to matching("[a-z]+", ".*x.*"))).build(), options).emit()
        val text = emitted.text!!
        val first = text.indexOf("PATTERN_0.matches(this.text)")
        val second = text.indexOf("PATTERN_1.matches(this.text)")
        assertTrue(first in 0 until second, text)
        assertTrue("PATTERN_0: Regex = Regex(\"[a-z]+\")" in text, text)
        assertTrue("PATTERN_1: Regex = Regex(\".*x.*\")" in text, text)
        assertTrue("containsMatchIn" !in text, text)
    }

    @Test
    fun `a clause java util regex cannot compile refuses its declaration`() {
        val emitted = TypesEmitter(model(struct("Broken", "text" to matching("[a-z]+", "(a"))).build(), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf(
                "$PLUGIN: `kt.demo.Broken` cannot be generated: its pattern `(a` does not compile under java.util.regex: " +
                    "Unclosed group",
            ),
            emitted.errors,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy pattern fields refuse the request with an error and no files`() {
        for (constraint in listOf(
            Constraint.newBuilder().setPattern("[a-z]+").build(),
            Constraint.newBuilder().setPatternConst("OLD_PATTERN").build(),
        )) {
            val scalar = Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_STRING).setConstraint(constraint).build()
            val declarations = listOf(
                Declaration.newBuilder().setName(spelled("Legacy")).setScalar(scalar).build(),
                struct("Legacy", "text" to Type.newBuilder().setInline(scalar).build()),
            )
            for (declaration in declarations) {
                val input = model(declaration).build()
                val emitted = TypesEmitter(input, options).emit()
                assertNull(emitted.text)
                assertTrue(emitted.errors.single().contains("kt.demo.Legacy"), emitted.errors.toString())
                assertTrue(emitted.errors.single().contains("requires ridl editor-v0.21.0 or later"), emitted.errors.toString())
                val response = Generator.generate(ridl.codegen.v1.Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(input).build())
                assertEquals(0, response.filesCount)
                assertTrue(response.diagnosticsCount > 0)
                assertTrue(response.diagnosticsList.all { it.severity == ridl.codegen.v1.Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR })
            }
        }
    }

    @Test
    fun `a named string refuses a repetition bound Java cannot compile`() {
        val declaration = Declaration.newBuilder().setName(spelled("Broken"))
            .setScalar(matching("[a-z]+", "a{99999999999}").inline).build()
        val emitted = TypesEmitter(model(declaration).build(), options).emit()
        assertNull(emitted.text)
        assertTrue(emitted.errors.single().contains("a{99999999999}` does not compile under java.util.regex"), emitted.errors.toString())
    }

    @Test
    fun `unresolved clauses add notes but no pattern checks`() {
        for (resolved in listOf(true, false)) {
            val constraint = Constraint.newBuilder().addMatches(Match.newBuilder().setPatternConst("MISSING"))
            if (resolved) constraint.addMatches(Match.newBuilder().setPattern("[a-z]+"))
            val scalar = Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_STRING).setConstraint(constraint)
            val declaration = Declaration.newBuilder().setName(spelled("Token")).setScalar(scalar).build()
            val emitted = TypesEmitter(model(declaration).build(), options).emit()
            assertEquals(emptyList<String>(), emitted.errors)
            val text = emitted.text!!
            assertTrue("The pattern `MISSING` did not resolve, so it is not checked." in text, text)
            assertEquals(if (resolved) 1 else 0, Regex("private val PATTERN_").findAll(text).count(), text)
            assertEquals(if (resolved) 1 else 0, Regex("\\.matches\\(value\\)").findAll(text).count(), text)
        }
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
