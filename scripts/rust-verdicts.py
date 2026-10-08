#!/usr/bin/env python3
"""Regenerates the Rust codec verdicts of the conformance module and compares
them with the committed files (#39).

`CodecTest` and `SpikeTest` compare the Kotlin codec with the Rust codec of the
pinned ridl release through files under
`modules/conformance/src/test/resources/flatbuffers/`: one
`<package>-codec-rust-verdicts.txt` per corpus package, and the cabin spike's
`cabin-golden.txt` and `cabin-rust-verdicts.txt`. This script makes them again
from the pinned tag, as `docs/k1b-flatbuffers-spike.md` describes:

1. `ridl build --emit rust` writes each corpus package's crate;
2. a round-trip program per package, and the cabin spike's program, are written
   under `build/` (the repository tracks no Rust, D-K9) and built with cargo
   against the tag's `crates/ridl-rt`;
3. each program reads the corpus the conformance tests wrote to
   `modules/conformance/build/spike/`, and its output is compared with the
   committed file, or written over it with `--write`.

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


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def release():
    """The pinned tag and the `ridl-rt` version requirement it carries: `0.6` for `editor-v0.6.0`."""
    tag = (CONFORMANCE / "ridl-release").read_text().strip()
    match = re.fullmatch(r"editor-v(\d+)\.(\d+)\.\d+", tag)
    if not match:
        sys.exit(f"modules/conformance/ridl-release is not a ridl release tag: {tag}")
    return tag, f"{match.group(1)}.{match.group(2)}"


def checkout(tag, given):
    if given:
        return pathlib.Path(given).resolve()
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
ridl-rt = {{ version = "{rt_version}", features = ["flatbuffers"] }}

[patch.crates-io]
ridl-rt = {{ path = "{ridl_checkout}/crates/ridl-rt" }}

[workspace]
""")
    (prog / "src" / "main.rs").write_text(main)
    run(["cargo", "build", "--quiet", "--manifest-path", prog / "Cargo.toml"],
        env={**os.environ, "CARGO_TARGET_DIR": str(WORK / "target"), "RUSTFLAGS": "-Awarnings"})
    return WORK / "target" / "debug" / name


def roundtrip_main(crate_dir):
    """The round trip's `main.rs`: one arm per public FlatBuffers payload of the crate."""
    arms = []
    for f in sorted(crate_dir.glob("*.rs")):
        if f.name == "lib.rs":
            continue
        pkg = f.stem
        path = "::".join(pkg.split("."))
        text = f.read_text()
        for t in re.findall(r"Payload<::ridl_rt::encoding::FlatBuffers>\s+for\s+([A-Za-z0-9_]+)", text):
            # An internal declaration is `pub(crate)`: unreachable from the program.
            if re.search(r"pub\(crate\) (struct|enum) " + t + r"\b", text):
                continue
            arms.append(f'        "{pkg}.{t}" => roundtrip::<veh_crate::{path}::{t}>(&b),')
    return ROUNDTRIP_MAIN.replace("ARMS", "\n".join(arms))


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


def compare(name, regenerated, write):
    """Compares one regenerated file with the committed one, or writes it. Returns whether they agree."""
    committed = VERDICTS / name
    if write:
        committed.write_text(regenerated)
        return True
    current = committed.read_text() if committed.exists() else ""
    if current == regenerated:
        print(f"  {name}: unchanged")
        return True
    old, new = current.splitlines(), regenerated.splitlines()
    changed = sum(1 for a, b in zip(old, new) if a != b) + abs(len(old) - len(new))
    print(f"  {name}: {changed} of {len(new)} lines differ")
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
    packages = sorted(p.name.removesuffix("-codec-rust-verdicts.txt") for p in VERDICTS.glob("*-codec-rust-verdicts.txt"))
    for package in packages:
        crate_dir = crate(ridl, package)
        binary = program(f"roundtrip-{package}", crate_dir, rt_version, source, roundtrip_main(crate_dir))
        output = run([binary], input=corpus(f"{package}-codec-corpus.txt"), capture_output=True, text=True).stdout
        agree &= compare(f"{package}-codec-rust-verdicts.txt", compact(output), args.write)

    spike = program("cabin-vectors", WORK / "crate-cabin", rt_version, source, SPIKE_MAIN)
    golden = run([spike, "encode"], capture_output=True, text=True).stdout
    agree &= compare("cabin-golden.txt", golden, args.write)
    verdicts = run([spike, "verify"], input=corpus("cabin-corpus.txt"), capture_output=True, text=True).stdout
    agree &= compare("cabin-rust-verdicts.txt", verdicts, args.write)

    if args.write:
        print("written; review the diff, then run just test")
    elif not agree:
        sys.exit("the committed Rust verdicts differ from the pinned release's: run `just rust-verdicts --write`")


if __name__ == "__main__":
    main()
