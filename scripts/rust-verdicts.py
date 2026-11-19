#!/usr/bin/env python3
"""Regenerates the Rust codec verdicts of the conformance module and compares
them with the committed files (#39).

`CodecTest` and `SpikeTest` compare the Kotlin codec with the Rust codec of the
pinned ridl release through files under
`modules/conformance/src/test/resources/flatbuffers/`: one
`<package>-codec-rust-verdicts.txt` per corpus package that selects
FlatBuffers, and the cabin spike's `cabin-golden.txt` and
`cabin-rust-verdicts.txt`. `PodbufCodecTest` compares the podbuf codecs through
`modules/conformance/src/test/resources/podbuf/<package>-codec-rust-verdicts.txt`,
one per corpus package that selects podbuf (#134), and `Proto3CodecTest` the
proto3 codecs through
`modules/conformance/src/test/resources/proto3/<package>-codec-rust-verdicts.txt`,
one per corpus package that selects proto3 (#133). `TranscodeTest` compares the
transcode functions through
`modules/conformance/src/test/resources/transcode/<package>-transcode-rust-verdicts.txt`,
one per corpus package that sets `transcode = true` for the Kotlin backend
(#136). This script makes them again from the pinned tag, as
`docs/k1b-flatbuffers-spike.md` describes:

1. `ridl build --emit rust` writes each corpus package's crate;
2. a round-trip program per package, and the cabin spike's program, are written
   under `build/` (the repository tracks no Rust, D-K9) and built with cargo
   against the tag's `crates/ridl-rt`;
3. each program reads the corpus the conformance tests wrote to
   `modules/conformance/build/spike/`, and its output is compared with the
   committed file, or written over it with `--write`.

`FaceSizesTest` compares each face's size tables with the Rust descriptors'
`Sizes<E>` through
`modules/conformance/src/test/resources/sizes/<package>-rust-sizes.txt`, one per
corpus package with a public interface: a program over the package's crate
prints each table, in every encoding the package selects.

`DescriptorTest` compares `ridl-descriptor-kt` with the tag's
`crates/ridl-descriptor` the same way (#142): a program over that crate, with
the features `ridl-loopback` takes, verifies the descriptor corpus the test
wrote to `build/spike/descriptor-corpus.txt`, and its verdicts are
`resources/descriptor/descriptor-rust-verdicts.txt`.

The packages are the corpus's, as `CodecTest`, `PodbufCodecTest` and
`Proto3CodecTest` and `TranscodeTest` list them, by the encodings and the
`transcode` each `ridl.toml` selects for the Kotlin backend: a corpus
package with no committed verdict file fails the check, and `--write` creates
it. A verdict file with no corpus package fails both.

It expects `ridl` at the pinned release (`RIDL_BIN`, or the one `installRidl`
installs) and the corpus of a conformance test run; `just rust-verdicts` runs
both first. A checkout of driftsys/ridl at the tag is cloned unless
`--ridl-checkout` names one.
"""

import argparse
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFORMANCE = ROOT / "modules" / "conformance"
CORPUS = CONFORMANCE / "src" / "test" / "corpus"
VERDICTS = CONFORMANCE / "src" / "test" / "resources" / "flatbuffers"
DESCRIPTOR_VERDICTS = CONFORMANCE / "src" / "test" / "resources" / "descriptor"
PODBUF_VERDICTS = CONFORMANCE / "src" / "test" / "resources" / "podbuf"
PROTO3_VERDICTS = CONFORMANCE / "src" / "test" / "resources" / "proto3"
SIZES = CONFORMANCE / "src" / "test" / "resources" / "sizes"
TRANSCODE_VERDICTS = CONFORMANCE / "src" / "test" / "resources" / "transcode"
SPIKE = CONFORMANCE / "build" / "spike"
WORK = CONFORMANCE / "build" / "rust-verdicts"

ROUNDTRIP_MAIN = """// Verifies, decodes and re-encodes each buffer with the Rust codec.
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
ARMS
        _ => "unknown-type".to_string(),
        };
        println!("{ty} {label} {out}");
    }
}
"""

