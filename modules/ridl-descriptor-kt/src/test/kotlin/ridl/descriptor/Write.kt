// A FlatBuffers writer for the tests, and the encoders of both descriptors
// built on it: the test-side stand-in for `ridl_descriptor::finish` and
// `ridl_descriptor::deployment::finish`. The module reads descriptors only
// (docs/design.md §6), so the writer stays out of its public API.
package ridl.descriptor

import java.util.IdentityHashMap
import ridl.descriptor.deployment.Attribute
import ridl.descriptor.deployment.AttributeValue
import ridl.descriptor.deployment.Deployment
import ridl.descriptor.deployment.Endpoint
import ridl.descriptor.deployment.FILE_IDENTIFIER as DEPLOYMENT_IDENTIFIER

/** A FlatBuffers object: a string, a byte vector, a vector of references, or a table whose fields are `Int` scalars, objects, or absent. */
internal sealed interface Node
internal class Str(val text: String) : Node
internal class Bytes(val bytes: ByteArray) : Node
internal class Vec(val items: List<Node>) : Node
internal class Tab(val fields: List<Any?>) : Node

/** A vector of `uint32`, its elements stored inline. */
internal class U32s(val values: List<Long>) : Node

/**
 * Writes [root] after the root offset and [identifier], each object after
 * every object that refers to it, so that every offset points forward. An
 * object that appears more than once is written once. [size] pads the
 * buffer with zeros at its end, which no reader reaches. Every scalar takes
 * four bytes; a reader of a `bool` or a `ubyte` reads the low byte.
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
            is Bytes -> {
                start = out.size
                u32(node.bytes.size)
                node.bytes.forEach { out.add(it) }
            }
            is U32s -> {
                start = out.size
                u32(node.values.size)
                node.values.forEach { u32(it.toInt()) }
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

// Like planus, the encoders leave out a scalar that holds its default.

private fun scalar(v: Long): Int? = if (v == 0L) null else v.toInt()

private fun flag(v: Boolean): Int? = if (v) 1 else null

private fun tag(e: Enum<*>): Int? = if (e.ordinal == 0) null else e.ordinal

private fun <T> tables(items: List<T>, each: (T) -> Node): Vec = Vec(items.map(each))

private fun strings(items: List<String>): Vec = Vec(items.map(::Str))

/** [catalog] as a catalog descriptor. */
internal fun encode(catalog: Catalog): ByteArray = write(catalogNode(catalog), FILE_IDENTIFIER)

private fun catalogNode(c: Catalog): Node = Tab(
    listOf(
        scalar(c.version), Str(c.name), Bytes(c.hash), Str(c.toolchain),
        tables(c.interfaces) { i ->
            Tab(listOf(Str(i.name), scalar(i.number), flag(i.provisional), tables(i.members, ::memberNode), U32s(i.reservedOrdinals)))
        },
        tables(c.retired) { Tab(listOf(Str(it.name), scalar(it.number))) },
        c.compatible?.let { list -> tables(list) { Tab(listOf(Bytes(it.hash))) } },
    ),
)

private fun memberNode(m: Member): Node = Tab(
    listOf(
        Str(m.name), scalar(m.ordinal), tag(m.kind),
        tables(m.payloads) { Tab(listOf(Str(it.role), Str(it.typeName), tables(it.maxSizes, ::sizeNode))) },
        m.timing?.let { Tab(listOf(tag(it.mode), it.minUs?.let(::Str), it.maxUs?.let(::Str))) },
        m.limit?.toInt(),
        m.streams?.let { list ->
            tables(list) { Tab(listOf(scalar(it.no.toLong()), tag(it.form), it.element?.let(::Str), tables(it.maxSizes, ::sizeNode))) }
        },
    ),
)

private fun sizeNode(s: MaxSize): Node = Tab(listOf(tag(s.encoding), scalar(s.bytes), tag(s.state), tag(s.cause)))

/** [deployment] as a deployment descriptor. */
internal fun encode(deployment: Deployment): ByteArray = write(deploymentNode(deployment), DEPLOYMENT_IDENTIFIER)

private fun deploymentNode(d: Deployment): Node = Tab(
    listOf(
        scalar(d.version), Str(d.system), Str(d.name), Str(d.`package`),
        tables(d.catalogs) { Tab(listOf(Str(it.name), Bytes(it.hash), Bytes(it.bytes))) },
        tables(d.interfaces) {
            Tab(listOf(Str(it.catalog), scalar(it.number), Str(it.name), flag(it.inline), flag(it.provisional), Str(it.service)))
        },
        tables(d.machines) { Tab(listOf(Str(it.name), flag(it.external), strings(it.labels), attributesNode(it.attributes))) },
        tables(d.components) {
            Tab(
                listOf(
                    Str(it.name), flag(it.implicit), flag(it.external), strings(it.instances), strings(it.labels),
                    attributesNode(it.attributes),
                ),
            )
        },
        tables(d.instances) {
            Tab(
                listOf(
                    Str(it.component), Str(it.instance), Str(it.machine), flag(it.external), attributesNode(it.attributes),
                    tables(it.offers) { key -> Tab(listOf(Str(key.catalog), scalar(key.number))) },
                    strings(it.maps), scalar(it.number.toLong()), flag(it.provisional),
                ),
            )
        },
        tables(d.links) {
            Tab(
                listOf(
                    Str(it.catalog), scalar(it.number), Str(it.service), endpointNode(it.consumer), endpointNode(it.producer),
                    tag(it.crossing), attributesNode(it.attributes),
                ),
            )
        },
        tables(d.routes) {
            Tab(
                listOf(
                    Str(it.catalog), scalar(it.number), scalar(it.ordinal), Str(it.`interface`), Str(it.member), Str(it.service),
                    tables(it.producers, ::endpointNode),
                ),
            )
        },
        tables(d.surface) { Tab(listOf(scalar(it.link), tag(it.direction))) },
        attributesNode(d.systemAttributes),
        attributesNode(d.attributes),
    ),
)

private fun endpointNode(e: Endpoint): Node = Tab(listOf(Str(e.component), Str(e.instance), Str(e.machine)))

private fun attributesNode(list: List<Attribute>): Vec =
    tables(list) { Tab(listOf(Str(it.ns), Str(it.key), it.value?.let(::valueNode))) }

private fun valueNode(v: AttributeValue): Node = Tab(listOf(v.scalar?.let(::Str), v.list?.let { tables(it, ::valueNode) }))
