#!/usr/bin/env python3
"""Packs the conformance results of a build into one release asset.

Reads the JUnit XML that the conformance test runs left under each module's
build/test-results/test, and the Rust codec verdict files the conformance
module checks in. Fails when a suite has no results or any test failed. Writes
build/compliance/ridlc-gen-kotlin-<version>-compliance.tar.gz, which holds:

- SUMMARY.md: the version, the commit, the pinned ridl release, and the test
  counts of each suite;
- junit/<module>/*.xml: the results themselves;
- rust-verdicts/*.txt: the Rust codec verdicts the Kotlin codec matched.

Run `just compliance`, which runs the suites first.
"""

import pathlib
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build" / "compliance"

# Each module whose test run is a conformance result, and what it checks.
SUITES = {
    "conformance": "the plugin against the pinned ridl release, the Rust codec verdicts included",
    "ridl-rt-kt-conformance": "the port contract suite's own tests",
    "ridl-rt-kt-loopback": "the port contract suite run over the loopback runtime",
}


def version():
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        key, _, value = line.partition("=")
        if key.strip() == "version":
            return value.strip()
    sys.exit("gradle.properties has no version")


def counts(xml_files):
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in xml_files:
        suite = ET.parse(path).getroot()
        for key in total:
            total[key] += int(suite.get(key, "0"))
    return total


def main():
    ver = version()
    commit = subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"],
                            capture_output=True, text=True, check=True).stdout.strip()
    release = (ROOT / "modules" / "conformance" / "ridl-release").read_text().strip()

    name = f"ridlc-gen-kotlin-{ver}-compliance"
    stage = OUT / name
    shutil.rmtree(OUT, ignore_errors=True)
    stage.mkdir(parents=True)

    rows = []
    failed = []
    for module, what in SUITES.items():
        results = ROOT / "modules" / module / "build" / "test-results" / "test"
        xml_files = sorted(results.glob("TEST-*.xml"))
        if not xml_files:
            sys.exit(f"no test results under {results.relative_to(ROOT)}: run `just compliance`")
        target = stage / "junit" / module
        target.mkdir(parents=True)
        for path in xml_files:
            shutil.copy2(path, target)
        c = counts(xml_files)
        passed = c["tests"] - c["failures"] - c["errors"] - c["skipped"]
        rows.append(f"| `{module}` | {what} | {c['tests']} | {passed} | "
                    f"{c['failures'] + c['errors']} | {c['skipped']} |")
        if c["failures"] or c["errors"]:
            failed.append(module)

    verdicts = sorted((ROOT / "modules" / "conformance" / "src" / "test" / "resources" / "flatbuffers")
                      .glob("*rust-verdicts.txt"))
    (stage / "rust-verdicts").mkdir()
    for path in verdicts:
        shutil.copy2(path, stage / "rust-verdicts")

    summary = "\n".join([
        f"# ridlc-gen-kotlin {ver}: conformance results",
        "",
        f"- Commit: `{commit}`",
        f"- Pinned ridl release: `{release}`",
        f"- Rust codec verdict files: {len(verdicts)}",
        "",
        "| Module | What it checks | Tests | Passed | Failed | Skipped |",
        "| ------ | -------------- | ----: | -----: | -----: | ------: |",
        *rows,
        "",
    ])
    (stage / "SUMMARY.md").write_text(summary)

    if failed:
        sys.exit(f"conformance failures in: {', '.join(failed)}")

    archive = OUT / f"{name}.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(stage, arcname=name)
    print(summary)
    print(archive.relative_to(ROOT))


if __name__ == "__main__":
    main()
