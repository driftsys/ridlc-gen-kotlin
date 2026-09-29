package ridl.conformance

import com.google.protobuf.util.JsonFormat
import ridl.codegen.kotlin.SCHEMA
import ridl.codegen.v1.ModelOuterClass
import ridl.codegen.v1.Plugin

/** A request nested as deep as the IR specification §4 recommends a reader accept. */
object Deep {
    /** The smallest request whose JSON nests at least [levels] deep, and that JSON. */
    fun request(levels: Int): Pair<Plugin.CodegenRequest, String> {
        var arrays = 0
        while (true) {
            arrays += 1
            val request = nested(arrays)
            val json = JsonFormat.printer().print(request)
            if (jsonDepth(json) >= levels) return request to json
        }
    }

    /** A struct whose one field's type is [arrays] arrays deep around a named type. */
    private fun nested(arrays: Int): Plugin.CodegenRequest {
        var type = ModelOuterClass.Type.newBuilder()
            .setNamed(ModelOuterClass.TypeRef.newBuilder().setReference("Level").setResolved(true))
            .build()
        repeat(arrays) {
            type = ModelOuterClass.Type.newBuilder()
                .setArray(ModelOuterClass.ArrayType.newBuilder().setElement(type).setMax(4))
                .build()
        }
        val field = ModelOuterClass.Field.newBuilder().setType(type)
        val struct = ModelOuterClass.Struct.newBuilder()
            .addSlots(ModelOuterClass.Slot.newBuilder().setOrdinal(1).setField(field))
        val model = ModelOuterClass.Model.newBuilder()
            .addDeclarations(ModelOuterClass.Declaration.newBuilder().setStruct(struct))
        return Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(model).build()
    }

    /** The deepest nesting of `{` and `[` outside strings. */
    fun jsonDepth(json: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' || c == '[' -> max = maxOf(max, ++depth)
                c == '}' || c == ']' -> depth -= 1
            }
        }
        return max
    }
}
