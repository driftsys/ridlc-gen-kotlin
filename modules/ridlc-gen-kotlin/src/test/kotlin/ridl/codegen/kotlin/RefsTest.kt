package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.codegen.kotlin.types.Refusal
import ridl.codegen.kotlin.types.declarationOf
import ridl.codegen.kotlin.types.isForeign
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.ForeignDeclaration
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Scope
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.TypeRef

/** How a type reference finds its declaration: a model fact the plugin cannot follow is a refusal, never an exception of the JVM's. */
class RefsTest {
    private fun declaration(name: String) = Declaration.newBuilder().setName(Spellings.newBuilder().setDeclared(name).setCamel(name)).build()

    /** Package `b`, which declares `Local` and reaches `a.Choice` and `a.Small`. */
    private val model = Model.newBuilder()
        .setScope(Scope.newBuilder().setPackage("b").addOthers("a").addOthers("b"))
        .addDeclarations(declaration("Local"))
        .addForeign(ForeignDeclaration.newBuilder().setPackage("a").setDeclaration(declaration("Choice")))
        .addForeign(ForeignDeclaration.newBuilder().setPackage("a").setDeclaration(declaration("Small")))
        .build()

    private fun ref(name: String, pkg: String, foreign: Boolean, index: Int) = TypeRef.newBuilder()
        .setReference(name).setResolved(true).setPackage(pkg).setForeign(foreign).setIndex(index).build()

    @Test
    fun `a local reference resolves in the package's own declarations`() {
        val local = ref("Local", "b", foreign = false, index = 0)
        assertFalse(model.isForeign(local))
        assertEquals("Local", model.declarationOf(local).name.declared)
    }

    @Test
    fun `a reference to another package resolves in the foreign table`() {
        val small = ref("Small", "a", foreign = true, index = 1)
        assertTrue(model.isForeign(small))
        assertEquals("Small", model.declarationOf(small).name.declared)
    }

    @Test
    fun `a reference whose foreign flag disagrees with its package is refused`() {
        val unmarked = assertThrows<Refusal> { model.declarationOf(ref("Small", "a", foreign = false, index = 1)) }
        assertEquals("the reference `Small` of package a is marked local in package b", unmarked.message)
        val marked = assertThrows<Refusal> { model.declarationOf(ref("Local", "b", foreign = true, index = 0)) }
        assertEquals("the reference `Local` of package b is marked foreign in package b", marked.message)
    }

    @Test
    fun `a reference past its table is refused, naming the reference, the table and its size`() {
        val refusal = assertThrows<Refusal> { model.declarationOf(ref("Gone", "b", foreign = false, index = 1)) }
        assertEquals("the reference `Gone` resolves to index 1 of package b's 1 local declarations", refusal.message)
        val foreign = assertThrows<Refusal> { model.declarationOf(ref("Gone", "a", foreign = true, index = 2)) }
        assertEquals("the reference `Gone` resolves to index 2 of package b's 2 foreign declarations", foreign.message)
    }

    @Test
    fun `a reference into the foreign table that lands in another package is refused`() {
        val stray = Model.newBuilder(model).addForeign(ForeignDeclaration.newBuilder().setPackage("c").setDeclaration(declaration("Other"))).build()
        val refusal = assertThrows<Refusal> { stray.declarationOf(ref("Other", "a", foreign = true, index = 2)) }
        assertEquals("the reference `Other` of package a resolves to a declaration of package c", refusal.message)
    }
}