PODBUF_ROUNDTRIP_MAIN = """// Verifies, decodes and re-encodes each buffer with the Rust podbuf codec.
// A podbuf buffer starts on an 8-byte boundary, so each input is copied into
// an `Aligned` buffer before `verify`, and encoded into one.
use ridl_rt::encoding::Podbuf;
use ridl_rt::payload::{Payload, Ref};
use ridl_rt::podbuf::Aligned;
use std::io::BufRead;

const CAPACITY: usize = 1 << 16;

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn unhex(s: &str) -> Vec<u8> { (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect() }

fn roundtrip<T: Payload<Podbuf>>(buf: &[u8]) -> String {
    let mut input: Box<Aligned<[u8; CAPACITY]>> = Box::new(Aligned::zeroed());
    input.0[..buf.len()].copy_from_slice(buf);
    match Ref::<T, Podbuf>::verify(&input.0[..buf.len()]) {
        Err(e) => format!("err {e:?}"),
        Ok(r) => {
            let value = r.decode();
            let mut out: Box<Aligned<[u8; CAPACITY]>> = Box::new(Aligned::zeroed());
            match Ref::<T, Podbuf>::encode(&value, &mut out.0) {
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
ARMS
        _ => "unknown-type".to_string(),
        };
        println!("{ty} {label} {out}");
    }
}
"""

TRANSCODE_MAIN = """// Runs each line through the Rust transcode function of its type and pair (#136),
// into an output of the line's capacity. Input and output are 8-aligned, as a
// podbuf buffer must be.
use ridl_rt::podbuf::Aligned;
use std::io::BufRead;

const CAPACITY: usize = 1 << 16;

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn unhex(s: &str) -> Vec<u8> { (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect() }

macro_rules! transcode {
    ($f:path, $b:expr, $cap:expr) => {{
        let mut input: Box<Aligned<[u8; CAPACITY]>> = Box::new(Aligned::zeroed());
        input.0[..$b.len()].copy_from_slice(&$b);
        let mut out: Box<Aligned<[u8; CAPACITY]>> = Box::new(Aligned::zeroed());
        match $f(&input.0[..$b.len()], &mut out.0[..$cap]) {
            Ok(r) => format!("ok {}", hex(r.bytes())),
            Err(e) => format!("err {e:?}"),
        }
    }};
}

fn main() {
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let mut parts = line.split_whitespace();
        let (ty, label, source, target) = (parts.next().unwrap(), parts.next().unwrap(), parts.next().unwrap(), parts.next().unwrap());
        let cap: usize = parts.next().unwrap().parse().unwrap();
        let b = unhex(parts.next().unwrap_or(""));
        let out = match (ty, source, target) {
ARMS
        _ => "unknown-function".to_string(),
        };
        println!("{ty} {label} {out}");
    }
}
"""

SPIKE_MAIN = """// Golden FlatBuffers vectors for the cabin package, from the Rust codec the
// pinned ridl release generates. `encode` prints `<type> <value> <hex>` lines;
// `verify` reads such lines on stdin and prints what the Rust verifier says.
use ridl_rt::encoding::FlatBuffers;
use ridl_rt::payload::{Payload, Ref};
use std::io::BufRead;
use veh_crate::veh::cabin::*;

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
"""


