// Which members with a stream the face carries, which the face and a doc link
// to one have to agree on.
package ridl.codegen.kotlin.types

import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.StreamForm
import ridl.codegen.v1.ModelOuterClass.StreamShape

/** How the face carries a member's streams: a reply stream, a stream parameter, or both. */
internal enum class StreamShapeKind { Reply, Upload, Duplex }

/** How the face carries [i]'s streams, or `null` when it has none or a form the face does not carry. */
internal fun streamShape(i: Interaction): StreamShapeKind? {
    val streams = i.streamsList
    if (streams.isEmpty() || !streams.all(::carried)) return null
    val reply = streams.firstOrNull { it.no == 0 }
    val parameters = streams.filter { it.no != 0 }
    return when {
        i.hasQuery() && reply != null && parameters.isEmpty() -> StreamShapeKind.Reply
        parameters.size != 1 || parameters[0].no != 1 -> null
        i.hasCommand() -> StreamShapeKind.Upload
        !i.hasQuery() -> null
        reply == null -> StreamShapeKind.Upload
        reply.form == StreamForm.STREAM_FORM_ELEMENTS -> StreamShapeKind.Duplex
        else -> null
    }
}

private fun carried(stream: StreamShape): Boolean = when (stream.form) {
    StreamForm.STREAM_FORM_ELEMENTS -> stream.hasElement()
    StreamForm.STREAM_FORM_BYTES, StreamForm.STREAM_FORM_TEXT -> true
    else -> false
}
