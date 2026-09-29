// Identity, and the interaction descriptors generated code writes: the
// spelling of `ridl_rt::contract` (docs/design.md §3).
package ridl.rt.contract

import ridl.rt.RidlError
import ridl.rt.encoding.Encoding
import ridl.rt.sample.Duration

/**
 * A member's ordinal: its position in the interface body, counted from 1
 * (ridl §11). An ordinal is never 0. `ridl_rt::contract::Ordinal`.
 */
@JvmInline
public value class Ordinal(public val value: UInt) : Comparable<Ordinal> {
    override fun compareTo(other: Ordinal): Int = value.compareTo(other.value)
}

/**
 * An interface's number in its catalog: the number the lock file froze, or a
 * provisional number. An interface number is never 0.
 * `ridl_rt::contract::InterfaceNo`.
 */
@JvmInline
public value class InterfaceNo(public val value: UInt) : Comparable<InterfaceNo> {
    override fun compareTo(other: InterfaceNo): Int = value.compareTo(other.value)
}

/**
 * The catalog hash: SHA-256 over the catalog's interfaces, their numbers, and
 * the types they reach. `ridl_rt::contract::CatalogHash`.
 *
 * Equality is structural over the 32 bytes, as the Rust `[u8; 32]` is. The
 * bytes are copied in and out, so a hash cannot change after it is built.
 */
public class CatalogHash(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == SIZE) { "a catalog hash is $SIZE bytes, not ${this.bytes.size}" }
    }

    /** A copy of the 32 bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is CatalogHash && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "CatalogHash(${bytes.joinToString("") { "%02x".format(it) }})"

    public companion object {
        /** The size of a SHA-256 hash in bytes. */
        public const val SIZE: Int = 32
    }
}

/**
 * A catalog: one package's interfaces. `ridl_rt::contract::CatalogRef`.
 *
 * Two catalog refs are equal only when both the names and the hashes are
 * equal, because two packages with the same contents can have the same hash.
 */
public data class CatalogRef(
    /** The package name. */
    public val name: String,
    /** The catalog hash. */
    public val hash: CatalogHash,
)

/** The five interaction kinds of the language, in the language order from 1. `ridl_rt::contract::Kind`. */
public enum class Kind(public val value: Int) {
    /** A `signal`. */
    Signal(1),

    /** An `event`. */
    Event(2),

    /** A `command`. */
    Command(3),

    /** A `query`. */
    Query(4),

    /** A `fixed`. */
    Fixed(5),
    ;

    public companion object {
        /** The kind whose value is [value], or `null` for a value no kind has. */
        public fun fromValue(value: Int): Kind? = entries.firstOrNull { it.value == value }
    }
}

/** An interface's descriptor. `ridl_rt::contract::Interface`. */
public interface Interface {
    /** The catalog the interface belongs to. */
    public val catalog: CatalogRef

    /** The interface number in that catalog. */
    public val number: InterfaceNo

    /** `true` when the number is provisional, not yet frozen in the lock file. */
    public val provisional: Boolean

    /** The interface name. */
    public val name: String

    /**
     * The members, in ordinal order. A reserved ordinal has no row, so the
     * index of a row is not its ordinal minus one.
     */
    public val members: List<Member>
}

/** An interaction's descriptor: the part every kind has. `ridl_rt::contract::Interaction`. */
public interface Interaction {
    /** The interface that declares the interaction. */
    public val iface: Interface

    /** The interaction's row in [Interface.members]. */
    public val member: Member
}

/** A `signal` (ridl §4). `ridl_rt::contract::Signal`. */
public interface Signal<T> : Interaction {
    /** The channel's init value (ridl §4.4). */
    public fun init(): T
}

/** An `event` (ridl §5). `ridl_rt::contract::Event`. */
public interface Event<T> : Interaction

/** A `fixed` (ridl §8). `ridl_rt::contract::Fixed`. */
public interface Fixed<T> : Interaction

/** A `command` (ridl §6). `ridl_rt::contract::Command`. */
public interface Command<A> : Interaction {
    /**
     * Evaluates the command's `require` clauses: `true` when every clause is
     * true or the command declares none. `false` is reported as
     * [ridl.rt.error.Contract.PreconditionFailed].
     */
    public fun require(args: A): Boolean
}

/** A `query` (ridl §7). `ridl_rt::contract::Query`. */
public interface Query<A, R> : Interaction {
    /**
     * Evaluates the query's `require` clauses: `true` when every clause is
     * true or the query declares none. `false` is reported as
     * [ridl.rt.error.Contract.PreconditionFailed].
     */
    public fun require(args: A): Boolean

    /**
     * Evaluates the query's `ensure` clauses: `true` when every clause is true
     * or the query declares none. `false` is reported as
     * [ridl.rt.error.Contract.ContractBroken].
     */
    public fun ensure(args: A, reply: R): Boolean
}

