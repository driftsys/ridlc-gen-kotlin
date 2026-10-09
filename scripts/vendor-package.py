#!/usr/bin/env python3
"""Builds build/vendor/ridl-rt-kt-<version>-android.tar.gz.

The runtime sources as an Android tree vendors them (packaging/android/README.md):
the committed sources of ridl-rt-kt, ridl-rt-kt-coroutines and
ridl-rt-kt-loopback, read with `git archive`, with the Android.bp, METADATA and
README of packaging/android filled in, LICENSE, and an empty MODULE_LICENSE_MIT.
Every entry carries the commit's time and no owner, and the gzip header no
time, so one commit always gives the same bytes.
"""

import datetime
import gzip
import io
import pathlib
import shutil
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build" / "vendor"
MODULES = ["ridl-rt-kt", "ridl-rt-kt-coroutines", "ridl-rt-kt-loopback"]
TEMPLATES = ["Android.bp", "METADATA", "README.md"]


def git(*args, binary=False):
    return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, check=True,
                          text=not binary).stdout


def version():
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        key, _, value = line.partition("=")
        if key.strip() == "version":
            return value.strip()
    sys.exit("gradle.properties has no version")


def main():
    ver = version()
    tag = f"v{ver}"
    name = f"ridl-rt-kt-{ver}"
    epoch = int(git("log", "-1", "--format=%ct", "HEAD").strip())
    day = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc)

    # path in the package -> (bytes, mode)
    entries = {}
    sources = [f"modules/{module}/src/main" for module in MODULES]
    archive = git("archive", "--format=tar", "HEAD", "--", "LICENSE", *sources, binary=True)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            path = member.name
            if path != "LICENSE":
                path = path.removeprefix("modules/")
            entries[path] = (tar.extractfile(member).read(), member.mode)
    entries["MODULE_LICENSE_MIT"] = (b"", 0o644)
    for template in TEMPLATES:
        text = (ROOT / "packaging" / "android" / template).read_text()
        text = (text.replace("@VERSION_NUMBER@", ver).replace("@VERSION@", tag).replace("@YEAR@", str(day.year))
                .replace("@MONTH@", str(day.month)).replace("@DAY@", str(day.day)))
        for placeholder in ("@VERSION_NUMBER@", "@VERSION@", "@YEAR@", "@MONTH@", "@DAY@"):
            if placeholder in text:
                sys.exit(f"{template}: {placeholder} was left unfilled")
        entries[template] = (text.encode(), 0o644)

    # Every source of the three modules is in the package, and nothing else.
    expected = len(git("ls-files", "--", *sources).split())
    actual = sum(1 for path in entries if "/src/main/" in path)
    if expected != actual:
        sys.exit(f"the package holds {actual} sources, the modules {expected}")

    # Cleared first: the release attaches every archive here.
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True)
    target = OUT / f"{name}-android.tar.gz"
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for path in sorted(entries):
            data, mode = entries[path]
            info = tarfile.TarInfo(f"{name}/{path}")
            info.size = len(data)
            info.mtime = epoch
            info.mode = mode
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(data))
    with open(target, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
        gz.write(buffer.getvalue())
    print(f"{target.relative_to(ROOT)} ({actual} sources)")


if __name__ == "__main__":
    main()
