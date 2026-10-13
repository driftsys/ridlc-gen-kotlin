# From `.ridl` to Kotlin

## Constrained scalars

A scalar with constraints:

<!-- excerpt: ridl/cabin.ridl -->

```ridl
/// A percentage of full scale.
type Level: integer [0..100]
```

becomes a value class. Its constructor is private, so every `Level` holds a
value in range:

<!-- excerpt: build/generated/ridl/veh/cabin/Types.kt -->

```kotlin
/**
 * A percentage of full scale.
 *
 * Rules:
 * - range 0 ..= 100
 */
@JvmInline
public value class Level private constructor(
  public val `value`: Long,
) {
// ...
    public fun of(`value`: Long): Level {
      val rule = violation(value)
      if (rule != null) {
        throw ConstraintViolation("Level", rule, value)
      }
      return Level(value)
    }

    /**
     * [value] as a `Level`, or `null` when it breaks a constraint.
     */
    public fun ofOrNull(`value`: Long): Level? = if (violation(value) == null) Level(value) else null
```

`Level.of(101)` throws `ConstraintViolation` with `Rule.Range`;
`Level.ofOrNull(101)` returns `null`.

## Enums

<!-- excerpt: ridl/cabin.ridl -->

```ridl
enum Health {
  /// The subsystem works normally.
  OK = 0
  /// The subsystem works, with a fault that needs attention.
  WARN = 1
  /// The subsystem does not work.
  FAIL = 2
}
```

becomes an enum class that keeps each discriminant:

<!-- excerpt: build/generated/ridl/veh/cabin/Types.kt -->

```kotlin
public enum class Health(
  public val `value`: Long,
) {
// ...
  OK(0L),
// ...
  WARN(1L),
// ...
  FAIL(2L),
  ;

  public companion object {
// ...
    public fun fromValue(`value`: Long): Health? = entries.firstOrNull { it.value == value }
```

## Structs

<!-- excerpt: ridl/cabin.ridl -->

```ridl
/// Raised when a subsystem leaves `OK`.
struct Warning {
  /// The warning code, as a percentage of full scale.
  code: Level
  /// The health the subsystem moved to.
  health: Health
}
```

becomes a class with value equality. Its fields are already valid, because each
is a value object:

<!-- excerpt: build/generated/ridl/veh/cabin/Types.kt -->

```kotlin
public class Warning(
// ...
  public val code: Level,
// ...
  public val health: Health,
) {
  override fun equals(other: Any?): Boolean = this === other || other is Warning &&
```

## Interfaces

<!-- excerpt: ridl/cabin.ridl -->

```ridl
interface Cabin {
  /// The current cabin air temperature.
  signal temperature: Temperature @10ms
  /// Sent when a subsystem leaves `OK`.
  event warning: Warning @[100ms..1s]
  /// Sets the cabin level, below 100.
  command setLevel(level: Level) @[..50ms] [
    require level < 100
  ]
  /// The average level over the window, in milliseconds.
  query average(window: Window): Average @[..200ms] [
    require window > 0
    ensure result >= 0
  ]
}
```

An interface becomes several declarations in `Faces.kt`:

- **The descriptor**, `object Cabin`. It names the interface's catalog, its
  number and its members, holds `serve` and `serveAsync`, and gives the size
  table of each encoding the package carries a codec for, FlatBuffers only:
  `Cabin.sizes(Encoding.FlatBuffers)` holds each member's reservation and the
  buffer sizes a runtime needs. A runtime port is created for a catalog:
  `Loopback(Cabin.catalog)`, whose ports carry FlatBuffers unless another
  encoding is named.
- **The provider interface**, `CabinProvider`, which the application implements
  to answer commands and queries.
- **The publisher**, `CabinPublisher`, which the provider side uses to publish
  signals and raise events.
- **The blocking client**, `CabinClient`, and **the suspending client**,
  `CabinAsyncClient`, which the consumer side uses.

The provider interface has one method per command and query:

<!-- excerpt: build/generated/ridl/veh/cabin/Faces.kt -->

```kotlin
public interface CabinProvider {
  /**
   * Sets the cabin level, below 100.
   *
   * Serves command `setLevel`. A command has no failure the application reports (ridl §6.1); arguments that break their constraints or `require` never reach it.
   *
   * Rules:
   * - response within 50000 us
   * - require `level < 100`
   *
   * @param level
   * - range 0 ..= 100
   */
  public fun setLevel(level: Level)

  /**
   * The average level over the window, in milliseconds.
   *
   * Serves query `average`. A reply that breaks an `ensure` clause is discarded, and `ContractBroken` settled.
   *
   * Rules:
   * - response within 200000 us
   * - require `window > 0`
   * - ensure `result >= 0`
   *
   * @param window
   * - range 0 ..= 100000
   */
  public fun average(window: Window): Average
}
```

