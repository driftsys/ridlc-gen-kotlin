// The deployment descriptor: the spelling of `ridl_descriptor::deployment`'s
// reading half, `verify` and the tables of `schema/deployment.fbs`, at the
// pinned release.
package ridl.descriptor.deployment

import ridl.descriptor.Malformed
import ridl.descriptor.Table
import ridl.descriptor.quoted
import ridl.descriptor.root

/** The schema version this module reads. `ridl_descriptor::deployment::SCHEMA_VERSION`. */
public const val SCHEMA_VERSION: Long = 1

/** The file identifier at bytes 4..8 of every deployment descriptor. */
public const val FILE_IDENTIFIER: String = "RDLD"

/** The artifact suffix, `<deployment package>.<DeploymentName>.deployment.binfb`. */
public const val FILE_SUFFIX: String = ".deployment.binfb"

/** How deeply attribute values may nest. `MAX_ATTRIBUTE_DEPTH`. */
public const val MAX_ATTRIBUTE_DEPTH: Int = 16

/** A deployment descriptor `verify` refuses. `ridl_descriptor::deployment::VerifyError`. */
public sealed class VerifyError(message: String, cause: Throwable? = null) : RuntimeException(message, cause, false, false) {
    public data class TooShort(val len: Int) : VerifyError("$len bytes is shorter than a deployment descriptor header")

    public class WrongIdentifier(public val identifier: ByteArray) :
        VerifyError("not a deployment descriptor: file identifier ${quoted(identifier)}, expected \"$FILE_IDENTIFIER\"")

    public data class WrongVersion(val version: Long) :
        VerifyError("deployment descriptor version $version; this toolchain reads version $SCHEMA_VERSION")

    /** The FlatBuffers structure is not sound. [kind] names planus's `ErrorKind`. */
    public data class Invalid(val kind: String) : VerifyError("deployment descriptor is malformed: $kind")

    /** An embedded catalog the catalog verifier refuses. */
    public class EmbeddedCatalog(public val catalog: String, public val source: ridl.descriptor.VerifyError) :
        VerifyError("embedded catalog `$catalog` is refused: ${source.message}", source)

    /** The stored hash is not the hash inside the embedded catalog. */
    public data class CatalogHashMismatch(val catalog: String) :
        VerifyError("the hash stored for embedded catalog `$catalog` is not the hash inside the catalog")

    public data object TooDeep : VerifyError("attribute values nest deeper than $MAX_ATTRIBUTE_DEPTH levels")

    public data object TooManyAttributeValues : VerifyError("the attribute values outnumber the size of the buffer")

    public data object MalformedAttributeValue : VerifyError("an attribute value sets both or neither of `scalar` and `list`")

    public data class InstanceNumber(val component: String, val instance: String, val number: Int) :
        VerifyError("instance `$component/$instance` has the number $number; an instance number is 1 to 2147483647")

    public data class DuplicateInstanceNumber(
        val number: Int,
        val firstComponent: String,
        val firstInstance: String,
        val secondComponent: String,
        val secondInstance: String,
    ) : VerifyError(
        "instances `$firstComponent/$firstInstance` and `$secondComponent/$secondInstance` have the same number " +
            "$number; an instance number is unique within a deployment",
    )

    public data class SurfaceLink(val index: Int, val link: Long, val links: Int) :
        VerifyError("surface entry $index names link $link, but the deployment has $links links")
}

/** `ridl.deployment.Crossing`, in schema order. */
public enum class Crossing { SameMachine, DifferentMachine, OffBoard }

/** `ridl.deployment.SurfaceDirection`, in schema order. */
public enum class SurfaceDirection { ExternalConsumes, ExternalOffers }

/** An attribute value: exactly one of [scalar] and [list] is set. */
public class AttributeValue(public val scalar: String?, public val list: List<AttributeValue>?)

