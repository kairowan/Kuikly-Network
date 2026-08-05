#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VENDOR="$ROOT/vendor/KuiklyUI-ohos"
VERSION=2.23.3

if [ ! -f "$VENDOR/gradlew" ]; then
  mkdir -p "$ROOT/vendor"
  if git -c http.version=HTTP/1.1 clone --depth 1 --filter=blob:none --sparse --branch "$VERSION" \
    https://github.com/Tencent-TDS/KuiklyUI.git "$VENDOR"; then
    git -C "$VENDOR" sparse-checkout set buildSrc core core-annotations core-ksp gradle
  else
    if [ -e "$VENDOR" ]; then
      printf '%s\n' "Kuikly clone failed and left an incomplete directory: $VENDOR" >&2
      exit 1
    fi
    DOWNLOAD_DIR=$(mktemp -d)
    trap 'rm -rf "$DOWNLOAD_DIR"' EXIT HUP INT TERM
    curl --fail --location --retry 3 \
      "https://codeload.github.com/Tencent-TDS/KuiklyUI/tar.gz/refs/tags/$VERSION" \
      --output "$DOWNLOAD_DIR/kuikly.tar.gz"
    mkdir -p "$VENDOR"
    tar -xzf "$DOWNLOAD_DIR/kuikly.tar.gz" --strip-components=1 -C "$VENDOR"
    git -C "$VENDOR" init --quiet
  fi
fi

if command -v dot_clean >/dev/null 2>&1; then
  dot_clean -m "$VENDOR"
fi

PATCH="$ROOT/harmony-kmp/patches/kuikly-ohos-gradle.patch"
if git -C "$VENDOR" apply --check "$PATCH" >/dev/null 2>&1; then
  git -C "$VENDOR" apply "$PATCH"
fi

printf '%s\n' "Kuikly Harmony sources ready at $VENDOR"
