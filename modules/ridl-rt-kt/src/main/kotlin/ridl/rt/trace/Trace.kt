// The trace context a call or an event can carry: the spelling of
// `ridl_rt::trace` (ridl 0.6.0, driftsys/ridl#752 and #754), whose hook Rust
// puts behind the `std` feature and the JVM always has.
//
// [TraceContext] is the W3C Trace Context `traceparent` layout without the
// version byte, as plain data. `ridl-rt-kt` carries it unvalidated: an
// all-zero id is carried like any other value, and rejecting one is the
// choice of whatever exports the trace. [Propagation] is the hook through
// which an application gives generated code the context to send and receives
// the context that arrived.
package ridl.rt.trace

import ridl.rt.RidlError
import java.util.concurrent.atomic.AtomicReference

/**
 * A trace id, a span id and the trace flags, in the layout of the W3C Trace
 * Context `traceparent` header without its version byte.
 * `ridl_rt::trace::TraceContext`.
 *
 * The value is not validated beyond the lengths of its ids. Equality is
 * structural over the bytes, as the Rust `Copy` struct's is. The ids are
 * copied in and out, so a context cannot change after it is built.
 */
public class TraceContext(traceId: ByteArray, spanId: ByteArray, flags: UByte) {
    private val trace: ByteArray = traceId.copyOf()
    private val span: ByteArray = spanId.copyOf()

    /** The trace flags byte, carried as given. */
    public val flags: UByte = flags

    init {
        require(trace.size == TRACE_ID_SIZE) { "a trace id is $TRACE_ID_SIZE bytes, not ${trace.size}" }
        require(span.size == SPAN_ID_SIZE) { "a span id is $SPAN_ID_SIZE bytes, not ${span.size}" }
    }

    /** A copy of the identifier of the whole trace. */
    public val traceId: ByteArray get() = trace.copyOf()

    /** A copy of the identifier of the span that sent the call or raised the event. */
    public val spanId: ByteArray get() = span.copyOf()

    override fun equals(other: Any?): Boolean =
        other is TraceContext && trace.contentEquals(other.trace) && span.contentEquals(other.span) && flags == other.flags

    override fun hashCode(): Int = (trace.contentHashCode() * 31 + span.contentHashCode()) * 31 + flags.hashCode()

    override fun toString(): String =
        "TraceContext(traceId=${trace.hex()}, spanId=${span.hex()}, flags=${"%02x".format(flags.toInt())})"

    public companion object {
        /** The size of a trace id in bytes. */
        public const val TRACE_ID_SIZE: Int = 16

        /** The size of a span id in bytes. */
        public const val SPAN_ID_SIZE: Int = 8
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/**
 * The hook through which an application connects its telemetry library to the
 * trace context that crosses a port. `ridl_rt::trace::Propagation`.
 *
 * `ridl-rt-kt` carries the bytes. Generated code is meant to call the hook at
 * the points below; no generated code calls it yet: the generated face still
 * passes `null` as the trace context (driftsys/ridl#754 tracks the change).
 * Which span is current, how ids are created and where traces are exported
 * belong to the application's library. No hook is registered until the
 * application calls [setPropagation].
 *
 * Generated code that calls the hook calls [current] just before it sends a
 * call or a raise; [enter] when it receives a claim, before the claim span
 * exists and before the handler runs; and [leave] after the handler has
 * returned, and also when it throws. Each `enter` is paired with one `leave`,
 * pairs nest, and the `leave` of a pair runs on the thread that ran its
 * `enter`, so an implementation can keep what `enter` attached on a
 * per-thread stack. An implementation's `enter` and `leave` should not throw.
 */
public interface Propagation {
    /** Called just before a call or a raise is sent. Returns the context to send, or `null`. */
    public fun current(): TraceContext?

    /**
     * Called when a claim is received, before the claim span is created and
     * before the handler runs. [received] is the context that came over the
     * wire, or `null` when the claim carried none; `leave` is called after
     * the handler in both cases.
     */
    public fun enter(received: TraceContext?)

    /** Called after the handler has returned, and also when it throws. Undoes the matching [enter]. */
    public fun leave()
}

/** [setPropagation] was called after a hook was already registered. `ridl_rt::trace::AlreadySet`. */
public data object AlreadySet : RidlError("a trace propagation hook is already registered")

private val hook = AtomicReference<Propagation?>(null)

/**
 * Registers the hook once for the process. A second call throws [AlreadySet]
 * and keeps the first hook. One hook serves every generated package in the
 * process, so a handler in one package that calls a client of another
 * continues the same trace. `ridl_rt::trace::set_propagation`.
 */
public fun setPropagation(p: Propagation) {
    if (!hook.compareAndSet(null, p)) throw AlreadySet
}

/** The registered hook, or `null` while the application has not called [setPropagation]. `ridl_rt::trace::propagation`. */
public fun propagation(): Propagation? = hook.get()
