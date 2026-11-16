package ridl.rt

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.rt.contract.DebugIdentity
import ridl.rt.contract.InstanceNo
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * `crates/ridl-rt/tests/instance_no.rs` of the pinned release, case for case,
 * and the `compile_fail` examples of `InstanceNo`'s documentation: without the
 * `debug` feature, which Kotlin spells as an opt-in to [DebugIdentity], the
 * listener and the twins cannot be named.
 */
class InstanceNoTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `positive instance bounds`() {
        assertEquals(1, InstanceNo.new(1)!!.get())
        assertEquals(Int.MAX_VALUE, InstanceNo.new(Int.MAX_VALUE)!!.get())
        for (n in listOf(0, -1, -Int.MAX_VALUE, Int.MIN_VALUE)) assertNull(InstanceNo.new(n), "$n")
    }

    @OptIn(DebugIdentity::class)
    @Test
    fun `debug identity partition`() {
        val twin = InstanceNo.new(1)!!.twin()!!
        assertEquals(-1, twin.get())
        assertEquals(true to true, twin.isTwin() to InstanceNo.LISTENER.isListener())
        for ((n, expected) in listOf(1 to -1, 2 to -2, Int.MAX_VALUE to -2_147_483_647)) {
            val positive = InstanceNo.new(n)!!
            val itsTwin = positive.twin()!!
            assertEquals(expected, itsTwin.get())
            assertFalse(positive.isTwin())
            assertFalse(positive.isListener())
            assertTrue(itsTwin.isTwin())
            assertFalse(itsTwin.isListener())
            assertNull(itsTwin.twin())
        }
        val listener = InstanceNo.LISTENER
        assertEquals(Int.MIN_VALUE, listener.get())
        assertFalse(listener.isTwin())
        assertTrue(listener.isListener())
        assertNull(listener.twin())
    }

    /** The Rust `compile_fail` examples, each a use outside the opt-in; and the private constructor. */
    @TestFactory
    fun `a debug identity cannot be named without the opt-in`(): List<DynamicTest> = listOf(
        "the constructor" to "InstanceNo(-1)",
        "the listener" to "InstanceNo.LISTENER",
        "a twin" to "InstanceNo.new(1)!!.twin()",
        "the twin test" to "InstanceNo.new(1)!!.isTwin()",
        "the listener test" to "InstanceNo.new(1)!!.isListener()",
    ).map { (what, expression) ->
        DynamicTest.dynamicTest(what) {
            val (ok, messages) = compile("import ridl.rt.contract.InstanceNo\n\nfun use(): Any? = $expression\n")
            assertFalse(ok, "`$expression` compiled")
            val reason = if (what == "the constructor") "it is private" else "A debug instance identity"
            assertTrue(reason in messages, messages)
        }
    }

    @Test
    fun `with the opt-in each debug identity compiles`() {
        val (ok, messages) = compile(
            """
            import ridl.rt.contract.DebugIdentity
            import ridl.rt.contract.InstanceNo

            @OptIn(DebugIdentity::class)
            fun use(): List<Any?> = listOf(
                InstanceNo.LISTENER, InstanceNo.new(1)!!.twin(), InstanceNo.new(1)!!.isTwin(), InstanceNo.new(1)!!.isListener(),
            )
            """.trimIndent(),
        )
        assertTrue(ok, messages)
    }

    @OptIn(ExperimentalCompilerApi::class)
    private fun compile(source: String): Pair<Boolean, String> {
        val output = ByteArrayOutputStream()
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Use.kt", source))
            inheritClassPath = true
            jvmTarget = "17"
            workingDir = work.toFile()
            messageOutputStream = output
            verbose = false
        }.compile()
        return (result.exitCode == KotlinCompilation.ExitCode.OK) to output.toString()
    }
}
