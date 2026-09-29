package ridl.rt

/**
 * The base of every error `ridl-rt-kt` throws or carries in a Kotlin [Result].
 *
 * A port method throws its error rather than returning a `Result` (D-K3), so
 * each error type of `ridl-rt` is a Kotlin exception here. They are values in
 * everything but that: no stack trace is captured, no cause is chained, and
 * the variants without data are singletons, so an error is as cheap to throw
 * and compare as the Rust enum it spells.
 */
public abstract class RidlError internal constructor(message: String) :
    RuntimeException(message, null, false, false)
