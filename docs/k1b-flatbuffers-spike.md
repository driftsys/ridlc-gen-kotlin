# K1b: the FlatBuffers verifier spike (O-K1)

**Status:** spike done, for Sebastien's disposition of O-K1. Written 2026-09-24.
`docs/design.md` §4 recommends option A — FlatBuffers with a verifier in
`ridl-rt-kt` — "decided by a bounded spike at the start of K1: write the
verifier for the cabin package's four payload types; if it comes in under the
size above and passes the malformed-buffer corpus (§7), FlatBuffers stays; if
not, C, with the frame amended." This is that spike. The design note is not
edited here (it is the same text as the driftsys/ridl copy); the frame
specification is untouched, because the answer is not C.

## What was built

- **`ridl-rt-kt`'s `ridl.rt.flatbuffers` package** — `Reader` and `Builder`, the
  Kotlin spelling of `ridl_rt::flatbuffers` at the pinned release: the
  bounds-checked little-endian reads, the vtable walk, the string and vector
  headers, and the back-to-front builder. Like the Rust module it decides no
  layout; a generated codec hands it slots, offsets and table sizes. Every read
  outside the buffer throws `VerifyError.Structure`, never a JVM exception.
- **The cabin package's payload codecs, written by hand** in the shape the K2c
  emitter would generate, over those two classes and the generated `Types.kt`:
  `Temperature`, `Level`, `Window`, `Average` and `Health` in their box tables
  (ADR-0019 decision 8), and `Warning` in its own table. They are in
  `modules/conformance/src/test/resources/flatbuffers/cabin-codec.kt`. Cabin has
  six payload types, not the four §4 counts: the command's and the query's
  arguments and the query's reply are three of them, and the `Horn` interface
  adds `Health`.
- **`SpikeTest`**, in the conformance module, run on every `check`.

## What was measured

| Criterion                                   | Result                                                                                                                                                                                   |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Size against `ridl-rt`'s FlatBuffers module | 363 lines, 210 of code, against the Rust module's 600 and 361                                                                                                                            |
| Encoding interoperates with Rust            | Kotlin encodes all 16 sample values of the six types to exactly the bytes the Rust codec encodes (`cabin-golden.txt`), and decodes those bytes back to the values                        |
| The malformed-buffer corpus                 | 1,385 mutants of the 16 buffers: every truncation, every byte set to 0x00, 0xFF and itself plus one, and each buffer grown past its size bound. None meets an exception of the JVM's own |
| The verifier agrees with Rust's             | On all 1,385, Kotlin's verdict — refused with which error, or decoded to which value — is the Rust verifier's, text for text: 978 refused, 407 decoded                                   |
| The comparison can fail                     | Removing the check that a field lies inside its table turns 24 verdicts into disagreements                                                                                               |

