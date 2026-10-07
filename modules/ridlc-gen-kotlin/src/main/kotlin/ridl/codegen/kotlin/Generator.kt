package ridl.codegen.kotlin

import ridl.codegen.v1.Plugin.CodegenRequest
import ridl.codegen.v1.Plugin.CodegenResponse
import ridl.codegen.kotlin.types.CodecEmitter
import ridl.codegen.kotlin.types.FacesEmitter
import ridl.codegen.kotlin.types.TypesEmitter
import ridl.codegen.kotlin.types.collisions
import ridl.codegen.v1.Plugin.Diagnostic
import ridl.codegen.v1.Plugin.DiagnosticSeverity
import ridl.codegen.v1.Plugin.GeneratedFile

/** The schema this plugin reads (IR specification §7). */
const val SCHEMA: String = "ridl.codegen.v1"

/** The plugin's name, as `ridlc` names it in every message. */
const val PLUGIN: String = "ridlc-gen-kotlin"

/**
 * `generate(CodegenRequest) -> CodegenResponse`: the whole plugin, minus the
 * pipe. It opens no file and writes nothing but its return value.
 */
object Generator {
    fun generate(request: CodegenRequest): CodegenResponse {
        // Step 2: a schema this plugin does not read is a backend failure the
        // host reports, not a host failure, so it is a response.
        if (request.schema != SCHEMA) {
            return failure("$PLUGIN reads `$SCHEMA` and the request is `${request.schema}`")
        }
        // Step 3.
        val options = when (val parsed = Options.parse(request)) {
            is Options.Parsed.Refused -> return failure(parsed.messages)
            is Options.Parsed.Ok -> parsed.options
        }
        // Step 4: the files. An interface the face cannot carry is skipped
        // with a warning, and the rest of the package is still generated.
        val types = TypesEmitter(request.model, options).emit()
        val codec = CodecEmitter(request.model, options).emit()
        val faces = FacesEmitter(request.model, options).emit()
        val errors = types.errors + codec.errors + faces.errors
        if (errors.isNotEmpty()) return failure(errors)
        // Two generated types of one name: every file's names are one
        // package's, so they are checked together.
        val clashes = collisions(request.model.name.dotted, types.claims + codec.claims + faces.claims)
        if (clashes.isNotEmpty()) return failure(clashes)
        // Every file starts with the host's marker and the project's header
        // (driftsys/ridl#746), as every in-tree backend's file does.
        val preamble = preamble(request.generatedMarker, request.header)
        val files = listOf(types.path to types.text, codec.path to codec.text, faces.path to faces.text)
            .mapNotNull { (path, text) -> text?.let { GeneratedFile.newBuilder().setPath(path).setText(preamble + it).build() } }
        return CodegenResponse.newBuilder()
            .addAllFiles(files)
            .addAllDiagnostics(faces.warnings.map { diagnostic(DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING, it) })
            .build()
    }

    private fun failure(vararg messages: String): CodegenResponse = failure(messages.toList())

    private fun failure(messages: List<String>): CodegenResponse =
        CodegenResponse.newBuilder()
            .addAllDiagnostics(messages.map { error(it) })
            .build()

    private fun error(message: String): Diagnostic = diagnostic(DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR, message)

    private fun diagnostic(severity: DiagnosticSeverity, message: String): Diagnostic =
        Diagnostic.newBuilder().setSeverity(severity).setMessage(message).build()
}

/**
 * The comment block a generated file starts with: [marker], then each line of
 * [header], each behind `//`, then one blank line. An empty line is a bare
 * `//`, so an empty [marker] with a header gives a bare `//` as line 1. Empty
 * when both are empty, as in a request older than the two fields. The host
 * normalises [header]: lines joined by `\n`, no trailing newline.
 * `ridl_ir::codegen::comment_preamble`.
 */
internal fun preamble(marker: String, header: String): String {
    if (marker.isEmpty() && header.isEmpty()) return ""
    val lines = listOf(marker) + if (header.isEmpty()) emptyList() else header.split('\n')
    return lines.joinToString("") { if (it.isEmpty()) "//\n" else "// $it\n" } + "\n"
}
