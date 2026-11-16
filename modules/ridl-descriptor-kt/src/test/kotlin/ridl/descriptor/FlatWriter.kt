package ridl.descriptor

import java.util.IdentityHashMap

/** A FlatBuffers object: a string, a vector of references, or a table whose fields are `Int` scalars, objects, or absent. */
internal sealed interface Node
internal class Str(val text: String) : Node
internal class Vec(val items: List<Node>) : Node
internal class Tab(val fields: List<Any?>) : Node

/**
 * Writes [root] after the root offset and [identifier], each object after
 * every object that refers to it, so that every offset points forward. An
 * object that appears more than once is written once, and every reference to
 * it points there. [size] pads the buffer with zeros at its end, which no
 * reader reaches.
 */
internal fun write(root: Node, identifier: String, size: Int = 0): ByteArray {
    val out = ArrayList<Byte>()
    fun u16(v: Int) { out.add(v.toByte()); out.add((v shr 8).toByte()) }
    fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
    fun align() { while (out.size % 4 != 0) out.add(0) }
    fun patch(at: Int, target: Int) {
        val v = target - at
        for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte()
    }
    val written = IdentityHashMap<Node, Int>()
    fun emit(node: Node): Int {
        align()
        val refs = ArrayList<Pair<Int, Node>>()
        val start: Int
        when (node) {
            is Str -> {
                start = out.size
                val bytes = node.text.toByteArray()
                u32(bytes.size)
                bytes.forEach { out.add(it) }
                out.add(0)
            }
            is Vec -> {
                start = out.size
                u32(node.items.size)
                node.items.forEach { refs.add(out.size to it); u32(0) }
            }
            is Tab -> {
                val n = node.fields.size
                val vtable = out.size
                u16(4 + 2 * n)
                u16(4 + 4 * n)
                node.fields.forEachIndexed { i, f -> u16(if (f == null) 0 else 4 + 4 * i) }
                align()
                start = out.size
                u32(start - vtable)
                node.fields.forEach { f ->
                    when (f) {
                        is Int -> u32(f)
                        is Node -> { refs.add(out.size to f); u32(0) }
                        else -> u32(0)
                    }
                }
            }
        }
        written[node] = start
        for ((at, child) in refs) patch(at, written[child] ?: emit(child))
        return start
    }
    u32(0)
    identifier.toByteArray().forEach { out.add(it) }
    patch(0, emit(root))
    while (out.size < size) out.add(0)
    return out.toByteArray()
}
