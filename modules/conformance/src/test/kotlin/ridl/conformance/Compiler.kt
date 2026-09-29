package ridl.conformance

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * Compiles generated Kotlin as a consumer's build would: against
 * `ridl-rt-kt` on the class path, with warnings as errors (docs/design.md §7,
 * "The output compiles").
 */
object Compiler {
    class Compiled(val ok: Boolean, val messages: String, val classLoader: ClassLoader?)

    @OptIn(ExperimentalCompilerApi::class)
    fun compile(sources: Map<String, String>, work: Path): Compiled {
        val output = ByteArrayOutputStream()
        val result: JvmCompilationResult = KotlinCompilation().apply {
            this.sources = sources.map { (path, text) -> SourceFile.kotlin(path.replace('/', '_'), text) }
            inheritClassPath = true
            allWarningsAsErrors = true
            jvmTarget = "17"
            workingDir = work.toFile()
            messageOutputStream = output
            verbose = false
        }.compile()
        val ok = result.exitCode == KotlinCompilation.ExitCode.OK
        return Compiled(ok, output.toString(), if (ok) result.classLoader else null)
    }
}
