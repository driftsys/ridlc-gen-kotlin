package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Constraint
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.Plugin.CodegenRequest
import java.nio.file.Path

/**
 * driftsys/ridl#654's `crates/ridl-backend-rust/tests/payload_constraints.rs`
 * at the pinned release, over the `payload-constraints` corpus package: the
 * value objects and the codec the plugin generates, driven by the probe of
 * `resources/constraints/payload.kt`. As the Rust test rewrites the IR, the
 * model is rewritten before generation: `Extreme` gets an integer step, which
 * is not a source form, and five floats get constraints no source spells.
 */
class ConstraintsTest {
    @TempDir
    lateinit var work: Path

    /** The declared name of each rewritten float, and its minimum, maximum and step. */
    private val floats = mapOf(
        "StepOnly" to Triple(null, null, "0.5"),
        "Tiny" to Triple(null, null, "1e-308"),
        "Wide" to Triple("-1e308", "1e308", "1e308"),
        "Large" to Triple("1000000000000.0", null, "0.01"),
        "LargeNegative" to Triple("-1e308", null, "0.1"),
    )

    private fun rewritten(model: Model): Model {
        val builder = model.toBuilder()
        for (i in 0 until builder.declarationsCount) {
            val declaration = builder.getDeclarations(i)
            if (!declaration.hasScalar()) continue
            val name = declaration.name.declared
            val constraint = when {
                name == "Extreme" -> declaration.scalar.constraint.toBuilder().setStep("3").build()
                name in floats -> {
                    val (min, max, step) = floats.getValue(name)
                    Constraint.newBuilder().apply {
                        min?.let { setMin(it) }
                        max?.let { setMax(it) }
                        setStep(step)
                    }.build()
                }
                else -> continue
            }
            val scalar = declaration.scalar.toBuilder().setConstraint(constraint).setVacuous(false)
            builder.setDeclarations(i, declaration.toBuilder().setScalar(scalar))
        }
        return builder.build()
    }

    @Test
    fun `payload constraints hold in the value objects and the codec`() {
        val requests = Harness.capturedRequests("payload-constraints", work.resolve("capture")).values.map(Wire::readRequest)
        val sources = requests.flatMap { r ->
            val response = Generator.generate(CodegenRequest.newBuilder(r).setModel(rewritten(r.model)).build())
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val probe = checkNotNull(javaClass.getResource("/constraints/payload.kt")).readText()
        val compiled = Compiler.compile(sources + ("constraints/payload.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("ridl.conformance.probe.constraints.PayloadConstraintsProbe")
            .getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