/** A backend key: [ns] is its namespace. [value] is absent for a flag. */
public class Attribute(public val ns: String, public val key: String, public val value: AttributeValue?)

/** One catalog of the closure, embedded whole: [bytes] is a verified catalog descriptor. */
public class EmbeddedCatalog(public val name: String, public val hash: ByteArray, public val bytes: ByteArray)

public class InterfaceKey(public val catalog: String, public val number: Long)

public class DeployedInterface(
    public val catalog: String,
    public val number: Long,
    public val name: String,
    public val inline: Boolean,
    public val provisional: Boolean,
    public val service: String,
)

public class Machine(
    public val name: String,
    public val external: Boolean,
    public val labels: List<String>,
    public val attributes: List<Attribute>,
)

public class Component(
    public val name: String,
    public val implicit: Boolean,
    public val external: Boolean,
    public val instances: List<String>,
    public val labels: List<String>,
    public val attributes: List<Attribute>,
)

/** One placement of a component on a machine. [number] is 1 to 2147483647, unique within the deployment. */
public class Instance(
    public val component: String,
    public val instance: String,
    public val machine: String,
    public val external: Boolean,
    public val attributes: List<Attribute>,
    public val offers: List<InterfaceKey>,
    public val maps: List<String>,
    public val number: Int,
    public val provisional: Boolean,
)

public class Endpoint(public val component: String, public val instance: String, public val machine: String)

public class Link(
    public val catalog: String,
    public val number: Long,
    public val service: String,
    public val consumer: Endpoint,
    public val producer: Endpoint,
    public val crossing: Crossing,
    public val attributes: List<Attribute>,
)

public class Route(
    public val catalog: String,
    public val number: Long,
    public val ordinal: Long,
    public val `interface`: String,
    public val member: String,
    public val service: String,
    public val producers: List<Endpoint>,
)

/** An external party on the system boundary: [link] indexes [Deployment.links]. */
public class Surface(public val link: Long, public val direction: SurfaceDirection)

/** A verified deployment descriptor, read in full. `ridl.deployment.Deployment`. */
public class Deployment(
    public val version: Long,
    public val system: String,
    public val name: String,
    public val `package`: String,
    public val catalogs: List<EmbeddedCatalog>,
    public val interfaces: List<DeployedInterface>,
    public val machines: List<Machine>,
    public val components: List<Component>,
    public val instances: List<Instance>,
    public val links: List<Link>,
    public val routes: List<Route>,
    public val surface: List<Surface>,
    public val systemAttributes: List<Attribute>,
    public val attributes: List<Attribute>,
)

/**
 * Verifies [bytes] as a deployment descriptor and reads it in full.
 * `ridl_descriptor::deployment::verify`: the header, the identifier, the
 * root, the version; every field, in the order the Rust walk touches them,
 * with its checks of attribute values, instance numbers and surface links;
 * then each embedded catalog through the catalog [ridl.descriptor.verify], and
 * its stored hash against the catalog's own.
 *
 * @throws VerifyError when Rust's `verify` refuses the buffer.
 */
public fun verify(bytes: ByteArray): Deployment {
    if (bytes.size < 8) throw VerifyError.TooShort(bytes.size)
    val identifier = bytes.copyOfRange(4, 8)
    if (!identifier.contentEquals(FILE_IDENTIFIER.toByteArray())) throw VerifyError.WrongIdentifier(identifier)
    val deployment = try {
        val table = root(bytes)
        val version = table.u32(0)
        if (version != SCHEMA_VERSION) throw VerifyError.WrongVersion(version)
        Walk(bytes.size / 4).deployment(table, version)
    } catch (e: Malformed) {
        throw VerifyError.Invalid(e.kind)
    }
    for (catalog in deployment.catalogs) {
        val embedded = try {
            ridl.descriptor.verify(catalog.bytes)
        } catch (e: ridl.descriptor.VerifyError) {
            throw VerifyError.EmbeddedCatalog(catalog.name, e)
        }
        if (!embedded.hash.contentEquals(catalog.hash)) throw VerifyError.CatalogHashMismatch(catalog.name)
    }
    return deployment
}

