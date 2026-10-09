# The coroutine flow

`samples/cabin/src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt` runs the
calls that wait again, with `CabinAsyncClient` and `Cabin.serveAsync`:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt -->

```kotlin
val port = Loopback(Cabin.catalog)
val client = CabinAsyncClient(port, signals)
val service = CabinService(average = 7)
val handler = port.handler()
val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, service) }
```

`signals` is the scope the client's signal states poll in. The sample makes one
on `Dispatchers.Default` and cancels it once it is done; an application passes a
scope that lives as long as the client, never the main thread's.

`serveAsync` suspends between claims and never returns normally: it ends by
throwing, or when its coroutine is cancelled. The sample stops it with
`provider.cancelAndJoin()`.

## A signal as a state

The suspending client has each signal as a `SignalState`: a `StateFlow` of its
samples, shared by every collector. Collecting it emits the current sample at
once, then each sample that differs from the last one emitted:

<!-- excerpt: src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt -->

```kotlin
val temperatures = client.temperature
    .onEach {
        if (it.value == Temperature.of(21)) {
            publisher.temperature(Temperature.of(22))
            publisher.commit()
        }
    }
    .take(2)
    .toList()
```

The state polls the port while it has a collector, and stops 3 s after the last
one left; while nothing collects it, nothing runs. It reads at the signal's rate
floor, the lower bound under which faster updates are coalesced: every 10 ms for
`temperature`, declared `@10ms`. The floor is rounded up to a multiple of 10 ms
and capped at the staleness bound. A signal with no floor is read at half its
staleness bound, and one with no timing at all at ridl's default floor of 100
ms. Every state polls at the multiples of its period on one grid shared by the
process, so states of compatible periods, 100 ms and 300 ms say, read in the
same slot.

A sample is emitted when its value, its provenance or its envelope changes, so
every publication is emitted, a `touch` that re-affirms the same value included.
A sample is also emitted when it turns stale, once, and when it turns fresh
again. A publication already decoded is not decoded again.

The rest of the state:

- `client.temperature.value` is the last sample polled while the state polls,
  and a fresh read of the port otherwise. `client.temperature.read()` always
  reads the port.
- `client.temperature.every(1.seconds)` is the same signal polled every second:
  another shared state. A period faster than the rate floor is the floor, so
  `every(1.milliseconds)` is `client.temperature` itself.
- `client.temperature.values` is a `StateFlow` of the values alone, which emits
  only when the value changes, over the same polling.

The blocking `CabinClient` keeps `temperature()`, a read of the port.

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
