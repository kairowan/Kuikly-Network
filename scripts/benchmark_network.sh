#!/usr/bin/env bash
set -euo pipefail

# 只运行无网络、无设备依赖的公共层热点基准，结果会输出到测试标准输出。
exec "$(dirname "$0")/../gradlew" \
  :network-core:testDebugUnitTest \
  --tests com.catchzoon.network.core.NetworkHotPathBenchmarkTest \
  --info \
  "$@"