/** `walk_fields`, with its budget of one attribute value per four bytes of the buffer. */
private class Walk(private var budget: Int) {
    fun deployment(t: Table, version: Long): Deployment {
        val system = t.string(1)
        val name = t.string(2)
        val pkg = t.string(3)
        val catalogs = t.tables(4) { EmbeddedCatalog(it.string(0), it.bytes(1), it.bytes(2)) }
        val interfaces = t.tables(5) {
            DeployedInterface(it.string(0), it.u32(1), it.string(2), it.bool(3), it.bool(4), it.string(5))
        }
        val machines = t.tables(6) { Machine(it.string(0), it.bool(1), it.strings(2), attributes(it, 3)) }
        val components = t.tables(7) {
            Component(it.string(0), it.bool(1), it.bool(2), it.strings(3), it.strings(4), attributes(it, 5))
        }
        val instances = instances(t)
        val links = t.tables(9) {
            Link(it.string(0), it.u32(1), it.string(2), endpoint(it.table(3)), endpoint(it.table(4)),
                Crossing.entries[it.enum(5, Crossing.entries.size)], attributes(it, 6))
        }
        val routes = t.tables(10) {
            Route(it.string(0), it.u32(1), it.u32(2), it.string(3), it.string(4), it.string(5), it.tables(6, ::endpoint))
        }
        val linkCount = t.required(9) { w, a -> vector(w, a, 4) }.len
        var index = 0
        val surface = t.tables(11) {
            val link = it.u32(0)
            if (link >= linkCount) throw VerifyError.SurfaceLink(index, link, linkCount)
            index++
            Surface(link, SurfaceDirection.entries[it.enum(1, SurfaceDirection.entries.size)])
        }
        val systemAttributes = attributes(t, 12)
        val attributes = attributes(t, 13)
        return Deployment(version, system, name, pkg, catalogs, interfaces, machines, components, instances, links, routes,
            surface, systemAttributes, attributes)
    }

    private fun instances(t: Table): List<Instance> {
        val seen = HashMap<Int, Instance>()
        return t.tables(8) {
            val component = it.string(0)
            val instance = it.string(1)
            val machine = it.string(2)
            val external = it.bool(3)
            val attributes = attributes(it, 4)
            val offers = it.tables(5) { key -> InterfaceKey(key.string(0), key.u32(1)) }
            val maps = it.strings(6)
            val provisional = it.bool(8)
            val number = it.i32(7)
            if (number <= 0) throw VerifyError.InstanceNumber(component, instance, number)
            seen[number]?.let { first ->
                throw VerifyError.DuplicateInstanceNumber(number, first.component, first.instance, component, instance)
            }
            Instance(component, instance, machine, external, attributes, offers, maps, number, provisional)
                .also { placed -> seen[number] = placed }
        }
    }

    private fun endpoint(t: Table): Endpoint = Endpoint(t.string(0), t.string(1), t.string(2))

    private fun attributes(t: Table, index: Int): List<Attribute> = t.tables(index) {
        val ns = it.string(0)
        val key = it.string(1)
        Attribute(ns, key, it.optionalTable(2)?.let { value -> value(value, 1) })
    }

    private fun value(t: Table, depth: Int): AttributeValue {
        if (depth > MAX_ATTRIBUTE_DEPTH) throw VerifyError.TooDeep
        if (budget == 0) throw VerifyError.TooManyAttributeValues
        budget--
        val scalar = t.optionalString(0)
        val list = t.optional(1) { w, a -> vector(w, a, 4) }
        if ((scalar != null) == (list != null)) throw VerifyError.MalformedAttributeValue
        return AttributeValue(scalar, if (list == null) null else t.optionalTables(1) { item -> value(item, depth + 1) })
    }
}