**Since ridl 0.5.0** (2026-10-01): the Rust codec reads an absent non-optional
scalar or enum field as its FlatBuffers default when 0 is a legal value of its
type (driftsys/ridl#472), and every cabin type admits 0. Regenerated over
`editor-v0.5.0`, 142 verdicts move from `MissingRequired` to a decoded default,
so the corpus is 960 refused and 425 decoded, and the hand-written codec reads
an absent field as 0 to match. ridl 0.4.0's PascalCase variants also changed the
`Debug` text of a `Health` (`Ok`, not `OK`). The golden bytes did not change.
The corpus no longer reaches a refused missing field; `kt-zero`'s, under
`CodecTest`, does.

**Since ridl 0.5.1** (2026-10-03): the Rust verifier checks float steps,
non-finite floats, inline constraints and map key uniqueness
(driftsys/ridl#654). Regenerated over `editor-v0.5.1`, neither the golden bytes
nor the 1,385 verdicts change: no cabin type has a float or a map.

**Since ridl 0.6.0** (2026-10-07): the Rust codec emitter and `ridl-rt`'s
FlatBuffers code differ from 0.5.1's in comments only, and no cabin type
changed, so the golden bytes and the verdicts stand as regenerated over
`editor-v0.5.1`.

The corpus covers §7's malformed cases for these types — truncated buffers,
offsets past the end, a vtable naming a field across its table's end, a missing
required field, an enum discriminant out of range, a value outside its range, a
buffer over its size bound. It does not reach a vector longer than its bound, a
union discriminant or a string, because the cabin package has none; K2c's corpus
over `fb-demo` and `veh-common` does.

## Recommendation

**Option A: FlatBuffers stays**, tag 1, as the frame specification §11.2 already
says. The verifier is smaller than its Rust original, and it agrees with it on
every buffer of the corpus.

One consequence to dispose of with it. D-K5 has the codec written against the
classes `flatc --kotlin` generates. The spike's codec uses none: like the Rust
codec, which does not use the `flatbuffers` crate, it reads and writes through
`ridl.rt.flatbuffers` alone. The recommendation is to keep it that way in K2c,
which removes §4's second objection to FlatBuffers — the large `flatc` output, a
second projection of every declaration — and removes `flatc` from the consumer's
build. The `.fbs` schema is still `ridl build`'s to emit (D-K5's first half
stands), for a consumer in another language.

## Regenerating the Rust verdicts

`cabin-golden.txt` and `cabin-rust-verdicts.txt` come from a Rust program that
links the crate `ridl build --emit rust` writes for the cabin corpus package
against the pinned release's `crates/ridl-rt`. The repository tracks no Rust, so
the program is here. With `ridl` at the pinned release and a checkout of
driftsys/ridl at the same tag:

```sh
ridl build --emit rust --out-dir cabin-rs modules/conformance/src/test/corpus/cabin
cargo new cabin-vectors   # then Cargo.toml and src/main.rs as below
cargo run -q -- encode > modules/conformance/src/test/resources/flatbuffers/cabin-golden.txt
just test                 # writes modules/conformance/build/spike/cabin-corpus.txt
cargo run -q -- verify < modules/conformance/build/spike/cabin-corpus.txt \
  > modules/conformance/src/test/resources/flatbuffers/cabin-rust-verdicts.txt
```

`Cargo.toml`, with the two paths filled in:

```toml
[package]
name = "cabin-vectors"
version = "0.0.0"
edition = "2024"

[dependencies]
veh_cabin = { path = "<path to cabin-rs>" }
ridl-rt = { version = "0.6", features = ["flatbuffers"] }

[patch.crates-io]
ridl-rt = { path = "<path to driftsys/ridl>/crates/ridl-rt" }
```

`src/main.rs`:

```rust
// Golden FlatBuffers vectors for the cabin package, from the Rust codec the
// pinned ridl release generates. `encode` prints `<type> <value> <hex>` lines;
// `verify` reads such lines on stdin and prints what the Rust verifier says.
use ridl_rt::encoding::FlatBuffers;
use ridl_rt::payload::{Payload, Ref};
use std::io::BufRead;
use veh_cabin::veh::cabin::*;

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn unhex(s: &str) -> Vec<u8> { (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect() }

fn enc<T: Payload<FlatBuffers>>(name: &str, shown: String, v: &T) {
    let mut out = [0u8; 128];
    let r = Ref::<T, FlatBuffers>::encode(v, &mut out).expect("encode");
    println!("{name} {shown} {}", hex(r.bytes()));
}

fn check<T: Payload<FlatBuffers> + std::fmt::Debug>(buf: &[u8]) -> String {
    match Ref::<T, FlatBuffers>::verify(buf) {
        Ok(r) => format!("ok {:?}", r.decode()),
        Err(e) => format!("err {e:?}"),
    }
}

fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("encode") => {
            for v in [-40i64, 0, 85] { enc("Temperature", v.to_string(), &Temperature::new(v).unwrap()); }
            for v in [0i64, 42, 100] { enc("Level", v.to_string(), &Level::new(v).unwrap()); }
            for v in [0i64, 1, 100000] { enc("Window", v.to_string(), &Window::new(v).unwrap()); }
            for v in [0i64, 1000] { enc("Average", v.to_string(), &Average::new(v).unwrap()); }
            for (n, v) in [("OK", Health::Ok), ("WARN", Health::Warn), ("FAIL", Health::Fail)] { enc("Health", n.into(), &v); }
            enc("Warning", "7,FAIL".into(), &Warning { code: Level::new(7).unwrap(), health: Health::Fail });
            enc("Warning", "100,OK".into(), &Warning { code: Level::new(100).unwrap(), health: Health::Ok });
        }
        Some("verify") => {
            for line in std::io::stdin().lock().lines() {
                let line = line.unwrap();
                let mut parts = line.split_whitespace();
                let (ty, label, h) = (parts.next().unwrap(), parts.next().unwrap(), parts.next().unwrap_or(""));
                let b = unhex(h);
                let out = match ty {
                    "Temperature" => check::<Temperature>(&b),
                    "Level" => check::<Level>(&b),
                    "Window" => check::<Window>(&b),
                    "Average" => check::<Average>(&b),
                    "Health" => check::<Health>(&b),
                    "Warning" => check::<Warning>(&b),
                    _ => "unknown type".into(),
                };
                println!("{ty} {label} {out}");
            }
        }
        _ => eprintln!("usage: encode | verify"),
    }
}
```

## Regenerating the codec verdicts (K2c)

`CodecTest` compares the generated `Codec.kt` with the Rust codec over every
corpus package. Its Rust side is one round-trip program per package, written by
the script below over the crate `ridl build --emit rust` writes for that corpus
package: each `pkg.Type label hex` line of the corpus is verified, decoded and
re-encoded, and printed with its verdict. For each package, with the paths
filled in:

```sh
ridl build --emit rust --out-dir <crate> modules/conformance/src/test/corpus/<package>
python3 gen.py <crate> <program> <path to driftsys/ridl>
cargo build --manifest-path <program>/Cargo.toml
just test   # writes modules/conformance/build/spike/<package>-codec-corpus.txt
<program>/target/debug/roundtrip < modules/conformance/build/spike/<package>-codec-corpus.txt \
  | python3 compact.py > modules/conformance/src/test/resources/flatbuffers/<package>-codec-rust-verdicts.txt
```

`ridl build` takes a package directory, so run it from inside the package. In
`gen.py`'s `Cargo.toml`, and in the spike's, the `ridl-rt` version must match
the release's (`0.2` for `editor-v0.2.2`, `0.3` for `v0.3.0`, `0.4` for
`editor-v0.4.0`, `0.5` for `editor-v0.5.0` and `editor-v0.5.1`, `0.6` for
`editor-v0.6.0`), or the path patch does not apply. Before driftsys/ridl#581,
the Rust face `kt-values` emits does not compile: its `Names` interface has a
parameter named `claim`, which the Rust `dispatch` shadows. The codec does not
depend on the interfaces, so delete `Names` from a copy of `probe.ridl` and emit
from that copy.

