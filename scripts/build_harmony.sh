#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VENDOR="$ROOT/vendor/KuiklyUI-ohos"
APP="$ROOT/ohosApp"
ARTIFACT=${1:-hap}
case "$ARTIFACT" in
  har)
    KOTLIN_LINK_TASK=linkSharedReleaseSharedOhosArm64
    KOTLIN_OUTPUT_DIR=sharedReleaseShared
    HVIGOR_TASK=assembleHar
    HVIGOR_MODULE=network_ohos
    BUILD_MODE=release
    ;;
  hap)
    KOTLIN_LINK_TASK=linkSharedDebugSharedOhosArm64
    KOTLIN_OUTPUT_DIR=sharedDebugShared
    HVIGOR_TASK=assembleHap
    HVIGOR_MODULE=entry
    BUILD_MODE=debug
    ;;
  *)
    printf '%s\n' "用法: $0 [har|hap]" >&2
    exit 2
    ;;
esac
DEVECO_HOME=${DEVECO_STUDIO_HOME:-/Applications/DevEco-Studio.app}
case "$DEVECO_HOME" in
  */Contents) DEVECO_CONTENTS=$DEVECO_HOME ;;
  *) DEVECO_CONTENTS="$DEVECO_HOME/Contents" ;;
esac

if [ -n "${OHOS_SDK_HOME:-}" ]; then
  SDK_HOME=$OHOS_SDK_HOME
else
  SDK_HOME="$DEVECO_CONTENTS/sdk/default/openharmony"
fi

if [ ! -d "$SDK_HOME" ]; then
  printf '%s\n' \
    "OpenHarmony SDK 未找到：$SDK_HOME" \
    "请安装 DevEco Studio，并设置 OHOS_SDK_HOME=/path/to/openharmony 后重试。" >&2
  exit 2
fi
export OHOS_SDK_HOME=$SDK_HOME
export OHOS_BASE_SDK_HOME=${OHOS_BASE_SDK_HOME:-$SDK_HOME}
export DEVECO_SDK_HOME=${DEVECO_SDK_HOME:-$DEVECO_CONTENTS/sdk}
export COPYFILE_DISABLE=1

# ExFAT 会把 macOS 扩展属性写成 ._* 文件，Hvigor 若直接在项目目录构建会将其打进 HAP。
USER_CACHE_DIR=$(getconf DARWIN_USER_CACHE_DIR 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}")
USER_CACHE_DIR=$(CDPATH= cd -- "$USER_CACHE_DIR" && pwd -P)
HARMONY_BUILD_ROOT=${HARMONY_BUILD_ROOT:-"${USER_CACHE_DIR%/}/KuiklyNetwork/hvigor-build"}
mkdir -p "$HARMONY_BUILD_ROOT"

HAR_OUTPUT="$ROOT/network-ohos/build/default/outputs/default/network_ohos.har"
if [ "$ARTIFACT" = har ]; then
  "$ROOT/scripts/bootstrap_kuikly_harmony.sh"

  KUIKLY_AGP_VERSION=7.4.2 \
  KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010 \
    "$VENDOR/gradlew" \
      -p "$VENDOR" \
      -c "$VENDOR/settings.2.0.ohos.gradle.kts" \
      -I "$ROOT/harmony-kmp/include-kuikly-network.init.gradle.kts" \
      ":kuikly-network:$KOTLIN_LINK_TASK" \
      --stacktrace

  NETWORK_BUILD_DIR=$(KUIKLY_AGP_VERSION=7.4.2 \
  KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010 \
    "$VENDOR/gradlew" \
      -p "$VENDOR" \
      -c "$VENDOR/settings.2.0.ohos.gradle.kts" \
      -I "$ROOT/harmony-kmp/include-kuikly-network.init.gradle.kts" \
      :kuikly-network:properties -q | sed -n 's/^buildDir: //p' | tail -n 1)
  if [ -z "$NETWORK_BUILD_DIR" ]; then
    printf '%s\n' "Unable to resolve the kuikly-network Gradle build directory." >&2
    exit 1
  fi

  NATIVE_OUTPUT_DIR="$NETWORK_BUILD_DIR/bin/ohosArm64/$KOTLIN_OUTPUT_DIR"
  HAR_LIB_DIR="$ROOT/network-ohos/libs/arm64-v8a"
  HAR_INCLUDE_DIR="$ROOT/network-ohos/include"
  mkdir -p "$HAR_LIB_DIR" "$HAR_INCLUDE_DIR"
  cp "$NATIVE_OUTPUT_DIR/libshared.so" "$HAR_LIB_DIR/libshared.so"
  cp "$NATIVE_OUTPUT_DIR/libshared_api.h" "$HAR_INCLUDE_DIR/libshared_api.h"
  # 清理旧版脚本留在 entry 中的副本，避免 HAP 同时合并两份同名 SO。
  rm -f "$APP/entry/libs/arm64-v8a/libshared.so"
  rm -f "$APP/entry/src/main/cpp/thirdparty/biz_entry/libshared_api.h"