The contract clauses are checked by the generated code, not by your provider:
`require level < 100` refuses `setLevel(Level.of(100))` with
`PreconditionFailed` before `setLevel` runs, and a reply that breaks
`ensure result >= 0` is replaced by `ContractBroken`.

The publisher has one method per signal and event. A signal value is staged,
then published by `commit`; an event is raised at once:

<!-- excerpt: build/generated/ridl/veh/cabin/Faces.kt -->

```kotlin
public class CabinPublisher<W>(
  internal val port: W,
) where W : SignalWriter, W : EventSink, W : Encoded {
// ...
  public fun temperature(`value`: Temperature) {
// ...
  public fun warning(`value`: Warning) {
```

The two clients have the same members. The blocking one takes an optional
timeout:

<!-- excerpt: build/generated/ridl/veh/cabin/Faces.kt -->

```kotlin
public class CabinClient<P>(
  internal val port: P,
  timeout: TimeDuration? = null,
) where P : SignalReader, P : EventSource, P : Caller, P : Clock, P : Wakeable, P : Encoded {
// ...
  public fun temperature(): Sample<Temperature> = poll.temperature()
// ...
  public fun setLevel(level: Level) {
// ...
  public fun average(window: Window): Average = CabinAverageCall(this.port, window).block(this.timeoutBound)
}
```

<!-- excerpt: build/generated/ridl/veh/cabin/Faces.kt -->

```kotlin
public class CabinAsyncClient<P>(
  port: P,
  scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  grid: PollGrid = PollGrid.Default,
) where P : SignalReader, P : EventSource, P : Caller, P : Clock, P : Wakeable, P : Encoded {
// ...
  public suspend fun setLevel(level: Level) {
// ...
  public suspend fun average(window: Window): Average = this._calls.withLock { CabinAverageCall(this._port, window).await() }
}
```

The blocking client reads a signal with `temperature()`, which does not wait.
The suspending client has each signal as a shared state instead, `temperature`,
polled in `scope`, by default one the client owns, on `grid`; it is described in
[the coroutine flow](coroutine-flow.md):

<!-- excerpt: build/generated/ridl/veh/cabin/Faces.kt -->

```kotlin
public val <P> CabinAsyncClient<P>.temperature:
    SignalState<Temperature> where P : SignalReader, P : EventSource, P : Caller, P : Clock, P : Wakeable, P : Encoded
  get() = _signalStates.of(CabinTemperature, null, TemperatureCodec.maxSize) { raw, buf -> sampled(TemperatureCodec, CabinTemperature, raw, buf) }
```

An interface with signals alone, such as cabin's `Horn`, has a blocking client
and a suspending one, `HornAsyncClient`, which holds the states.

Some operations are extension functions in the same package rather than members:
`nextEvent`, `subscribe<Event>` and `unsubscribe<Event>` on the clients, the
blocking client's `timeout` property, each signal's state on the suspending
client, and `commit`, `invalidate<Signal>` and `touch<Signal>` on the publisher.
Import them by name, or import `veh.cabin.*`.

## Documentation

Each `///` comment of the `.ridl` source becomes the KDoc of what it documents,
and the generated KDoc adds what the source states about the item:

- **Links.** A `[Name]` link in a comment becomes a KDoc link to the generated
  Kotlin item: `[Level][veh.cabin.Level]`. A link to an item this package does
  not generate, an `internal` one, or a constant Kotlin cannot spell, becomes
  the qualified name in a code span instead, so no link dangles.
- **Rules.** A `Rules:` list holds every rule the source declares on the item,
  one per line, in the wording `ridl` itself uses: a range, a step, a unit, a
  length, a pattern, a timing, a response bound, a `require` or an `ensure`
  clause. The list states the contract; which of these the generated code checks
  is described in the sections above.
- **Interactions.** The comment and rules of a signal, an event, a command or a
  query go on each method you call or implement for it: the clients' methods,
  the provider's method and the publisher's method. For an event, that is
  `subscribe<Event>`. A call's parameter has its own comment and rules under
  `@param`.
- **Tags.** `@see` and `@since` become the KDoc tags of the same name, and
  `@deprecated` becomes Kotlin's `@Deprecated` annotation. `@labels` is not
  rendered.