`gen.py`:

```python
# Writes a Rust round-trip program for one generated crate: every line
# `pkg.Type label hex` on stdin is verified, decoded and re-encoded by the
# Rust codec, and printed as `pkg.Type label ok <hex>` or `... err <error>`.
import re, sys, pathlib
crate = pathlib.Path(sys.argv[1]); prog = pathlib.Path(sys.argv[2]); ridl = sys.argv[3]
name = re.search(r'^name = "([^"]+)"', crate.joinpath('Cargo.toml').read_text(), re.M).group(1)
arms = []
for f in sorted(crate.glob('*.rs')):
    if f.name == 'lib.rs': continue
    pkg = f.stem
    path = '::'.join(pkg.split('.'))
    text = f.read_text()
    for t in re.findall(r'Payload<::ridl_rt::encoding::FlatBuffers>\s+for\s+([A-Za-z0-9_]+)', text):
        # An internal declaration is `pub(crate)`: unreachable from this program.
        if re.search(r'pub\(crate\) (struct|enum) ' + t + r'\b', text): continue
        arms.append(f'        "{pkg}.{t}" => roundtrip::<veh_crate::{path}::{t}>(&b),')
prog.joinpath('src').mkdir(parents=True, exist_ok=True)
prog.joinpath('Cargo.toml').write_text(f'''[package]
name = "roundtrip"
version = "0.0.0"
edition = "2024"

[dependencies]
veh_crate = {{ path = "{crate}", package = "{name}" }}
ridl-rt = {{ version = "0.6", features = ["flatbuffers"] }}

[patch.crates-io]
ridl-rt = {{ path = "{ridl}/crates/ridl-rt" }}
''')
prog.joinpath('src/main.rs').write_text('''// Verifies, decodes and re-encodes each buffer with the Rust codec.
use ridl_rt::encoding::FlatBuffers;
use ridl_rt::payload::{Payload, Ref};
use std::io::BufRead;

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn unhex(s: &str) -> Vec<u8> { (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect() }

fn roundtrip<T: Payload<FlatBuffers>>(buf: &[u8]) -> String {
    match Ref::<T, FlatBuffers>::verify(buf) {
        Err(e) => format!("err {e:?}"),
        Ok(r) => {
            let value = r.decode();
            let mut out = vec![0u8; 1 << 16];
            match Ref::<T, FlatBuffers>::encode(&value, &mut out) {
                Ok(e) => format!("ok {}", hex(e.bytes())),
                Err(e) => format!("reencode-failed {e:?}"),
            }
        }
    }
}

fn main() {
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let mut parts = line.split_whitespace();
        let (ty, label, h) = (parts.next().unwrap(), parts.next().unwrap(), parts.next().unwrap_or(""));
        let b = unhex(h);
        let out = match ty {
''' + '\n'.join(arms) + '''
        _ => "unknown-type".to_string(),
        };
        println!("{ty} {label} {out}");
    }
}
''')
print(len(arms), 'types')
```

`compact.py`:

```python
# Compacts the Rust round trip's output to the verdicts CodecTest compares:
# `ok:<first 16 hex of SHA-256 of the re-encoded hex>` or `err:<error>`.
import hashlib, sys
for line in sys.stdin:
    ty, label, rest = line.rstrip('\n').split(' ', 2)
    if rest.startswith('ok '):
        print('ok:' + hashlib.sha256(rest[3:].encode()).hexdigest()[:16])
    elif rest.startswith('err '):
        print('err:' + rest[4:])
    else:
        print('?:' + rest)
```
