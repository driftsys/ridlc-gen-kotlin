# Changelog

## [0.2.0] (2026-10-13)

### Features

- **ridlc-gen-kotlin:** give an internal interface its descriptors and no face
  ([4ea99bb])
- **ridlc-gen-kotlin:** send a call's induced argument and reply structs
  ([158d10c])
- **ridl-rt-kt:** a watermark has no sequence number ([c58fd9a]), refs [#94]
- **repo:** a size table per codec, and a face that reads its encoding from its
  port ([9efde73])
- **ridl-rt-kt:** a violation a peer reports has no type name ([d914ba8])
- **ridlc-gen-kotlin:** generate the types alone when no encoding is named
  ([8c9e4ba]), refs [#83]
- **ridlc-gen-kotlin:** render doc links, rules, see and since in KDoc
  ([6ab07e8]), closes [#86]
- **ridlc-gen-kotlin:** read the ridl 0.10 codegen schema ([4114aae]), refs
  [#88]
- **ridlc-gen-kotlin:** read the encodings option ridl 0.9 sends ([baa8ddc]),
  refs [#83]
- **ridlc-gen-kotlin:** each signal is a shared state on the async client
  ([56a45ab])
- **ridl-rt-kt-coroutines:** a signal as a shared state on a polling grid
  ([5bb5c05])

### Bug Fixes

- **samples:** the seqlock port states its encoding ([74eb785])
- **ridl-rt-kt-coroutines:** read the port when a polled sample is too old
  ([5ab1d81]), closes [#78]
- **ridlc-gen-kotlin:** keep a signal read's sentence whole around its doc
  ([baac551]), refs [#86]
- **ridl-rt-kt:** a fixed member reserves nothing ([964dde4])
- **conformance:** rerun the tests when the ridl binary changes ([6194200])
- **ridl-rt-kt-coroutines:** stop a slow state after its linger, and fail
  collectors a scope ends ([b51ef69])
- **ridl-rt-kt-coroutines:** remember an idle state weakly, and outlive the
  scope's end ([6c8fad5])
- **ridlc-gen-kotlin:** check the async client's catalog without a poll client
  ([2ef9f8e])
- **ridl-rt-kt-coroutines:** close the signal-state races of the second review
  ([7bd63c3])
- **ridlc-gen-kotlin:** default scope and unshadowable members on the async
  client ([4108c5a])
- **ridl-rt-kt-coroutines:** harden signal states after review ([8ac9cee])

### Documentation

- **ridl-rt-kt:** record the ridl-rt 0.10 items Kotlin spells another way
  ([1e0aaef])
- **ridl-rt-kt:** say which transports leave a violation without a type name
  ([b2aed1d])
- **docs:** the linger holds for every period, and a scope's end fails
  collectors ([7708459])
- **docs:** a background scope for the signal states, and what a poll reads
  ([094f783])
- **ridl-rt-kt-coroutines:** the age of a cached value in the README ([e18a66e])
- **ridl-rt-kt-coroutines:** the age of a cached signal value ([16ca606])
- **docs:** the async client's default scope in the book and the demo
  ([6ea4b3f])
- **docs:** read the cabin signal as a shared state ([f997f02])
- **repo:** format the 0.1.0 changelog ([7c97e46])

### Refactoring

- **ridl-rt-kt-coroutines:** one coroutine per polling, and drop a state that
  stopped ([d721163]), closes 80 and #79.

A `SignalState` now has one coroutine for the whole of a polling: it sleeps
until the next slot or the end of the linger, whichever comes first, polls on a
slot and stops at the end. The stopper job and the polling flag are gone; the
state keeps its subscriber count, the poller and the moment the last subscriber
left. The five races the two reviews of #77 found had no second coroutine left
to race with.

The rebuild on `shareIn` or `WhileSubscribed` that #80 proposed does not fit
three of the rules `SignalStateTest` pins, and the module README records why:
the sharing coroutine waits for the scope's life, a `MutableStateFlow` hands a
new collector its current value before any start command runs, and
`WhileSubscribed` issues a start only after a stop, so a collector back within
the linger after a failure would wait it out. All 26 tests pass unchanged.

Eviction follows from the one place a state goes idle (#79): a state leaves
`SignalStates` when its polling stops or fails, unless it is its signal's
default-period state, which stays so a generated property is one instance. A
dropped state a caller holds keeps working and is shared again when collected
again. A state never read holds no buffer. Three tests cover the slider case,
the held state and the failed one.

- **ridl-rt-kt-coroutines:** name the staleness bound `bound` in effectivePeriod
  ([208fb20])
- **ridl-rt-kt-coroutines:** spell effectivePeriod as its four cases ([3ff3d2b])

### BREAKING CHANGES

- an `internal` interface no longer has an `internal` face.
- `ridl.rt.port.Watermark` has no `seq`. Drop the argument.
- a port a generated face is built over implements `ridl.rt.port.Encoded`;
  `EncodedSizes`, `PayloadInfo.maxSize`, `Encoding.maxSize`,
  `Member.reservation` and `Unsized` are removed; `tableBudget` takes an
  interface; a descriptor has `sizes(encoding)` in place of `MAX_BUFFER_SIZE`
  and `EVENT_SOURCE_BUFFER_SIZE`.
- `ridl.rt.payload.Violation.typeName` is `String?`.
- a package whose manifest names no encoding gets no Codec.kt and no Faces.kt;
  name `flatbuffers` in [backend.kotlin].
- the `wire-encoding` option is refused as unknown; name the encoding with
  `encodings`.

[0.2.0]: https://github.com/driftsys/ridlc-gen-kotlin/compare/v0.1.0...v0.2.0
[4ea99bb]: https://github.com/driftsys/ridlc-gen-kotlin/commit/4ea99bb
[158d10c]: https://github.com/driftsys/ridlc-gen-kotlin/commit/158d10c
[c58fd9a]: https://github.com/driftsys/ridlc-gen-kotlin/commit/c58fd9a
[#94]: https://github.com/driftsys/ridlc-gen-kotlin/issues/94
[9efde73]: https://github.com/driftsys/ridlc-gen-kotlin/commit/9efde73
[d914ba8]: https://github.com/driftsys/ridlc-gen-kotlin/commit/d914ba8
[8c9e4ba]: https://github.com/driftsys/ridlc-gen-kotlin/commit/8c9e4ba
[#83]: https://github.com/driftsys/ridlc-gen-kotlin/issues/83
[6ab07e8]: https://github.com/driftsys/ridlc-gen-kotlin/commit/6ab07e8
[#86]: https://github.com/driftsys/ridlc-gen-kotlin/issues/86
[4114aae]: https://github.com/driftsys/ridlc-gen-kotlin/commit/4114aae
[#88]: https://github.com/driftsys/ridlc-gen-kotlin/issues/88
[baa8ddc]: https://github.com/driftsys/ridlc-gen-kotlin/commit/baa8ddc
[56a45ab]: https://github.com/driftsys/ridlc-gen-kotlin/commit/56a45ab
[5bb5c05]: https://github.com/driftsys/ridlc-gen-kotlin/commit/5bb5c05
[74eb785]: https://github.com/driftsys/ridlc-gen-kotlin/commit/74eb785
[5ab1d81]: https://github.com/driftsys/ridlc-gen-kotlin/commit/5ab1d81
[#78]: https://github.com/driftsys/ridlc-gen-kotlin/issues/78
[baac551]: https://github.com/driftsys/ridlc-gen-kotlin/commit/baac551
[964dde4]: https://github.com/driftsys/ridlc-gen-kotlin/commit/964dde4
[6194200]: https://github.com/driftsys/ridlc-gen-kotlin/commit/6194200
[b51ef69]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b51ef69
[6c8fad5]: https://github.com/driftsys/ridlc-gen-kotlin/commit/6c8fad5
[2ef9f8e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/2ef9f8e
[7bd63c3]: https://github.com/driftsys/ridlc-gen-kotlin/commit/7bd63c3
[4108c5a]: https://github.com/driftsys/ridlc-gen-kotlin/commit/4108c5a
[8ac9cee]: https://github.com/driftsys/ridlc-gen-kotlin/commit/8ac9cee
[1e0aaef]: https://github.com/driftsys/ridlc-gen-kotlin/commit/1e0aaef
[b2aed1d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b2aed1d
[7708459]: https://github.com/driftsys/ridlc-gen-kotlin/commit/7708459
[094f783]: https://github.com/driftsys/ridlc-gen-kotlin/commit/094f783
[e18a66e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/e18a66e
[16ca606]: https://github.com/driftsys/ridlc-gen-kotlin/commit/16ca606
[6ea4b3f]: https://github.com/driftsys/ridlc-gen-kotlin/commit/6ea4b3f
[f997f02]: https://github.com/driftsys/ridlc-gen-kotlin/commit/f997f02
[7c97e46]: https://github.com/driftsys/ridlc-gen-kotlin/commit/7c97e46
[d721163]: https://github.com/driftsys/ridlc-gen-kotlin/commit/d721163
[208fb20]: https://github.com/driftsys/ridlc-gen-kotlin/commit/208fb20
[3ff3d2b]: https://github.com/driftsys/ridlc-gen-kotlin/commit/3ff3d2b

## 0.1.0 (2026-10-09)

### Bug Fixes

- **repo:** make the release re-runnable and the new recipes portable
  ([6fe594d])
- **conformance:** list the Rust verdict packages from the corpus ([a47fa94]),
  closes [#49]
- **ridlc-gen-kotlin:** compare a bare bytes map key by content again
  ([ea1c526]), closes [#50]
- **ridlc-gen-kotlin:** check an integer step of 2^63 or more exactly
  ([cfd7ffc])
- **ridlc-gen-kotlin:** name each descriptor's catalog after the unit
  ([89a5978]), closes [#55]
- **ridlc-gen-kotlin:** scope each map's key check, and compare f32 keys as f32
  ([0eea31b])
- **ridlc-gen-kotlin:** refuse map keys that verify takes for one ([86b3ade])
- **repo:** never use what a Buffer method returns, for Android ([22abb11])
- **ridlc-gen-kotlin:** refuse a pattern java.util.regex cannot compile
  ([541c73d]), closes [#12]
- **ridlc-gen-kotlin:** read an absent scalar or enum field as its FlatBuffers
  default ([6e94f3c])
- **ridlc-gen-kotlin:** spell the plugin's own names out of reach of ridl names
  ([91d9f27]), refs [#16]
- **ridlc-gen-kotlin:** bound the claims one serve pass takes ([f64f8a7])
- **ridlc-gen-kotlin:** settle an oversized claim Corrupt in dispatch
  ([20e015e]), closes [#10]
- **ridlc-gen-kotlin:** resolve foreign references by package ([e492f60])
- **ridlc-gen-kotlin:** bound serve, serialise nextEvent and guard generated
  names ([92dcb73])
- **ridl-rt-kt:** make blockOn's waker inert once the wait returns ([678d80e])
- **ridlc-gen-kotlin:** keep the clients' errors and names apart from the
  members' ([8025648])
- **ridl-rt-kt-loopback:** withdraw a forgotten call no handler has claimed
  ([27b3582])
- **ridl-rt-kt-conformance:** accept a withdrawn call on a forget before any
  claim ([29535e1])
- **ridl-rt-kt-loopback:** store one waker per kind of key and return a closed
  handler's claims ([44b072e])
- **ridlc-gen-kotlin:** read an empty signal as its init value only under Init
  or Invalid(Declared) ([1b7e515])
- **ci:** harden scaffold checks and base resolution ([b463719])

### Refactoring

- **ridlc-gen-kotlin:** read TypeRef.foreign again ([a01c09d])
- **ridlc-gen-kotlin:** remove the per-interface AIDL emission ([50b72e9]),
  closes [#4]

### Documentation

- **docs:** describe the signal flows in the book ([48471c7])
- **docs:** record O-K1's disposition and bring the design note up to date
  ([52b080d])
- **repo:** turn the user guide into a book published on each release
  ([ffb011d])
- **docs:** add a user guide for the plugin and its generated code ([b554009]),
  closes [#47]
- **ridlc-gen-kotlin:** name the three copied schema files and what the plugin
  ignores ([b2cf70b])
- **repo:** record the release steps and the unmarked ShortClaim break
  ([d1c935b]), closes [#14]
- **ridl-rt-kt-coroutines:** say awaitPoll's yield also applies to a client
  ([04d4149]), closes [#15]
- **conformance:** note what regenerating the codec verdicts needs today
  ([20e11b5])
- **ridlc-gen-kotlin:** record the clients and serve ([a626afb]), closes [#7]
- **docs:** plan the interaction clients and serve ([087e90e])
- **docs:** design the interaction clients and serve ([49a7d1c])
- **ridlc-gen-kotlin:** drop the note on the fixed [bool] codec defect
  ([a228099])
- **docs:** the design note is this repository's only record ([804ba65])
- **docs:** add the design note ([37cab2e])
- **repo:** describe generator and runtime modules ([cf2aa7c])
- **repo:** plan the multi-module scaffold ([ad02b09])
- **repo:** define multi-module generator repository ([1b28a0d])

### Features

- **samples:** read the cabin signal as a flow in the coroutine demo ([628d9af])
- **ridlc-gen-kotlin:** a Flow of each signal on the suspending client
  ([bc537b8])
- **ridl-rt-kt-coroutines:** read a signal as a cold Flow ([59806c4])
- **ridl-rt-kt:** carry an optional trace context on calls and events
  ([bb1f05e])
- **ridlc-gen-kotlin:** start every file with the marker and the header
  ([4d39250])
- **repo:** follow ridl 0.6.0 ([3bed179])
- **ridlc-gen-kotlin:** check the port's catalog once per binding ([7ae6f8c]),
  closes [#27]
- **repo:** follow ridl 0.5.1 ([9a64ef0])
- **ridlc-gen-kotlin:** write the model's catalog hash and refuse a malformed
  one ([d6ffe64]), closes [#26]
- **ridl-rt-kt-loopback:** add Loopback.attach ([b1dccb3])
- **ridlc-gen-kotlin:** refuse two generated names one Kotlin namespace cannot
  hold ([f891725]), closes [#16], [#18]
- **ridl-rt-kt:** add flagWaker and WakeFlag ([3f6564b])
- **ridlc-gen-kotlin:** move the face's fixed and derived operations to
  extensions ([c785ec6]), closes [#9]
- **ridl-rt-kt:** report an oversized claim with its id ([b748a3d]), refs [#10]
- **samples:** run the cabin demo through the generated clients and serve
  ([5de1245]), refs [#7]
- **ridlc-gen-kotlin:** skip an interface whose member names collide with the
  clients ([65821f3]), refs 7 and driftsys/ridl#570
- **ridlc-gen-kotlin:** generate the async client and serveAsync ([4640b60])
- **ridlc-gen-kotlin:** generate the blocking client and serve ([fb7c65b]), refs
  [#7]
- **ridlc-gen-kotlin:** generate a call object per command and query ([2d655d3])
- **ridlc-gen-kotlin:** make the poll face internal ([c8a4fe8])
- **ridl-rt-kt-coroutines:** add awaitPoll, the suspending twin of blockOn
  ([e0ab310]), refs [#7]
- **ridl-rt-kt-loopback:** move the call table onto correlate.Table ([b0082e7])
- **ridl-rt-kt:** add the correlation table, the waiter registry and the
  composed errors ([1346175]), closes [#6]
- **ridl-rt-kt-conformance:** add the port contract suite as a module
  ([69a2561]), closes [#8]
- **ridl-rt-kt:** add the freshness, event loss, budget and deadline helpers
  ([27739e7]), refs [#6]
- **ridl-rt-kt:** key Wakeable by Interest, add Transport.Busy and blockOn
  ([c9b8a15]), closes [#5]
- **ridl-rt-kt-coroutines:** add the suspending await over Wakeable ([c5e7e98])
- **samples:** add the cabin JVM demo ([ef637ab])
- **ridlc-gen-kotlin:** generate the AIDL binding of the frame ([c934b8a])
- **ridlc-gen-kotlin:** generate the interaction faces into Faces.kt ([29e5752])
- **ridlc-gen-kotlin:** generate the FlatBuffers codecs into Codec.kt
  ([0e6a0ae])
- **ridl-rt-kt:** add the FlatBuffers reader and builder (K1b spike) ([670387d])
- **ridlc-gen-kotlin:** generate the value objects into Types.kt ([d0c3c31])
- **ridl-rt-kt-loopback:** add the in-process reference runtime ([498ccd2])
- **ridlc-gen-kotlin:** read the request and answer it ([3573753])
- **ridl-rt-kt:** spell the ridl-rt correspondence table ([9e631f9])

### BREAKING CHANGES

- every Caller and EventSink implementation, and every call to command, query or
  raise, takes the trace argument, and every Claim, RawOccurrence and
  ReadError.ShortClaim built by hand takes the trace field.
- a face generated over ridl 0.6.0 carries the real catalog hash, so it refuses
  a port attached to the all-zero catalog of an earlier build, and an untimed
  command or query now lapses after 1 s or 3 s unless the package sets
  [defaults] command_timing or query_timing.
- a generated client or publisher constructed over a port attached to a catalog
  other than the one its face was generated from, and serve or serveAsync over
  such a handler, now throws IllegalStateException. Attach the runtime to the
  generated `<Iface>.catalog`, or compare `port.catalog` with it before binding.
- ridl.rt.payload.Rule gains Unique, so a `when` over Rule with no else must
  name it. A step-only float scalar now has a private constructor and `of`, as
  the model no longer calls it vacuous, and a scalar whose checks are all empty
  has a public constructor and no `of`.
- a package whose face was skipped with a warning because a generated type's
  name was taken is now refused with an error.
- `object Constants` is `object Constants_`; a Java caller of the face's
  extensions names `FacesKt_`; an enum value or bit named as above is spelled
  with one more `_`.
- a consumer outside the generated package imports the extensions it calls, by
  name or with `<package>.*`.
- **ridl-rt-kt:** `ReadError.ShortClaim` (b748a3d,
  driftsys/ridlc-gen-kotlin#14). `ReadError` is a sealed class, so an exhaustive
  `when` over it no longer compiles, and a `Handler` whose `nextClaim` reports
  an oversized claim with `Short` no longer meets the port contract: it reports
  `ShortClaim` with the claim's id. Rust's `ReadError` is `#[non_exhaustive]`,
  so the same change was not breaking there.
- **ridlc-gen-kotlin:** a package with a signal depends on
  `ridl-rt-kt-coroutines` and `kotlinx-coroutines-core` (#74). A signal-only
  interface now has a suspending client with a `Flow` of each signal, so a
  consumer of such a package without those dependencies no longer compiles.

[6fe594d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/6fe594d
[a47fa94]: https://github.com/driftsys/ridlc-gen-kotlin/commit/a47fa94
[#49]: https://github.com/driftsys/ridlc-gen-kotlin/issues/49
[ea1c526]: https://github.com/driftsys/ridlc-gen-kotlin/commit/ea1c526
[#50]: https://github.com/driftsys/ridlc-gen-kotlin/issues/50
[cfd7ffc]: https://github.com/driftsys/ridlc-gen-kotlin/commit/cfd7ffc
[89a5978]: https://github.com/driftsys/ridlc-gen-kotlin/commit/89a5978
[#55]: https://github.com/driftsys/ridlc-gen-kotlin/issues/55
[0eea31b]: https://github.com/driftsys/ridlc-gen-kotlin/commit/0eea31b
[86b3ade]: https://github.com/driftsys/ridlc-gen-kotlin/commit/86b3ade
[22abb11]: https://github.com/driftsys/ridlc-gen-kotlin/commit/22abb11
[541c73d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/541c73d
[#12]: https://github.com/driftsys/ridlc-gen-kotlin/issues/12
[6e94f3c]: https://github.com/driftsys/ridlc-gen-kotlin/commit/6e94f3c
[91d9f27]: https://github.com/driftsys/ridlc-gen-kotlin/commit/91d9f27
[#16]: https://github.com/driftsys/ridlc-gen-kotlin/issues/16
[f64f8a7]: https://github.com/driftsys/ridlc-gen-kotlin/commit/f64f8a7
[20e015e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/20e015e
[#10]: https://github.com/driftsys/ridlc-gen-kotlin/issues/10
[e492f60]: https://github.com/driftsys/ridlc-gen-kotlin/commit/e492f60
[92dcb73]: https://github.com/driftsys/ridlc-gen-kotlin/commit/92dcb73
[678d80e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/678d80e
[8025648]: https://github.com/driftsys/ridlc-gen-kotlin/commit/8025648
[27b3582]: https://github.com/driftsys/ridlc-gen-kotlin/commit/27b3582
[29535e1]: https://github.com/driftsys/ridlc-gen-kotlin/commit/29535e1
[44b072e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/44b072e
[1b7e515]: https://github.com/driftsys/ridlc-gen-kotlin/commit/1b7e515
[b463719]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b463719
[a01c09d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/a01c09d
[50b72e9]: https://github.com/driftsys/ridlc-gen-kotlin/commit/50b72e9
[#4]: https://github.com/driftsys/ridlc-gen-kotlin/issues/4
[48471c7]: https://github.com/driftsys/ridlc-gen-kotlin/commit/48471c7
[52b080d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/52b080d
[ffb011d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/ffb011d
[b554009]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b554009
[#47]: https://github.com/driftsys/ridlc-gen-kotlin/issues/47
[b2cf70b]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b2cf70b
[d1c935b]: https://github.com/driftsys/ridlc-gen-kotlin/commit/d1c935b
[#14]: https://github.com/driftsys/ridlc-gen-kotlin/issues/14
[04d4149]: https://github.com/driftsys/ridlc-gen-kotlin/commit/04d4149
[#15]: https://github.com/driftsys/ridlc-gen-kotlin/issues/15
[20e11b5]: https://github.com/driftsys/ridlc-gen-kotlin/commit/20e11b5
[a626afb]: https://github.com/driftsys/ridlc-gen-kotlin/commit/a626afb
[#7]: https://github.com/driftsys/ridlc-gen-kotlin/issues/7
[087e90e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/087e90e
[49a7d1c]: https://github.com/driftsys/ridlc-gen-kotlin/commit/49a7d1c
[a228099]: https://github.com/driftsys/ridlc-gen-kotlin/commit/a228099
[804ba65]: https://github.com/driftsys/ridlc-gen-kotlin/commit/804ba65
[37cab2e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/37cab2e
[cf2aa7c]: https://github.com/driftsys/ridlc-gen-kotlin/commit/cf2aa7c
[ad02b09]: https://github.com/driftsys/ridlc-gen-kotlin/commit/ad02b09
[1b28a0d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/1b28a0d
[628d9af]: https://github.com/driftsys/ridlc-gen-kotlin/commit/628d9af
[bc537b8]: https://github.com/driftsys/ridlc-gen-kotlin/commit/bc537b8
[59806c4]: https://github.com/driftsys/ridlc-gen-kotlin/commit/59806c4
[bb1f05e]: https://github.com/driftsys/ridlc-gen-kotlin/commit/bb1f05e
[4d39250]: https://github.com/driftsys/ridlc-gen-kotlin/commit/4d39250
[3bed179]: https://github.com/driftsys/ridlc-gen-kotlin/commit/3bed179
[7ae6f8c]: https://github.com/driftsys/ridlc-gen-kotlin/commit/7ae6f8c
[#27]: https://github.com/driftsys/ridlc-gen-kotlin/issues/27
[9a64ef0]: https://github.com/driftsys/ridlc-gen-kotlin/commit/9a64ef0
[d6ffe64]: https://github.com/driftsys/ridlc-gen-kotlin/commit/d6ffe64
[#26]: https://github.com/driftsys/ridlc-gen-kotlin/issues/26
[b1dccb3]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b1dccb3
[f891725]: https://github.com/driftsys/ridlc-gen-kotlin/commit/f891725
[#18]: https://github.com/driftsys/ridlc-gen-kotlin/issues/18
[3f6564b]: https://github.com/driftsys/ridlc-gen-kotlin/commit/3f6564b
[c785ec6]: https://github.com/driftsys/ridlc-gen-kotlin/commit/c785ec6
[#9]: https://github.com/driftsys/ridlc-gen-kotlin/issues/9
[b748a3d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b748a3d
[5de1245]: https://github.com/driftsys/ridlc-gen-kotlin/commit/5de1245
[65821f3]: https://github.com/driftsys/ridlc-gen-kotlin/commit/65821f3
[4640b60]: https://github.com/driftsys/ridlc-gen-kotlin/commit/4640b60
[fb7c65b]: https://github.com/driftsys/ridlc-gen-kotlin/commit/fb7c65b
[2d655d3]: https://github.com/driftsys/ridlc-gen-kotlin/commit/2d655d3
[c8a4fe8]: https://github.com/driftsys/ridlc-gen-kotlin/commit/c8a4fe8
[e0ab310]: https://github.com/driftsys/ridlc-gen-kotlin/commit/e0ab310
[b0082e7]: https://github.com/driftsys/ridlc-gen-kotlin/commit/b0082e7
[1346175]: https://github.com/driftsys/ridlc-gen-kotlin/commit/1346175
[#6]: https://github.com/driftsys/ridlc-gen-kotlin/issues/6
[69a2561]: https://github.com/driftsys/ridlc-gen-kotlin/commit/69a2561
[#8]: https://github.com/driftsys/ridlc-gen-kotlin/issues/8
[27739e7]: https://github.com/driftsys/ridlc-gen-kotlin/commit/27739e7
[c9b8a15]: https://github.com/driftsys/ridlc-gen-kotlin/commit/c9b8a15
[#5]: https://github.com/driftsys/ridlc-gen-kotlin/issues/5
[c5e7e98]: https://github.com/driftsys/ridlc-gen-kotlin/commit/c5e7e98
[ef637ab]: https://github.com/driftsys/ridlc-gen-kotlin/commit/ef637ab
[c934b8a]: https://github.com/driftsys/ridlc-gen-kotlin/commit/c934b8a
[29e5752]: https://github.com/driftsys/ridlc-gen-kotlin/commit/29e5752
[0e6a0ae]: https://github.com/driftsys/ridlc-gen-kotlin/commit/0e6a0ae
[670387d]: https://github.com/driftsys/ridlc-gen-kotlin/commit/670387d
[d0c3c31]: https://github.com/driftsys/ridlc-gen-kotlin/commit/d0c3c31
[498ccd2]: https://github.com/driftsys/ridlc-gen-kotlin/commit/498ccd2
[3573753]: https://github.com/driftsys/ridlc-gen-kotlin/commit/3573753
[9e631f9]: https://github.com/driftsys/ridlc-gen-kotlin/commit/9e631f9
