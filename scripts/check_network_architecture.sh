#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
network_common="$project_root/network-core/src/commonMain/kotlin/com/catchzoon/network"
android_transport="$project_root/network-core/src/androidMain/kotlin/com/catchzoon/network/platform"
sample_common="$project_root/shared/src/commonMain/kotlin"

if command -v rg >/dev/null 2>&1; then
  search=(rg)
else
  search=(grep -R -E)
fi

fail_if_found() {
  local pattern="$1"
  local path="$2"
  local message="$3"
  if "${search[@]}" -n "$pattern" "$path"; then
    echo "network architecture check failed: $message" >&2
    exit 1
  fi
}

# ponytail: 包级静态门禁已经覆盖最容易回退的依赖方向；需要跨仓规则时再升级为编译器插件。
fail_if_found 'import (okhttp3|retrofit2)\.' "$network_common" "OkHttp/Retrofit escaped Android transport"
fail_if_found 'import com\.google\.gson\.' "$network_common" "Gson escaped Android source set"
fail_if_found 'https?://.*\+' "$sample_common" "sample rebuilt URLs with string concatenation"
fail_if_found 'com\.catchzoon\.(app|feature|data\.repository)' "$sample_common" "business code leaked into the library sample"

"${search[@]}" -q 'OkHttpClient' "$android_transport/RetrofitNetworkEngine.kt"
"${search[@]}" -q 'Retrofit\.Builder' "$android_transport/RetrofitNetworkEngine.kt"
"${search[@]}" -q '@NetworkService' "$sample_common/com/catchzoon/network/sample/api/SampleApi.kt"
"${search[@]}" -q 'closeNetworkScope' "$sample_common/com/catchzoon/network/sample/NetworkSamplePage.kt"

echo "network architecture check passed"