DESCRIPTOR_MAIN = """// Verifies each buffer as a deployment or catalog descriptor with the pinned
// ridl-descriptor (#142). Prints error payloads with strings and identifiers
// encoded as hex; `Invalid` retains only planus's error kind.
use std::io::BufRead;
fn unhex(s: &str) -> Vec<u8> { (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect() }
fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn kind(e: &planus::Error) -> String {
    let shown = format!("{:?}", e.error_kind);
    shown.split(|c: char| !c.is_alphanumeric()).next().unwrap().to_string()
}
fn catalog(e: &ridl_descriptor::VerifyError) -> String {
    use ridl_descriptor::VerifyError::*;
    match e {
        TooShort(len) => format!("TooShort({len})"),
        WrongIdentifier(id) => format!("WrongIdentifier({})", hex(id)),
        WrongVersion(version) => format!("WrongVersion({version})"),
        Invalid(e) => format!("Invalid({})", kind(e)),
        #[allow(unreachable_patterns)]
        other => format!("?{other:?}"),
    }
}
fn deployment(e: &ridl_descriptor::deployment::VerifyError) -> String {
    use ridl_descriptor::deployment::VerifyError::*;
    match e {
        Invalid(e) => format!("Invalid({})", kind(e)),
        TooShort(len) => format!("TooShort({len})"),
        WrongIdentifier(id) => format!("WrongIdentifier({})", hex(id)),
        WrongVersion(version) => format!("WrongVersion({version})"),
        EmbeddedCatalog { catalog: name, source } => format!("EmbeddedCatalog({},{})", hex(name.as_bytes()), catalog(source)),
        CatalogHashMismatch { catalog } => format!("CatalogHashMismatch({})", hex(catalog.as_bytes())),
        InstanceNumber { component, instance, number } => format!("InstanceNumber({},{},{number})", hex(component.as_bytes()), hex(instance.as_bytes())),
        DuplicateInstanceNumber { number, first_component, first_instance, second_component, second_instance } =>
            format!("DuplicateInstanceNumber({number},{},{},{},{})", hex(first_component.as_bytes()), hex(first_instance.as_bytes()), hex(second_component.as_bytes()), hex(second_instance.as_bytes())),
        SurfaceLink { index, link, links } => format!("SurfaceLink({index},{link},{links})"),
        other => format!("{other:?}"),
    }
}
fn main() {
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let (which, h) = line.split_once(' ').unwrap_or((line.as_str(), ""));
        let b = unhex(h);
        let verdict = match which {
            "deployment" => ridl_descriptor::deployment::verify(&b).map(|_| ()).map_err(|e| deployment(&e)),
            "catalog" => ridl_descriptor::verify(&b).map(|_| ()).map_err(|e| catalog(&e)),
            _ => Err("unknown-kind".into()),
        };
        match verdict {
            Ok(()) => println!("ok"),
            Err(e) => println!("err:{e}"),
        }
    }
}
"""

SIZES_MAIN = """// Prints the `Sizes<E>` table of each public interface descriptor of the
// crate, in each encoding it has one for, as `FaceSizesTest` prints the
// Kotlin face's.
use ridl_rt::contract::Sizes;
use ridl_rt::encoding::Encoding;

fn table<I: Sizes<E>, E: Encoding>(name: &str, encoding: &str) {
    println!(
        "{name} {encoding} reservations={:?} maxBuffer={} eventBuffer={}",
        I::RESERVATIONS, I::MAX_BUFFER_SIZE, I::EVENT_SOURCE_BUFFER_SIZE,
    );
}

fn main() {
TABLES
}
"""


def descriptor_program(ridl_checkout):
    """The descriptor verdict program, over the tag's `ridl-descriptor` with the features `ridl-loopback` takes."""
    prog = WORK / "descriptor-verdicts"
    (prog / "src").mkdir(parents=True, exist_ok=True)
    (prog / "Cargo.toml").write_text(f"""[package]
name = "descriptor-verdicts"
version = "0.0.0"
edition = "2024"
[dependencies]
ridl-descriptor = {{ path = "{ridl_checkout}/crates/ridl-descriptor", default-features = false }}
planus = {{ version = "=1.3.0", default-features = false }}
[workspace]
""")
    (prog / "src" / "main.rs").write_text(DESCRIPTOR_MAIN)
    run(["cargo", "build", "--quiet", "--manifest-path", prog / "Cargo.toml"],
        env={**os.environ, "CARGO_TARGET_DIR": str(WORK / "target"), "RUSTFLAGS": "-Awarnings"})
    return WORK / "target" / "debug" / "descriptor-verdicts"


def indexed(output):
    """The verdicts `DescriptorTest` compares: a legend, `<index> <verdict>` per distinct verdict in order of
    first appearance, then a blank line, then one index per buffer of the corpus, 100 to a line."""
    legend, text = {}, []
    for verdict in output.splitlines():
        if verdict not in legend:
            legend[verdict] = str(len(legend))
        text.append(legend[verdict])
    lines = [f"{index} {verdict}" for verdict, index in legend.items()] + [""]
    lines += [" ".join(text[i:i + 100]) for i in range(0, len(text), 100)]
    return "\n".join(lines) + "\n"


