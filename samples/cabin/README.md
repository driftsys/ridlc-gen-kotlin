# cabin

## Responsibility

This sample is the JVM demonstration (docs/design.md §6): the ridl repository's
`examples/cabin` package, generated into Kotlin by the pinned `ridl` running
this repository's plugin, with a Kotlin consumer and a Kotlin provider
round-tripping over
[`ridl-rt-kt-loopback`](../../modules/ridl-rt-kt-loopback/README.md). It is the
Kotlin twin of that repository's `examples/cabin/consumer`. The repository is
licensed under the root [MIT License](../../LICENSE).

- `ridl/` is the package: `cabin.ridl` and `ridl.toml`, copied from
  `examples/cabin` at the pinned release.
- The `generateCabin` task runs
  `ridl build --plugin kotlin=<the installed
  plugin>` over it into
  `build/generated/ridl`, which is the generated half of the main source set;
  nothing generated is tracked.
- `src/main/kotlin/ridl/sample/cabin/Main.kt` is the application: one loopback,
  the provider on a thread of its own running `Cabin.serve`, and the consumer's
  four round trips, a signal, an event, a command and a query, through the
  blocking `CabinClient`, each printing the value it carried.
- `src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt` runs the round trips that
  wait again through the suspending `CabinAsyncClient`, with `Cabin.serveAsync`
  as the provider. The sample uses only the public clients and `serve`; the poll
  face and `dispatch` they are built on are internal.

```sh
just demo
```

## Status

Stages K4 and K5. `just demo` prints `signal ok 21`, `event ok 5`,
`command ok 42` and `query ok 7`, then `coroutine query ok 7`,
`coroutine command ok 42` and `coroutine event ok 5`, and `DemoTest` pins those
seven lines.
