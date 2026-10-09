#!/usr/bin/env bash
# Builds build/vendor/ridl-rt-kt-<version>-android.tar.gz: the runtime sources
# as an Android tree vendors them (packaging/android/README.md). The sources are
# the committed ones, read with `git archive`, and the archive's file times are
# the commit's, so one commit always gives the same bytes.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(sed -n 's/^version=//p' "$root/gradle.properties")"
[ -n "$version" ] || { echo "gradle.properties has no version" >&2; exit 1; }
tag="v$version"
name="ridl-rt-kt-$tag"
modules=(ridl-rt-kt ridl-rt-kt-coroutines ridl-rt-kt-loopback)

out="$root/build/vendor"
rm -rf "$out"
stage="$out/$name"
mkdir -p "$stage"

paths=(LICENSE)
for module in "${modules[@]}"; do paths+=("modules/$module/src/main"); done
git -C "$root" archive --format=tar HEAD -- "${paths[@]}" | tar -x -C "$out"
for module in "${modules[@]}"; do
  mkdir -p "$stage/$module/src"
  mv "$out/modules/$module/src/main" "$stage/$module/src/main"
done
rm -rf "$out/modules"
mv "$out/LICENSE" "$stage/LICENSE"
: > "$stage/MODULE_LICENSE_MIT"

epoch="$(git -C "$root" log -1 --format=%ct HEAD)"
read -r year month day < <(date -u -d "@$epoch" '+%Y %-m %-d')
for file in Android.bp METADATA README.md; do
  sed -e "s/@VERSION@/$tag/g" -e "s/@YEAR@/$year/" -e "s/@MONTH@/$month/" -e "s/@DAY@/$day/" \
    "$root/packaging/android/$file" > "$stage/$file"
done

# Every Kotlin source of the three modules is in the package, and nothing else
# under their src/main.
expected="$(git -C "$root" ls-files "${paths[@]:1}" | wc -l)"
actual="$(find "$stage" -path '*/src/main/*' -type f | wc -l)"
[ "$expected" = "$actual" ] || { echo "the package holds $actual sources, the modules $expected" >&2; exit 1; }
if grep -rl '@[A-Z]*@' "$stage"/Android.bp "$stage"/METADATA "$stage"/README.md; then
  echo "a placeholder was left unfilled" >&2; exit 1
fi

archive="$out/$name-android.tar.gz"
tar --sort=name --mtime="@$epoch" --owner=0 --group=0 --numeric-owner --format=gnu \
  -C "$out" -cf - "$name" | gzip -n > "$archive"
rm -rf "$stage"
echo "${archive#"$root"/} ($actual sources)"
