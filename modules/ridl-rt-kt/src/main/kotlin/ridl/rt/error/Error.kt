// The error strata of ridl §10 that a runtime reports: the spelling of
// `ridl_rt::error` (docs/design.md §3).
package ridl.rt.error

import ridl.rt.RidlError
import ridl.rt.payload.Violation
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.ServeError

/**
 * The outcome of a call that did not succeed. `ridl_rt::error::CallError`.
 *
 * Its two arms, [Contract] and [Transport], are themselves sealed, so a `when`
 * over a `CallError` names either the two arms or every variant of both. A
 * call outcome is carried in a Kotlin [Result], which is why this is an
 * exception.
 */
public sealed class CallError(message: String) : RidlError(message)

/**
 * A contract error, ridl §10.2. Derived from the contract and never declared.
 * `ridl_rt::error::Contract`.
 */
public sealed class Contract(message: String) : CallError(message) {
    /** `INVALID_VALUE`: a payload breaks its typl constraints. */
    public data class InvalidValue(public val violation: Violation) : Contract("INVALID_VALUE: $violation")

    /** `PRECONDITION_FAILED`: a `require` clause evaluates to false. */
    public data object PreconditionFailed : Contract("PRECONDITION_FAILED")

    /** `CONTRACT_BROKEN`: an `ensure` clause evaluates to false. */
    public data object ContractBroken : Contract("CONTRACT_BROKEN")

    /** `UNKNOWN_INTERACTION`: the peers disagree on an interface number or an ordinal. */
    public data object UnknownInteraction : Contract("UNKNOWN_INTERACTION")
}

/** An infrastructure failure that the runtime detected, ridl §10.3. `ridl_rt::error::Transport`. */
public sealed class Transport(message: String) : CallError(message) {
    /** The response bound passed without a reply. */
    public data object Timeout : Transport("the response bound passed without a reply")

    /** A command received no delivery acknowledgment within its bound. */
    public data object Undelivered : Transport("no delivery acknowledgment within the bound")

    /** The connection to the peer is lost. */
    public data object Down : Transport("the connection to the peer is lost")

    /** A payload is not a well-formed encoding. */
    public data object Corrupt : Transport("a payload is not a well-formed encoding")

    /**
     * The providing runtime refused the call at admission and the caller may
     * retry later. Crosses the frame as a `response` outcome (frame
     * specification §9.6).
     */
    public data object Busy : Transport("the providing runtime refused the call at admission; retry later")
}

/**
 * The error of a generated client call. `ridl_rt::error::ClientError`
 * (ADR-0021 decision 16).
 *
 * One type for every call of every generated client, so an application over
 * two generated packages handles one error. A send failure is here and not in
 * [CallError], because it is not a settlement outcome: nothing was sent, and
 * no provider settled anything.
 */
public sealed class ClientError(message: String) : RidlError(message) {
    /** The call was not sent. */
    public data class Send(public val error: SendError) : ClientError("the call was not sent: ${error.message}")

    /** The call was sent and its outcome is a failure. */
    public data class Call(public val error: CallError) : ClientError("the call failed: ${error.message}")

    /**
     * The port failed while the outcome was read. Only `ReadError.Detached` is
     * reachable, because the reply buffer is sized from the reply's
     * `maxSize`; it is kept as what it is and not mapped onto a [Transport]
     * variant, which would give a local failure a frame-level meaning.
     */
    public data class Read(public val error: ReadError) : ClientError("the outcome could not be read: ${error.message}")
}

/** The error a generated `serve` ends with. `ridl_rt::error::ProviderError`. */
public sealed class ProviderError(message: String) : RidlError(message) {
    /** `Handler.serve` refused the interface's members. */
    public data class Serve(public val error: ServeError) : ProviderError("serve was refused: ${error.message}")

    /** The handler port failed while a claim was read; the claims already settled stay settled. */
    public data class Claim(public val error: ReadError) : ProviderError("a claim could not be read: ${error.message}")
}
