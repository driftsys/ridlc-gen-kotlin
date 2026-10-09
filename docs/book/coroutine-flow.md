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