def run(command, **kwargs):
    """Runs [command], and on a failure prints the standard error a capture kept, such as a Rust panic (#53)."""
    try:
        return subprocess.run(command, check=True, **kwargs)
    except subprocess.CalledProcessError as error:
        if error.stderr:
            sys.stderr.write(error.stderr if isinstance(error.stderr, str) else error.stderr.decode(errors="replace"))
        sys.exit(f"{command[0]} exited {error.returncode}")


def release():
    """The pinned tag and the `ridl-rt` version requirement it carries: `0.10` for `editor-v0.10.0`."""
    tag = (CONFORMANCE / "ridl-release").read_text().strip()
    match = re.fullmatch(r"editor-v(\d+)\.(\d+)\.\d+", tag)
    if not match:
        sys.exit(f"modules/conformance/ridl-release is not a ridl release tag: {tag}")
    return tag, f"{match.group(1)}.{match.group(2)}"


def checkout(tag, given):
    if given:
        # The programs build against this checkout's `ridl-rt`, so it must be
        # the pinned tag's commit (#53).
        given = pathlib.Path(given).resolve()
        head, pinned = (subprocess.run(["git", "-C", given, "rev-parse", "--verify", "--quiet", ref],
                                       capture_output=True, text=True).stdout.strip()
                        for ref in ("HEAD^{commit}", f"refs/tags/{tag}^{{commit}}"))
        if not pinned:
            sys.exit(f"--ridl-checkout {given} has no tag {tag}: fetch it, or omit --ridl-checkout")
        if head != pinned:
            sys.exit(f"--ridl-checkout {given} is at {head[:12] or 'no commit'}, not at {tag} ({pinned[:12]})")
        return given
    target = WORK / "ridl"
    if (target / ".git").exists():
        described = subprocess.run(["git", "-C", target, "describe", "--tags", "--exact-match"],
                                   capture_output=True, text=True).stdout.strip()
        if described == tag:
            return target
        shutil.rmtree(target)
    run(["git", "clone", "--quiet", "--depth", "1", "--branch", tag, "https://github.com/driftsys/ridl", target])
    return target


def crate(ridl, package):
    """The crate `ridl build --emit rust` writes for one corpus package. `ridl build` takes a package directory."""
    source = WORK / f"src-{package}"
    out = WORK / f"crate-{package}"
    for path in (source, out):
        shutil.rmtree(path, ignore_errors=True)
    shutil.copytree(CORPUS / package, source)
    # The build's lints are the corpus's business, not this report's: shown
    # only when the build fails.
    built = subprocess.run([ridl, "build", "--emit", "rust", "--out-dir", out, "."], cwd=source, capture_output=True, text=True)
    if built.returncode != 0:
        sys.exit(f"ridl build of {package} exited {built.returncode}:\n{built.stderr}")
    return out


def program(name, crate_dir, rt_version, ridl_checkout, main):
    """A cargo program over [crate_dir], aliased `veh_crate`, with `ridl-rt` patched to the tag's."""
    crate_name = re.search(r'^name = "([^"]+)"', (crate_dir / "Cargo.toml").read_text(), re.M).group(1)
    prog = WORK / name
    (prog / "src").mkdir(parents=True, exist_ok=True)
    (prog / "Cargo.toml").write_text(f"""[package]
name = "{name}"
version = "0.0.0"
edition = "2024"

[dependencies]
veh_crate = {{ path = "{crate_dir}", package = "{crate_name}" }}
ridl-rt = {{ version = "{rt_version}", features = ["flatbuffers", "proto3", "podbuf"] }}

[patch.crates-io]
ridl-rt = {{ path = "{ridl_checkout}/crates/ridl-rt" }}

[workspace]
""")
    (prog / "src" / "main.rs").write_text(main)
    run(["cargo", "build", "--quiet", "--manifest-path", prog / "Cargo.toml"],
        env={**os.environ, "CARGO_TARGET_DIR": str(WORK / "target"), "RUSTFLAGS": "-Awarnings"})
    return WORK / "target" / "debug" / name


