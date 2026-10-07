package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.Inits
import ridl.codegen.kotlin.types.Wires
import ridl.codegen.v1.ModelOuterClass.DeclKind
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Enum
import ridl.codegen.v1.ModelOuterClass.EnumValue
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.Init
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef

/** The init values driftsys/ridl#654 changed: the Rust backend's `defaults.rs`, case for case. */
class InitsTest {
    private fun spelled(name: String) = Spellings.newBuilder().setDeclared(name)
        .setCamel(name.replaceFirstChar(Char::uppercaseChar)).setSnake(name).build()

    private val level = Declaration.newBuilder().setName(spelled("Level")).setScalar(
        Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8).setVacuous(true),
    ).setInit(Init.newBuilder().setDerivable(true).setValue("0")).build()

    private val health = Declaration.newBuilder().setName(spelled("Health")).setEnum(
        Enum.newBuilder().setInitMember(0)
            .addValues(EnumValue.newBuilder().setName(spelled("ok")).setValue(0))
            .addValues(EnumValue.newBuilder().setName(spelled("warn")).setValue(4))
            .addValues(EnumValue.newBuilder().setName(spelled("fail")).setValue(9)),
    ).build()

    private fun named(index: Int, kind: DeclKind, optional: Boolean = false) = Type.newBuilder().setOptional(optional).setNamed(
        TypeRef.newBuilder().setReference(if (index == 0) "Level" else "Health").setResolved(true).setIndex(index).setKind(kind),
    ).build()

    /** The init of a struct with [fields], whose two other declarations are `Level` and `Health`. */
    private fun init(vararg fields: Field.Builder): String {
        val record = Declaration.newBuilder().setName(spelled("Record"))
            .setStruct(Struct.newBuilder().addAllSlots(fields.map { Slot.newBuilder().setField(it).build() })).build()
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .addDeclarations(level).addDeclarations(health).addDeclarations(record).build()
        return Inits(model, "kt.demo", Wires(model, "kt.demo")).ofDeclaration("kt.demo", record, foreign = false).toString()
    }

    @Test
    fun `an optional field with a declared init holds the value, not absence`() {
        val field = Field.newBuilder().setName(spelled("level")).setType(named(0, DeclKind.DECL_KIND_SCALAR, optional = true))
        assertEquals("kt.demo.Record(level = null)", init(field))
        assertEquals("kt.demo.Record(level = kt.demo.Level(7L))", init(field.setDeclaredInit("7")))
    }

    @Test
    fun `an enum field's declared init selects the member by value`() {
        val field = Field.newBuilder().setName(spelled("health")).setType(named(1, DeclKind.DECL_KIND_ENUM))
        assertEquals("kt.demo.Record(health = kt.demo.Health.ok)", init(field))
        assertEquals("kt.demo.Record(health = kt.demo.Health.fail)", init(field.setDeclaredInit("9")))
        assertEquals("kt.demo.Record(health = kt.demo.Health.warn)", init(field.setDeclaredInit("4").setType(named(1, DeclKind.DECL_KIND_ENUM, optional = true))))
    }

    @Test
    fun `a bytes init is the UTF-8 encoding of its text`() {
        val bytes = Type.newBuilder().setInline(Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_BYTES).setVacuous(true))
        val field = Field.newBuilder().setName(spelled("blob")).setType(bytes)
            .setInit(Init.newBuilder().setDerivable(true).setOneLevel(true).setValue("é!"))
        assertEquals("kt.demo.Record(blob = \"é!\".encodeToByteArray())", init(field))
    }
}
