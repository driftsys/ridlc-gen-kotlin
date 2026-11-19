#!/usr/bin/env python3
"""Checks the Android vendor package against the modules it packs (#189).

With no argument, it compares three sources that must agree:

- `MODULES` in `scripts/vendor-package.py`, the modules the package carries;
- the `java_library` blocks of `packaging/android/Android.bp`;
- the `api` and `implementation` dependencies of each module's
  `build.gradle.kts`.

Android.bp must hold one `java_library` per module and no other, each building
its module's `src/main/kotlin` sources, with `static_libs` equal to the module's
Gradle dependencies spelled as Soong names them. A module that depends on no
other module lists `kotlin-stdlib`, which every other one reaches through it.

With `--package`, it also opens the archive `just vendor-package` built under
`build/vendor/`, reads the Android.bp packed in it, and requires every source a
packed target builds, and nothing beyond those, the templates and the licenses.
"""

import ast
import json
import pathlib
import re
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID_BP = ROOT / "packaging" / "android" / "Android.bp"
VENDOR_SCRIPT = ROOT / "scripts" / "vendor-package.py"
OUT = ROOT / "build" / "vendor"
TEMPLATES = ["Android.bp", "METADATA", "README.md"]
LICENSES = ["LICENSE", "MODULE_LICENSE_MIT"]

# The Soong name of each Gradle library a packed module depends on. A library
# missing here fails the check, so a new dependency is mapped on purpose.
SOONG_NAMES = {"libs.kotlinx.coroutines.core": "kotlinx_coroutines"}
STDLIB = "kotlin-stdlib"


def modules():
    """`MODULES` of vendor-package.py, read without running the script."""
    tree = ast.parse(VENDOR_SCRIPT.read_text(), VENDOR_SCRIPT.name)
    for node in tree.body:
        if isinstance(node, ast.Assign) and [getattr(t, "id", None) for t in node.targets] == ["MODULES"]:
            return ast.literal_eval(node.value)
    sys.exit(f"{VENDOR_SCRIPT.relative_to(ROOT)} has no MODULES")


def java_libraries(text, origin):
    """The `java_library` blocks of an Android.bp, as {name: {property: value}}."""
    text = re.sub(r"//[^\n]*", "", text)
    libraries = {}
    for start in re.finditer(r"^java_library\s*\{", text, re.MULTILINE):
        depth, end = 0, start.end() - 1
        for end in range(start.end() - 1, len(text)):
            depth += {"{": 1, "}": -1}.get(text[end], 0)
            if depth == 0:
                break
        else:
            sys.exit(f"{origin}: a java_library block is not closed")
        body = text[start.end():end]
        properties = {}
        for match in re.finditer(r'^\s*(\w+)\s*:\s*(\[[^\]]*\]|"[^"]*"|\w+)\s*,', body, re.MULTILINE):
            key, raw = match.groups()
            if key in properties:
                sys.exit(f"{origin}: a java_library sets {key} twice")
            # A Blueprint list allows a trailing comma, which JSON does not.
            properties[key] = json.loads(re.sub(r",\s*\]$", "]", raw)) if raw[0] in '["' else raw
        name = properties.get("name")
        if not isinstance(name, str):
            sys.exit(f"{origin}: a java_library has no name")
        if name in libraries:
            sys.exit(f"{origin}: two java_library blocks are named {name}")
        libraries[name] = properties
    return libraries


def gradle_dependencies(module, packed):
    """The Soong names of a module's `api` and `implementation` dependencies."""
    script = ROOT / "modules" / module / "build.gradle.kts"
    names = set()
    for configuration, notation in re.findall(r"^\s*(\w+)\((.*)\)\s*$", script.read_text(), re.MULTILINE):
        if configuration not in ("api", "implementation"):
            continue
        project = re.fullmatch(r'project\(":([\w-]+)"\)', notation)
        if project:
            if project.group(1) not in packed:
                sys.exit(f"{module} depends on {project.group(1)}, which the vendor package does not carry")
            names.add(project.group(1))
        elif notation in SOONG_NAMES:
            names.add(SOONG_NAMES[notation])
        else:
            sys.exit(f"{script.relative_to(ROOT)}: no Soong name for {configuration}({notation})")
    if not names & set(packed):
        names.add(STDLIB)
    return names


def sources(module):
    return f"{module}/src/main/kotlin/**/*.kt"


def check_tree():
    packed = modules()
    if len(set(packed)) != len(packed):
        sys.exit("MODULES names a module twice")
    libraries = java_libraries(ANDROID_BP.read_text(), ANDROID_BP.relative_to(ROOT))
    for name in sorted(set(packed) - set(libraries)):
        sys.exit(f"Android.bp has no java_library for {name}")
    for name in sorted(set(libraries) - set(packed)):
        sys.exit(f"Android.bp builds {name}, which MODULES does not carry")
    for module in packed:
        library = libraries[module]
        if library.get("srcs") != [sources(module)]:
            sys.exit(f'Android.bp: {module} must have srcs: ["{sources(module)}"], not {library.get("srcs")}')
        expected = gradle_dependencies(module, packed)
        actual = library.get("static_libs", [])
        if len(set(actual)) != len(actual) or set(actual) != expected:
            sys.exit(f"Android.bp: {module} must have static_libs {sorted(expected)}, not {actual}")
        print(f"checked vendor module: {module}")


def check_package():
    archives = sorted(OUT.glob("*-android.tar.gz"))
    if len(archives) != 1:
        sys.exit(f"build/vendor holds {len(archives)} vendor packages, not one: run `just vendor-package` first")
    with tarfile.open(archives[0]) as tar:
        members = [m for m in tar.getmembers() if m.isfile()]
        top = {m.name.split("/", 1)[0] for m in members}
        if len(top) != 1:
            sys.exit(f"{archives[0].name} unpacks to {sorted(top)}, not one directory")
        prefix = f"{top.pop()}/"
        packed = {m.name.removeprefix(prefix) for m in members}
        if "Android.bp" not in packed:
            sys.exit(f"{archives[0].name} holds no Android.bp")
        bp = tar.extractfile(f"{prefix}Android.bp").read().decode()
    expected = set(TEMPLATES) | set(LICENSES)
    for name, library in java_libraries(bp, f"{archives[0].name}/Android.bp").items():
        tracked = {
            path.removeprefix("modules/")
            for path in subprocess.run(["git", "-C", ROOT, "ls-files", "--", f"modules/{name}/src/main/kotlin"],
                                       capture_output=True, check=True, text=True).stdout.split()
        }
        if not tracked:
            sys.exit(f"{name} has no tracked source under modules/{name}/src/main/kotlin")
        for path in sorted(tracked - packed):
            sys.exit(f"{archives[0].name} is missing a source of {name}: {path}")
        expected |= tracked
        print(f"checked packed module: {name} ({len(tracked)} sources)")
    for path in sorted(packed - expected):
        sys.exit(f"{archives[0].name} holds a file no target builds: {path}")
    for path in sorted(expected - packed):
        sys.exit(f"{archives[0].name} is missing {path}")


def main():
    arguments = sys.argv[1:]
    if arguments not in ([], ["--package"]):
        sys.exit("usage: check-vendor-package.py [--package]")
    check_tree()
    if arguments:
        check_package()


if __name__ == "__main__":
    main()
