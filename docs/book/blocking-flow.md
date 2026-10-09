# The blocking flow

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
