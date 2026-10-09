# The Kotlin plugin: a user guide

This guide takes a `.ridl` package to a running Kotlin client and provider. It
uses the cabin sample (`samples/cabin`) throughout. Every Kotlin and ridl block
below is an excerpt of that sample, of `cabin.ridl`, or of the code the plugin
generates for it, and `GuideTest` fails when one stops matching its source. The
excerpts come from ridl `editor-v0.7.0`, the release this repository pins in
`modules/conformance/ridl-release`, and from the plugin built from this
repository at the same commit as this file.

A `// ...` line in an excerpt stands for lines left out.

## 1. Setup

### The tools

- **ridl**, the toolchain. Install the pinned release with its install script;
  `RIDL_VERSION` names the release:

  ```sh
  curl -fsSL https://raw.githubusercontent.com/driftsys/ridl/editor-v0.7.0/install.sh \
    | RIDL_VERSION=editor-v0.7.0 bash
  ```

- **The plugin**, `ridlc-gen-kotlin`. It is not published to Maven: build its
  distribution from this repository with a JDK 17 or later.

  ```sh
  just dist
  # modules/ridlc-gen-kotlin/build/install/ridlc-gen-kotlin/bin/ridlc-gen-kotlin
  ```

### Running the plugin

`ridl build` runs a plugin once per package. Name the plugin by its language,
and `ridl` runs `ridlc-gen-kotlin` from your `PATH`, or give its path:

```sh
ridl build --plugin kotlin .
ridl build --plugin kotlin=path/to/bin/ridlc-gen-kotlin --out-dir out .
```

For each package, the plugin writes three files into the package's directory
under the output directory, `veh/cabin/` for package `veh.cabin`:

| File       | What it holds                                                                                   |
| ---------- | ----------------------------------------------------------------------------------------------- |
| `Types.kt` | one value object per declaration: scalars, enums, enum sets, structs, tuples, unions, constants |
| `Codec.kt` | the FlatBuffers codec of each payload: `encode`, `verify` and `decode`                          |
| `Faces.kt` | per interface: its descriptor, the clients, the provider interface, the publisher, `serve`      |

`Faces.kt` is written only for a package that declares an interface. The Kotlin
package is the ridl package's name. The plugin has a `kotlin-package` option,
but no `ridl build` flag sets a plugin option yet, so the name is always the
default.

### The Gradle build

The generated code compiles against the runtime libraries. Until they reach a
remote Maven repository, run `just publish-local` here and add `mavenLocal()`:

```kts
dependencies {
    implementation("io.github.driftsys.ridl:ridl-rt-kt:0.1.0-SNAPSHOT")
    // Faces.kt's suspending client and serveAsync.
    implementation("io.github.driftsys.ridl:ridl-rt-kt-coroutines:0.1.0-SNAPSHOT")
    // An in-process runtime, for tests and demos.
    implementation("io.github.driftsys.ridl:ridl-rt-kt-loopback:0.1.0-SNAPSHOT")
}
```

The sample generates its code in the build rather than checking it in: its
`generateCabin` task runs `ridl build --plugin kotlin=<plugin>` into
`build/generated/ridl`, and the main source set includes that directory. See
`samples/cabin/build.gradle.kts`.

### The runtime

A generated client or provider talks to a **port**, which a runtime provides.
This repository ships one runtime, `ridl-rt-kt-loopback`, which connects a
client and a provider in one process. It is what the sample and the tests use. A
runtime that crosses processes, such as the Binder runtime, lives in its own
repository and implements the same port interfaces of `ridl-rt-kt`.

## 2. From `.ridl` to Kotlin

### Constrained scalars

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

### Enums

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

### Structs

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

