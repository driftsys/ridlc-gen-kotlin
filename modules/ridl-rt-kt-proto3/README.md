# `ridl-rt-kt-proto3`

## Responsibility

The shared proto3 reader and writer, in `ridl.rt.proto3`: a hand-written port of
`crates/ridl-rt/src/proto3.rs` at ridl `editor-v0.21.0`. It depends only on
`ridl-rt-kt`, which supplies the encoding, payload and error types and the
string well-formedness checks. The dependency goes one way: `ridl-rt-kt` does
not reference this module.

`WireType`, the varint lengths, zigzag conversions and narrowing checks,
`readVarint`, `utf8`, `Value`, `Field`, `Reader`, `Writer` and `Verified` are
the shared operations a codec needs. They decide no field layout. `Reader`
returns fields in wire order and stops after its first error. Non-minimal
varints are accepted; overlong varints, values outside their scalar width,
invalid tags and truncated fields retain Rust's `Malformed` verdicts.

`Writer` writes from the caller's buffer position to its limit, in little-endian
order regardless of the buffer's order. Its write methods allocate no arrays,
buffer views or other objects. They write the prefix that fits and count all
requested bytes; `finish` reports an overflow with `EncodeError.Capacity`. The
caller keeps the output buffer's position and limit unchanged until `finish`.
The source passed to `bytes` must not overlap the destination, as Rust's
borrowed slices cannot overlap either.

## Status

Run `just test` or `just check` from the repository root. `Proto3Test` ports all
18 Rust unit tests, with their original names in KDoc. Additional cases cover
direct and read-only input buffers, positions, limits, byte order, truncated
fixed-width values, large lengths, invalid UTF-8 sequences and the absence of a
dependency from the core runtime to this module.

The Android vendor package declares this module as the optional Soong library
`ridl-rt-kt-proto3` (see `packaging/android/README.md`). The plugin's generated
`Proto3Codec.kt` reads and writes through this module, which a package needs
only when it selects proto3 (#133); the module holds no codec or face itself.

## Where the code departs from Rust and docs/design.md

- **Unsigned values are `UInt` and `ULong`.** These preserve every Rust `u32`
  and `u64` value, including the ten-byte sign extension of a negative `int32`.
- **Slices are `ByteBuffer` ranges.** `readVarint` uses the buffer's position as
  Rust's mutable offset and advances it only on success. `Reader` keeps its own
  view and does not move the caller's position. Length-delimited values and
  `Verified.bytes` are read-only views sharing the input storage; the caller
  must not mutate that storage while using them. Kotlin cannot enforce Rust's
  borrow lifetime. `Verified` has a `bytes` property because the Kotlin runtime
  has no `View` trait.
- **Errors use the existing Kotlin exceptions.** A malformed read throws
  `VerifyError.Structure` carrying the same `Malformed` verdict. `next` returns
  `null` at the end. `finish` returns the byte count and advances the output
  position on success, following `Payload.encode`; on overflow it leaves the
  position unchanged. A read-only output throws the JVM's
  `ReadOnlyBufferException`, since Rust's mutable slice cannot be read-only.
- **Sizes are JVM `Int` values.** The writer's requested byte count saturates at
  `Int.MAX_VALUE`, the largest count the shared `EncodeError.Capacity` can
  represent, instead of Rust's `usize::MAX`. Negative `checkCapacity` sizes are
  rejected as invalid arguments; Rust cannot express them.
- **Reading allocates JVM objects.** `Value`, `Field`, buffer views and decoded
  strings allocate. `utf8` uses a strict UTF-8 decoder instead of borrowing a
  Rust `str`. The writer object is allocated at construction and a capacity
  exception is allocated on failure; successful writes and `finish` allocate
  nothing. Existing `WellFormed.kt` checks remain in the core runtime.

A manifest can now select proto3 to generate `Proto3Codec.kt`. The generated
code depends on this runtime. Proto3 faces are still missing under #133; an
interface in a package that selects proto3 gets no face and a warning.
