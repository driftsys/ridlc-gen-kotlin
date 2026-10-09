#!/usr/bin/env bash
# Installs the ridlc-gen-kotlin plugin from a GitHub release of
# driftsys/ridlc-gen-kotlin. It downloads the release's distribution tarball and
# SHA256SUMS, checks the tarball's SHA-256, unpacks it under
# RIDLC_GEN_KOTLIN_HOME, and links its start script into
# RIDLC_GEN_KOTLIN_INSTALL_DIR. Usage:
#
#   curl -fsSL https://github.com/driftsys/ridlc-gen-kotlin/releases/latest/download/install.sh | bash
#
# The plugin runs on a JVM: it needs a JDK or JRE 17 or later at run time, on
# PATH or named by JAVA_HOME.
#
# Settings:
#   RIDLC_GEN_KOTLIN_VERSION      the release tag, such as v0.1.0. The script a
#                                 release carries defaults to that release; the
#                                 one on main defaults to the newest v* release.
#   RIDLC_GEN_KOTLIN_HOME         where distributions are unpacked, one directory
#                                 per version (default ~/.local/share/ridlc-gen-kotlin).
#   RIDLC_GEN_KOTLIN_INSTALL_DIR  where the `ridlc-gen-kotlin` link goes
#                                 (default ~/.local/bin).
#
# RIDLC_GEN_KOTLIN_BASE_URL overrides where release assets are fetched from
# (default https://github.com/$REPO/releases/download), and
# RIDLC_GEN_KOTLIN_DRY_RUN=1 prints the tarball's URL and stops. Both exist so
# `just install-check` and the release workflow can run the script against
# local files; they are test hooks, not settings an end user is expected to set.
set -eu

REPO="driftsys/ridlc-gen-kotlin"
NAME="ridlc-gen-kotlin"
# The release workflow writes the release's own tag here in the copy it
# attaches to that release.
RELEASE_VERSION=""
INSTALL_DIR="${RIDLC_GEN_KOTLIN_INSTALL_DIR:-$HOME/.local/bin}"
HOME_DIR="${RIDLC_GEN_KOTLIN_HOME:-$HOME/.local/share/$NAME}"
BASE_URL="${RIDLC_GEN_KOTLIN_BASE_URL:-https://github.com/$REPO/releases/download}"

get_version() {
  if [ -n "${RIDLC_GEN_KOTLIN_VERSION:-}" ]; then
    printf '%s\n' "$RIDLC_GEN_KOTLIN_VERSION"
    return
  fi
  if [ -n "$RELEASE_VERSION" ]; then
    printf '%s\n' "$RELEASE_VERSION"
    return
  fi
  body=$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=100") \
    || { echo "error: could not reach the GitHub API" >&2; exit 1; }
  version=$(printf '%s\n' "$body" | grep '"tag_name"' | cut -d'"' -f4 | grep '^v' | head -1)
  if [ -z "$version" ]; then
    echo "error: no v* release of $REPO found" >&2
    exit 1
  fi
  printf '%s\n' "$version"
}

sha256_check() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -c "$1"; else shasum -a 256 -c "$1"; fi
}

check_java() {
  local java="java"
  if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; fi
  if ! command -v "$java" >/dev/null 2>&1; then
    echo "warning: no java found; the plugin needs a JDK or JRE 17 or later to run" >&2
    return
  fi
  major=$("$java" -version 2>&1 | sed -n 's/.* version "\([0-9]*\).*/\1/p' | head -1)
  if [ -n "$major" ] && [ "$major" -lt 17 ]; then
    echo "warning: $java is Java $major; the plugin needs Java 17 or later" >&2
  fi
}

main() {
  local version tarball url
  version="$(get_version)"
  tarball="$NAME-${version#v}.tar"
  url="$BASE_URL/$version/$tarball"

  if [ -n "${RIDLC_GEN_KOTLIN_DRY_RUN:-}" ]; then
    echo "$url"
    return
  fi

  echo "Installing $NAME $version to $HOME_DIR" >&2
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "${tmpdir:-}"' EXIT INT TERM
  curl -fsSL "$url" -o "$tmpdir/$tarball"
  curl -fsSL "$BASE_URL/$version/SHA256SUMS" -o "$tmpdir/SHA256SUMS"
  if ! grep " \*\{0,1\}$tarball\$" "$tmpdir/SHA256SUMS" > "$tmpdir/$tarball.sha256"; then
    echo "error: SHA256SUMS of $version lists no $tarball" >&2
    exit 1
  fi
  (cd "$tmpdir" && sha256_check "$tarball.sha256" >/dev/null)

  mkdir -p "$tmpdir/unpacked"
  tar -xf "$tmpdir/$tarball" -C "$tmpdir/unpacked"
  # The distribution holds one directory, bin/ and lib/ under it.
  set -- "$tmpdir"/unpacked/*/
  if [ $# -ne 1 ] || [ ! -x "$1/bin/$NAME" ]; then
    echo "error: $tarball holds no single directory with bin/$NAME" >&2
    exit 1
  fi

  # A version's directory is replaced whole, then the link is renamed over the
  # old one, so a run of the plugin never sees half an install.
  mkdir -p "$HOME_DIR" "$INSTALL_DIR"
  target="$HOME_DIR/$version"
  rm -rf "$target.new"
  mv "$1" "$target.new"
  rm -rf "$target"
  mv "$target.new" "$target"
  ln -s "$target/bin/$NAME" "$INSTALL_DIR/.$NAME.new"
  mv -f "$INSTALL_DIR/.$NAME.new" "$INSTALL_DIR/$NAME"

  echo "Installed $INSTALL_DIR/$NAME -> $target/bin/$NAME" >&2
  check_java
  case ":$PATH:" in
    *":$INSTALL_DIR:"*) ;;
    *)
      echo "" >&2
      echo "Add to your PATH:" >&2
      echo "  export PATH=\"$INSTALL_DIR:\$PATH\"" >&2
      ;;
  esac
}

main
