#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd -- "$script_dir/.." && pwd)"

required_readmes=(
  "modules/ridlc-gen-kotlin/README.md"
  "modules/ridl-rt-kt/README.md"
  "modules/ridl-rt-kt-loopback/README.md"
  "modules/ridl-rt-kt-coroutines/README.md"
  "modules/ridl-rt-kt-conformance/README.md"
  "modules/conformance/README.md"
  "samples/cabin/README.md"
  "shared/README.md"
)

required_root_files=(
  ".editorconfig"
  ".gitattributes"
  ".gitignore"
  ".git-std.toml"
  "AGENTS.md"
  "CONTRIBUTING.md"
  "LICENSE"
  "README.md"
  "justfile"
  "settings.gradle.kts"
  "gradlew"
  "gradlew.bat"
  "gradle/wrapper/gradle-wrapper.jar"
  "gradle/wrapper/gradle-wrapper.properties"
  "gradle/libs.versions.toml"
  "modules/conformance/ridl-release"
)

for relative_path in "${required_root_files[@]}" "${required_readmes[@]}"; do
  if [[ ! -f "$root_dir/$relative_path" ]]; then
    printf 'missing required path: %s\n' "$relative_path" >&2
    exit 1
  fi
done

# Kotlin and Gradle sources are tracked; build outputs, generated sources,
# binaries other than the Gradle wrapper jar, and the toolchains of other
# languages are not (docs/design.md §6, D-K9: no ridl source is vendored).
while IFS= read -r -d '' tracked_path; do
  case "$tracked_path" in
    gradle/wrapper/gradle-wrapper.jar)
      ;;
    *.rs|\
    Cargo.toml|*/Cargo.toml|Cargo.lock|*/Cargo.lock|\
    pom.xml|*/pom.xml|\
    mvnw|*/mvnw|mvnw.cmd|*/mvnw.cmd|\
    */gradlew|*/gradlew.bat|*/settings.gradle.kts|*/settings.gradle|\
    .gradle/*|*/.gradle/*|.kotlin/*|*/.kotlin/*|\
    build/*|*/build/*|out/*|*/out/*|target/*|*/target/*|\
    generated/*|*/generated/*|generated-sources/*|*/generated-sources/*|\
    gen/*|*/gen/*|dist/*|*/dist/*|\
    *.class|*.jar|*.aar|*.war|*.ear|*.kotlin_module|*.pom|*.module)
      printf 'forbidden tracked file: %s\n' "$tracked_path" >&2
      exit 1
      ;;
  esac
done < <(git -C "$root_dir" ls-files -z)

for relative_path in "${required_readmes[@]}"; do
  readme="$root_dir/$relative_path"
  if ! grep -Eq '^##[[:space:]]+Responsibility:?[[:space:]]*$' "$readme"; then
    printf 'README is missing a Responsibility heading: %s\n' "$relative_path" >&2
    exit 1
  fi
  if ! grep -Eq '^##[[:space:]]+Status:?[[:space:]]*$' "$readme"; then
    printf 'README is missing a Status heading: %s\n' "$relative_path" >&2
    exit 1
  fi
  printf 'checked README: %s\n' "$relative_path"
done

release="$(tr -d '[:space:]' < "$root_dir/modules/conformance/ridl-release")"
if [[ ! "$release" =~ ^editor-v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf 'modules/conformance/ridl-release is not a ridl release tag: %s\n' "$release" >&2
  exit 1
fi

printf 'repository check passed\n'