### Interfaces

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
  number and its members, and holds `serve` and `serveAsync`. A runtime port is
  created for a catalog: `Loopback(Cabin.catalog)`.
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
   * Serves command `setLevel`. A command has no failure the application reports (ridl §6.1); arguments that break their constraints or `require` never reach it.
   */
  public fun setLevel(level: Level)

  /**
   * Serves query `average`. A reply that breaks an `ensure` clause is discarded, and `ContractBroken` settled.
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
) where W : SignalWriter, W : EventSink {
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
) where P : SignalReader, P : EventSource, P : Caller, P : Clock, P : Wakeable {
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
  internal val port: P,
) where P : SignalReader, P : EventSource, P : Caller, P : Clock, P : Wakeable {
// ...
  public suspend fun setLevel(level: Level) {
// ...
  public suspend fun average(window: Window): Average = this.calls.withLock { CabinAverageCall(this.port, window).await() }
}
```

Some operations are extension functions in the same package rather than members:
`nextEvent`, `subscribe<Event>` and `unsubscribe<Event>` on the clients, the
blocking client's `timeout` property, and `commit`, `invalidate<Signal>` and
`touch<Signal>` on the publisher. Import them by name, or import `veh.cabin.*`.

## 3. The blocking flow

`samples/cabin/src/main/kotlin/ridl/sample/cabin/Main.kt` runs the four
interaction kinds over one loopback, with the provider on a thread of its own.

The provider implements `CabinProvider`:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/Main.kt -->

```kotlin
class CabinService(private val average: Long) : CabinProvider {
    val levels: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

    override fun setLevel(level: Level) {
        levels += level.value
    }

    override fun average(window: Window): Average = Average.of(average)
}
```

One port, a handler handle for the provider, and the client:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/Main.kt -->

```kotlin
val port = Loopback(Cabin.catalog)
val handler = port.handler()
val service = CabinService(average = 7)
// serve returns at each short timeout, so the flag stops the provider within one.
val running = AtomicBoolean(true)
val provider = thread(name = "cabin-provider") { while (running.get()) Cabin.serve(handler, service, 100.milliseconds) }
val client = CabinClient(port, timeout = 5.seconds)
```

`Cabin.serve` settles claims on the calling thread until its timeout passes;
with no timeout it returns only by throwing. The loop lets the sample stop it.

A signal is published, then read. A read does not wait: it returns the latest
value with its provenance:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/Main.kt -->

```kotlin
CabinPublisher(port).apply {
    temperature(Temperature.of(21))
    commit()
}
val sample = client.temperature()
check(sample.value == Temperature.of(21) && sample.provenance == Provenance.Live) { "signal: $sample" }
```

An event is delivered once the client has subscribed:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/Main.kt -->

```kotlin
client.subscribeWarning()
CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
val event = client.nextEvent() as? Cabin.Event.Warning ?: error("event: no occurrence")
val warning = event.occurrence.payload.getOrThrow()
```

A command and a query wait for their outcome:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/Main.kt -->

```kotlin
client.setLevel(Level.of(42))
val average = client.average(Window.of(10))
```

A command returns once it is delivered, before the provider's method has run
(ridl §6.1). A query returns the provider's reply.

## 4. The coroutine flow

`samples/cabin/src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt` runs the
calls that wait again, with `CabinAsyncClient` and `Cabin.serveAsync`:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt -->

```kotlin
val port = Loopback(Cabin.catalog)
val client = CabinAsyncClient(port)
val service = CabinService(average = 7)
val handler = port.handler()
val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, service) }
```

`serveAsync` suspends between claims and never returns normally: it ends by
throwing, or when its coroutine is cancelled. The sample stops it with
`provider.cancelAndJoin()`.

The calls are the blocking ones, suspended:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt -->

```kotlin
        lines += "coroutine query ok ${client.average(Window.of(10)).value}"
// ...
        client.setLevel(Level.of(42))

        client.subscribeWarning()
        CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
        val event = client.nextEvent() as Cabin.Event.Warning
```

The suspending `nextEvent` waits until an occurrence arrives, so it returns
`Cabin.Event`, never `null`.

## 5. Blocking or suspending

|              | `CabinClient`                                                                         | `CabinAsyncClient`                                                                    |
| ------------ | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| A call       | blocks the calling thread until its outcome                                           | suspends until its outcome                                                            |
| Timeout      | `timeout` on the client, for every call and `nextEvent`; `null` waits without a bound | none of its own: wrap the call in `withTimeout`                                       |
| Cancellation | none: a call ends with its outcome, its timeout or the member's deadline              | cancelling the coroutine forgets the call                                             |
| `nextEvent`  | returns `null` at the timeout                                                         | suspends until an occurrence; concurrent calls take occurrences one at a time         |
| Concurrency  | use it from one thread at a time                                                      | one call at a time: a second call waits for the first; use a second client to overlap |
| Serving      | `Cabin.serve(handler, provider, timeout)`, on a thread of its own                     | `Cabin.serveAsync(handler, provider)`, in a coroutine                                 |

Whichever you pick, each command and query also has its own deadline, the
response bound the `.ridl` file declares (`@[..50ms]` for `setLevel`), or 1 s
for an untimed command and 3 s for an untimed query. Past it, a call fails even
when the client's timeout is longer.

Pick the blocking client for code that already runs on its own threads, such as
a command-line tool or a test. Pick the suspending client when the application
uses coroutines, so that a wait does not hold a thread.

## 6. Errors

| Thrown                  | By                                             | When                                                                                                                                                                                           |
| ----------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ConstraintViolation`   | `of` on a value object, a struct's constructor | the value breaks a constraint of its type                                                                                                                                                      |
| `ClientError.Send`      | a client call                                  | the call was not sent, for example `SendError.Busy` until the member's deadline                                                                                                                |
| `ClientError.Call`      | a client call                                  | the outcome is a failure: `Contract.PreconditionFailed`, `Contract.ContractBroken`, `Transport.Timeout` for a query past its deadline, `Transport.Undelivered` for a command past its deadline |
| `ClientError.Read`      | a client call, `nextEvent`                     | the port failed while the outcome was read                                                                                                                                                     |
| `ProviderError.Serve`   | `serve`, `serveAsync`                          | the handler refused the interface's members                                                                                                                                                    |
| `ProviderError.Claim`   | `serve`, `serveAsync`                          | the handler failed while a claim was read                                                                                                                                                      |
| `IllegalStateException` | a client's or publisher's constructor, `serve` | the port is attached to another catalog than the one the code was generated from                                                                                                               |

An exception your provider throws is not caught: it leaves `serve` or
`serveAsync` unchanged. A program that must not throw on a catalog mismatch
compares `port.catalog == Cabin.catalog` before it builds a client.

## Further reading

- [`modules/ridlc-gen-kotlin/README.md`](../modules/ridlc-gen-kotlin/README.md):
  every rule the generated code follows, and where it departs from the design.
- [`docs/design.md`](design.md): the design of the plugin, the runtime contract,
  the value objects, the codec and the faces.
- [The ridl book](https://driftsys.github.io/ridl/): the language, and how
  `ridl` runs a plugin.