def payload_types(crate_dir, encoding="FlatBuffers"):
    """The public payloads of the crate under [encoding], `FlatBuffers`, `Proto3` or `Podbuf`, as `pkg.Type` with their Rust paths."""
    types = []
    for f in sorted(crate_dir.glob("*.rs")):
        if f.name == "lib.rs":
            continue
        pkg = f.stem
        path = "::".join(pkg.split("."))
        text = f.read_text()
        for t in re.findall(r"Payload<::ridl_rt::encoding::" + encoding + r">\s+for\s+([A-Za-z0-9_]+)", text):
            # An internal declaration is `pub(crate)`: unreachable from the program.
            if re.search(r"pub\(crate\) (struct|enum) " + t + r"\b", text):
                continue
            types.append((f"{pkg}.{t}", f"veh_crate::{path}::{t}"))
    return types


def size_tables(crate_dir):
    """The public interface descriptors of the crate with a `Sizes<E>` table, as (`pkg.Iface`, encoding, Rust path)."""
    tables = []
    for f in sorted(crate_dir.glob("*.rs")):
        if f.name == "lib.rs":
            continue
        pkg = f.stem
        path = "::".join(pkg.split("."))
        text = f.read_text()
        for encoding, iface in re.findall(r"impl ::ridl_rt::contract::Sizes<::ridl_rt::encoding::(\w+)> for (\w+)", text):
            # An internal interface's descriptor is `pub(crate)`: unreachable from the program.
            if re.search(r"pub\(crate\) struct " + iface + r"\b", text):
                continue
            tables.append((f"{pkg}.{iface}", encoding, f"veh_crate::{path}::{iface}"))
    return tables


def sizes_main(tables):
    """The size table program's `main.rs`: one line per table of [tables]."""
    lines = [f'    table::<{path}, ridl_rt::encoding::{encoding}>("{name}", "{encoding}");' for name, encoding, path in tables]
    return SIZES_MAIN.replace("TABLES", "\n".join(lines))


def roundtrip_main(crate_dir, encoding="FlatBuffers"):
    """The round trip's `main.rs`: one arm per public payload of the crate under [encoding]."""
    arms = [f'        "{name}" => roundtrip::<{path}>(&b),' for name, path in payload_types(crate_dir, encoding)]
    if encoding == "Podbuf":
        main = PODBUF_ROUNDTRIP_MAIN
    else:
        # A proto3 buffer needs no alignment: the FlatBuffers round trip, over the proto3 marker.
        main = ROUNDTRIP_MAIN.replace("FlatBuffers", encoding)
    return main.replace("ARMS", "\n".join(arms))


def transcode_functions(crate_dir):
    """The public transcode functions of the crate, `(pkg.Type, source, target)` with their Rust paths, in source order."""
    functions = []
    for f in sorted(crate_dir.glob("*.rs")):
        if f.name == "lib.rs":
            continue
        pkg = f.stem
        path = "::".join(pkg.split("."))
        text = f.read_text()
        for block in re.finditer(r"^impl ([A-Za-z0-9_]+) \{\n(.*?)^\}", text, re.M | re.S):
            t = block.group(1)
            # An internal declaration is `pub(crate)`: unreachable from the program.
            if re.search(r"pub\(crate\) (struct|enum) " + t + r"\b", text):
                continue
            for source, target in re.findall(r"pub fn transcode_([a-z0-9]+)_to_([a-z0-9]+)<", block.group(2)):
                functions.append(((f"{pkg}.{t}", source, target), f"veh_crate::{path}::{t}::transcode_{source}_to_{target}"))
    return functions


def transcode_main(crate_dir):
    """The transcode program's `main.rs`: one arm per public transcode function of the crate."""
    arms = [f'        ("{t}", "{s}", "{d}") => transcode!({path}, b, cap),' for (t, s, d), path in transcode_functions(crate_dir)]
    return TRANSCODE_MAIN.replace("ARMS", "\n".join(arms))


def transcodes(package):
    """Whether the corpus package's `ridl.toml` sets `transcode = true` for the Kotlin backend, as `Harness.transcode` reads it."""
    manifest = (CORPUS / package / "ridl.toml").read_text()
    kotlin = manifest.partition("[backend.kotlin]")[2].split("\n[")[0]
    return re.search(r"^transcode\s*=\s*true\s*$", kotlin, re.M) is not None