/** One member of an interface: a row of [Interface.members]. `ridl_rt::contract::Member`. */
public data class Member(
    /** The member's ordinal. */
    public val ordinal: Ordinal,
    /** The member's kind. */
    public val kind: Kind,
    /** The member's name. */
    public val name: String,
    /**
     * The member's timing, as the IR resolved it. `null` when the IR carries
     * no timing: a `command` or a `query` with no timing annotation, or a
     * `fixed`.
     */
    public val timing: Timing?,
    /**
     * One entry per payload: two for a `query` (the request, then the reply),
     * one for every other kind.
     */
    public val payloads: List<PayloadInfo>,
) {
    /**
     * The member's `timing.max`: on a `command` or a `query` the response
     * bound, and so the call's deadline (ridl §9.3; frame specification §8).
     * `null` when the member has no timing or its timing has no `max`.
     * `ridl_rt::contract::Member::call_deadline`.
     *
     * The `max` is returned whatever the kind; on a `signal` it is the
     * staleness bound and on an `event` the time to live (ridl §9), neither
     * of which is a call deadline, so call it on a `command` or a `query`.
     */
    public fun callDeadline(): Duration? = timing?.max

    /**
     * The bytes one in-flight instance of this member reserves in [encoding]:
     * the sum of [PayloadInfo.maxSize] for [encoding] over its [payloads] —
     * one payload for most kinds, two for a `query`, the request and then the
     * reply. `ridl_rt::contract::Member::reservation`.
     *
     * No specification defines this budget. It is derived from the
     * descriptors alone, so that every runtime sizing a table of calls in
     * flight computes the same number.
     *
     * @throws Unsized naming this member and the first payload whose size for
     *   [encoding] is `null`: a missing size is reported, never estimated.
     */
    public fun reservation(encoding: Encoding): ULong {
        var total = 0uL
        for (payload in payloads) {
            val size = encoding.maxSize(payload.maxSize) ?: throw Unsized(ordinal, name, payload.typeName)
            total = saturatingAdd(total, size.toULong())
        }
        return total
    }
}

/**
 * The in-flight byte budget of a table of members in [encoding]: the sum of
 * [Member.reservation] over [members]. Pass an interface's
 * [Interface.members]; a table serving several interfaces adds the budget of
 * each. `ridl_rt::contract::table_budget`.
 *
 * No specification defines this budget. It is derived from the descriptors
 * alone, and it counts every member it is given, whatever its kind.
 *
 * @throws Unsized for the first member, in the order given, whose reservation
 *   has a payload with no size for [encoding].
 */
public fun tableBudget(members: List<Member>, encoding: Encoding): ULong =
    members.fold(0uL) { total, member -> saturatingAdd(total, member.reservation(encoding)) }

/** `a + b`, clamped at the largest `ULong`, as Rust's `u64::saturating_add`. */
private fun saturatingAdd(a: ULong, b: ULong): ULong = if (ULong.MAX_VALUE - a < b) ULong.MAX_VALUE else a + b

/**
 * A payload with no size in the encoding a budget was asked for: its
 * [EncodedSizes] field for that encoding is `null`. The budget is not
 * computed, because a missing size is not estimated.
 * `ridl_rt::contract::Unsized`.
 */
public class Unsized(
    /** The member's ordinal. */
    public val ordinal: Ordinal,
    /** The member's name. */
    public val member: String,
    /** The name of the payload type that has no size. */
    public val typeName: String,
) : RidlError("member `$member` (ordinal ${ordinal.value}): payload `$typeName` has no size in this encoding")

/** The form of a timing annotation (ridl §9). `ridl_rt::contract::TimingMode`. */
public enum class TimingMode {
    /** `@Xms`: a strict period, on a signal only (ridl §9.2). */
    StrictPeriodic,

    /** `@[min..max]`, where either side may be absent. */
    Range,
}

/**
 * A member's timing (ridl §9). `ridl_rt::contract::Timing`.
 *
 * `max` is the staleness bound of a signal, the time to live of an event, and
 * the response bound of a call. Under [TimingMode.StrictPeriodic], `min` and
 * `max` both hold the period.
 */
public data class Timing(
    /** The form of the annotation. */
    public val mode: TimingMode,
    /** The lower bound. `null` when the IR leaves it unset. */
    public val min: Duration?,
    /** The upper bound. `null` when the IR leaves it unset. */
    public val max: Duration?,
)

/** One payload of a member. `ridl_rt::contract::PayloadInfo`. */
public data class PayloadInfo(
    /** The payload type's name. */
    public val typeName: String,
    /** The payload's largest encoded size in each core encoding. */
    public val maxSize: EncodedSizes,
)

/**
 * A payload's largest encoded size in bytes, one field per core encoding.
 * `ridl_rt::contract::EncodedSizes`.
 *
 * A field is `null` when the toolchain cannot size the payload for that
 * encoding; a consumer reads `null` as "no size is available here", never as
 * "this payload cannot be encoded this way".
 */
public data class EncodedSizes(
    /** The proto3 size. */
    public val proto3: UInt?,
    /** The FlatBuffers size. */
    public val flatbuffers: UInt?,
    /** The `repr(C)` size. */
    public val reprC: UInt?,
)