elif [ ! -f "$HAR_OUTPUT" ]; then
  printf '%s\n' \
    "Harmony demo requires the packaged HAR: $HAR_OUTPUT" \
    "Run ./scripts/build_harmony.sh har first." >&2
  exit 1
fi

if command -v ohpm >/dev/null 2>&1; then
  OHPM_BIN=$(command -v ohpm)
else
  OHPM_BIN="$DEVECO_CONTENTS/tools/ohpm/bin/ohpm"
fi
if [ ! -x "$OHPM_BIN" ]; then
  printf '%s\n' "ohpm was not found. Install DevEco Studio or set DEVECO_STUDIO_HOME." >&2
  exit 1
fi

cd "$APP"
"$OHPM_BIN" install
if [ "$ARTIFACT" = hap ]; then
  INSTALLED_HAR="$APP/entry/oh_modules/@catchzoon/network-ohos"
  if [ ! -f "$INSTALLED_HAR/libs/arm64-v8a/libshared.so" ] || \
     [ ! -f "$INSTALLED_HAR/include/libshared_api.h" ]; then
    printf '%s\n' "Installed @catchzoon/network-ohos HAR is incomplete: $INSTALLED_HAR" >&2
    exit 1
  fi
  INSTALLED_HAR_REAL=$(CDPATH= cd -- "$INSTALLED_HAR" && pwd -P)
  SOURCE_MODULE_REAL=$(CDPATH= cd -- "$ROOT/network-ohos" && pwd -P)
  if [ "$INSTALLED_HAR_REAL" = "$SOURCE_MODULE_REAL" ]; then
    printf '%s\n' "Harmony demo resolved the source module instead of network_ohos.har." >&2
    exit 1
  fi
  printf '%s\n' "Harmony demo dependency verified from HAR: $INSTALLED_HAR_REAL"
fi
if command -v dot_clean >/dev/null 2>&1; then
  dot_clean -m "$APP"
  dot_clean -m "$ROOT/network-ohos"
fi
if [ -x ./hvigorw ]; then
  HVIGOR_BIN=./hvigorw
else
  HVIGOR_BIN="$DEVECO_CONTENTS/tools/hvigor/bin/hvigorw"
fi
if [ ! -x "$HVIGOR_BIN" ]; then
  printf '%s\n' "hvigorw was not found. Install DevEco Studio or set DEVECO_STUDIO_HOME." >&2
  exit 1
fi
"$HVIGOR_BIN" "$HVIGOR_TASK" \
  --mode module \
  -p product=default \
  -p "module=$HVIGOR_MODULE@default" \
  -p "buildMode=$BUILD_MODE" \
  -p build-cache-dir="$HARMONY_BUILD_ROOT" \
  --no-daemon

if [ "$ARTIFACT" = har ]; then
  OUTPUT_DIR="$ROOT/network-ohos/build/default/outputs/default"
  OUTPUT="$HAR_OUTPUT"
  if [ ! -f "$OUTPUT" ]; then
    printf '%s\n' "Hvigor succeeded but $OUTPUT was not generated." >&2
    exit 1
  fi
  if ! tar -tzf "$OUTPUT" | grep -Fxq 'package/libs/arm64-v8a/libshared.so'; then
    printf '%s\n' "HAR verification failed: package/libs/arm64-v8a/libshared.so is missing." >&2
    exit 1
  fi
  if ! tar -tzf "$OUTPUT" | grep -Fxq 'package/include/libshared_api.h'; then
    printf '%s\n' "HAR verification failed: package/include/libshared_api.h is missing." >&2
    exit 1
  fi
else
  OUTPUT_DIR="$APP/entry/build/default/outputs/default"
  OUTPUT="$OUTPUT_DIR/entry-default-unsigned.hap"
  if [ ! -f "$OUTPUT" ]; then
    printf '%s\n' "Hvigor succeeded but $OUTPUT was not generated." >&2
    exit 1
  fi
fi
if command -v dot_clean >/dev/null 2>&1; then
  dot_clean -m "$OUTPUT_DIR"
fi
printf '%s\n' "$ARTIFACT generated: $OUTPUT"
