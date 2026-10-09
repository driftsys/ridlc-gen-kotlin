# The coroutine flow

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

## A signal as a flow

The suspending client reads a signal as a `Flow` of samples. The flow emits the
current sample at once, then each sample that differs from the last one emitted:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt -->

```kotlin
val temperatures = client.temperatureFlow()
    .onEach {
        if (it.value == Temperature.of(21)) {
            publisher.temperature(Temperature.of(22))
            publisher.commit()
        }
    }
    .take(2)
    .toList()
```

The flow reads the signal at its declared rate: every 10 ms for `temperature`,
declared `@10ms`. For a range, it reads at the lower bound, the rate floor under
which faster updates are coalesced, so no update is missed. A signal with no
lower bound is read at its staleness bound, and one with no timing at all at
ridl's default floor of 100 ms.

A sample is emitted when its value, its provenance or its envelope changes, so
every publication is emitted, a `touch` that re-affirms the same value included.
A sample is also emitted when it turns stale, once, and when it turns fresh
again. Apply `distinctUntilChangedBy { it.value }` to see only changes of the
value. The flow never completes; cancelling the collecting coroutine stops it.

## The calls

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
