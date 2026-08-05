#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VENDOR="$ROOT/vendor/KuiklyUI"
VERSION=2.24.0

if [ ! -f "$VENDOR/OpenKuiklyIOSRender.podspec" ]; then
  mkdir -p "$VENDOR/core-render-ios"
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
  BASE_URL="https://cdn.jsdelivr.net/gh/Tencent-TDS/KuiklyUI@$VERSION"
  curl -L --fail --retry 4 --retry-all-errors -o "$VENDOR/LICENSE" "$BASE_URL/LICENSE"
  curl -L --fail --retry 4 --retry-all-errors -o "$VENDOR/OpenKuiklyIOSRender.podspec" "$BASE_URL/OpenKuiklyIOSRender.podspec"
  curl -L --fail --retry 4 --retry-all-errors \
    -o "$TMP_DIR/tree.json" \
    "https://api.github.com/repos/Tencent-TDS/KuiklyUI/git/trees/$VERSION?recursive=1"
  python3 - "$TMP_DIR/tree.json" > "$TMP_DIR/ios-files.txt" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    tree = json.load(source)["tree"]
for item in tree:
    path = item.get("path", "")
    if item.get("type") == "blob" and path.startswith("core-render-ios/"):
        print(path)
PY
  (
    cd "$VENDOR"
    xargs -P 12 -I '{}' sh -c '
      mkdir -p "$(dirname "$1")"
      curl -L --fail --retry 4 --retry-all-errors --silent --show-error -o "$1" "$2/$1"
    ' sh '{}' "$BASE_URL" < "$TMP_DIR/ios-files.txt"
  )
fi

cd "$ROOT"
./gradlew :shared:podspec :shared:generateDummyFramework --no-configuration-cache
cd iosApp
pod install