def compact(output):
    """The verdicts `CodecTest` compares: `ok:<first 16 hex of SHA-256 of the re-encoded hex>` or `err:<error>`."""
    lines = []
    for line in output.splitlines():
        _, _, rest = line.split(" ", 2)
        if rest.startswith("ok "):
            lines.append("ok:" + hashlib.sha256(rest[3:].encode()).hexdigest()[:16])
        elif rest.startswith("err "):
            lines.append("err:" + rest[4:])
        else:
            lines.append("?:" + rest)
    return "\n".join(lines) + "\n"


def corpus(name):
    path = SPIKE / name
    if not path.exists():
        sys.exit(f"{path.relative_to(ROOT)} is missing: run the conformance tests first (just rust-verdicts does)")
    return path.read_text()


def encodings(package):
    """The encodings the corpus package's `ridl.toml` selects for the Kotlin backend, as `Harness.encodings` reads them."""
    manifest = (CORPUS / package / "ridl.toml").read_text()
    kotlin = manifest.partition("[backend.kotlin]")[2].split("\n[")[0]
    listed = re.search(r"^encodings\s*=\s*\[([^\]]*)\]", kotlin, re.M)
    return re.findall(r'"([^"]+)"', listed.group(1)) if listed else []


def sampled_verdicts(package, crate_dir, rt_version, source, encoding, directory, write):
    """The verdicts of the [encoding] (`Podbuf` or `Proto3`) round trip over its corpus, compared or written.

    Every payload the Rust codec has must be sampled: a root the Kotlin codec
    lacks would otherwise go unseen.
    """
    label = encoding.lower()
    binary = program(f"{label}-roundtrip-{package}", crate_dir, rt_version, source, roundtrip_main(crate_dir, encoding))
    buffers = corpus(f"{package}-{label}-corpus.txt")
    sampled = {line.split(" ", 1)[0] for line in buffers.splitlines() if line}
    agree = True
    for name, _ in payload_types(crate_dir, encoding):
        if name not in sampled:
            print(f"  {label}/{package}: the Rust payload {name} has no sample in the corpus")
            agree = False
    output = run([binary], input=buffers, capture_output=True, text=True).stdout
    return compare(f"{package}-codec-rust-verdicts.txt", compact(output), write, directory) and agree


def compare(name, regenerated, write, directory=VERDICTS):
    """Compares one regenerated file with the committed one, or writes it. Returns whether they agree."""
    committed = directory / name
    if write:
        committed.parent.mkdir(parents=True, exist_ok=True)
        committed.write_text(regenerated)
        return True
    if not committed.exists():
        print(f"  {directory.name}/{name}: not committed; `just rust-verdicts --write` creates it")
        return False
    current = committed.read_text()
    if current == regenerated:
        print(f"  {directory.name}/{name}: unchanged")
        return True
    old, new = current.splitlines(), regenerated.splitlines()
    changed = sum(1 for a, b in zip(old, new) if a != b) + abs(len(old) - len(new))
    print(f"  {directory.name}/{name}: {changed} of {len(new)} lines differ")
    for i, (a, b) in enumerate(zip(old, new)):
        if a != b:
            print(f"    line {i + 1}: committed `{a}`, Rust `{b}`")
            break
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--write", action="store_true", help="write the regenerated files over the committed ones")
    parser.add_argument("--ridl-checkout", help="a driftsys/ridl checkout at the pinned tag, instead of a fresh clone")
    args = parser.parse_args()

    tag, rt_version = release()
    ridl = os.environ.get("RIDL_BIN") or str(CONFORMANCE / "build" / "ridl" / "ridl")
    if not pathlib.Path(ridl).exists():
        sys.exit(f"{ridl} is missing: run ./gradlew :conformance:installRidl first, or set RIDL_BIN")
    WORK.mkdir(parents=True, exist_ok=True)
    source = checkout(tag, args.ridl_checkout)
    print(f"Rust verdicts of {tag} (ridl-rt {rt_version}), from {source}")

    agree = True
    # Every corpus package, as `CodecTest`, `PodbufCodecTest` and
    # `Proto3CodecTest` list them (#49): a package with no committed verdict
    # file fails the check, and `--write` creates its file.
    every = sorted(p.parent.name for p in CORPUS.glob("*/ridl.toml"))
    selected = {package: encodings(package) for package in every}
    flatbuffers = [p for p in every if "flatbuffers" in selected[p]]
    podbuf = [p for p in every if "podbuf" in selected[p]]
    proto3 = [p for p in every if "proto3" in selected[p]]
    transcode = [p for p in every if transcodes(p)]
    for directory, packages, suffix in ((VERDICTS, flatbuffers, "codec"), (PODBUF_VERDICTS, podbuf, "codec"),
                                        (PROTO3_VERDICTS, proto3, "codec"), (TRANSCODE_VERDICTS, transcode, "transcode")):
        for stray in sorted(directory.glob(f"*-{suffix}-rust-verdicts.txt")):
            if stray.name.removesuffix(f"-{suffix}-rust-verdicts.txt") not in packages:
                print(f"  {directory.name}/{stray.name}: no corpus package selects {directory.name}; delete it")
                agree = False
    tabled = set()
    for package in every:
        if package not in flatbuffers and package not in podbuf and package not in proto3:
            continue
        crate_dir = crate(ridl, package)
        # The face's size tables in every encoding the package carries, which `FaceSizesTest` compares.
        tables = size_tables(crate_dir)
        if tables:
            tabled.add(package)
            binary = program(f"sizes-{package}", crate_dir, rt_version, source, sizes_main(tables))
            output = run([binary], capture_output=True, text=True).stdout
            agree &= compare(f"{package}-rust-sizes.txt", "".join(sorted(output.splitlines(keepends=True))), args.write, SIZES)
        if package in flatbuffers:
            binary = program(f"roundtrip-{package}", crate_dir, rt_version, source, roundtrip_main(crate_dir))
            output = run([binary], input=corpus(f"{package}-codec-corpus.txt"), capture_output=True, text=True).stdout
            agree &= compare(f"{package}-codec-rust-verdicts.txt", compact(output), args.write)
        if package in proto3:
            agree &= sampled_verdicts(package, crate_dir, rt_version, source, "Proto3", PROTO3_VERDICTS, args.write)
        if package in podbuf:
            agree &= sampled_verdicts(package, crate_dir, rt_version, source, "Podbuf", PODBUF_VERDICTS, args.write)
        if package in transcode:
            binary = program(f"transcode-{package}", crate_dir, rt_version, source, transcode_main(crate_dir))
            lines = corpus(f"{package}-transcode-corpus.txt")
            # Every transcode function Rust has is sampled: a pair the Kotlin
            # plugin lacks would otherwise go unseen.
            sampled = {tuple(line.split(" ")[0:1] + line.split(" ")[2:4]) for line in lines.splitlines() if line}
            for function, _ in transcode_functions(crate_dir):
                if function not in sampled:
                    print(f"  transcode/{package}: the Rust function {function[0]}::transcode_{function[1]}_to_{function[2]} has no line in the corpus")
                    agree = False
            output = run([binary], input=lines, capture_output=True, text=True).stdout
            agree &= compare(f"{package}-transcode-rust-verdicts.txt", compact(output), args.write, TRANSCODE_VERDICTS)

    for stray in sorted(SIZES.glob("*-rust-sizes.txt")):
        if stray.name.removesuffix("-rust-sizes.txt") not in tabled:
            print(f"  {SIZES.name}/{stray.name}: no corpus package has a public interface; delete it")
            agree = False

    spike = program("cabin-vectors", WORK / "crate-cabin", rt_version, source, SPIKE_MAIN)
    golden = run([spike, "encode"], capture_output=True, text=True).stdout
    agree &= compare("cabin-golden.txt", golden, args.write)
    verdicts = run([spike, "verify"], input=corpus("cabin-corpus.txt"), capture_output=True, text=True).stdout
    agree &= compare("cabin-rust-verdicts.txt", verdicts, args.write)

    # #142: the deployment and catalog descriptor verdicts of `DescriptorTest`.
    descriptor = descriptor_program(source)
    output = run([descriptor], input=corpus("descriptor-corpus.txt"), capture_output=True, text=True).stdout
    agree &= compare("descriptor-rust-verdicts.txt", indexed(output), args.write, DESCRIPTOR_VERDICTS)

    if args.write and not agree:
        sys.exit("a committed verdict file has no corpus package: delete it")
    elif args.write:
        print("written; review the diff, then run just test")
    elif not agree:
        sys.exit("the committed Rust verdicts differ from the corpus and the pinned release's, as each line above says")


if __name__ == "__main__":
    main()
