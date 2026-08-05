# 网络库发布

## 版本规则

发布物统一使用 `networkVersion`，遵循 SemVer。`network-bom` 约束所有模块为同一版本；破坏公共 API 时必须
提升主版本，并在变更日志中给出迁移方式。

## 本地门禁

```bash
./gradlew apiCheck
./gradlew :network-core:testDebugUnitTest :network-inspector:testDebugUnitTest \
  :network-realtime:testDebugUnitTest :network-testing:testDebugUnitTest
./gradlew :network-core:compileKotlinIosSimulatorArm64 \
  :network-kuikly:compileKotlinIosSimulatorArm64 \
  :network-inspector:compileKotlinIosSimulatorArm64 \
  :network-koin:compileKotlinIosSimulatorArm64 \
  :network-realtime:compileKotlinIosSimulatorArm64
```

有意修改公共 API 后运行 `./gradlew apiDump`，人工检查 `api/` 差异，再提交快照。不要在 CI 中自动更新快照。

## 发布配置

| 环境变量/属性 | 用途 |
| --- | --- |
| `NETWORK_VERSION` / `-PnetworkVersion` | 发布版本 |
| `NETWORK_REPOSITORY_URL` / `-PnetworkRepositoryUrl` | Maven 仓库地址 |
| `NETWORK_REPOSITORY_USERNAME` | 仓库账号 |
| `NETWORK_REPOSITORY_PASSWORD` | 仓库密码 |
| `NETWORK_SIGNING_KEY` | ASCII-armored 内存 PGP 私钥 |
| `NETWORK_SIGNING_PASSWORD` | 私钥密码 |

没有配置远程仓库或签名密钥时，构建和测试不会失败，仍可执行 `publishToMavenLocal`。正式版本必须由 CI 注入
签名和仓库凭据，源码及 Gradle 属性文件不保存秘密。

## 发布前检查

1. API/ABI 快照和变更日志已审核。
2. Android、iOS 模拟器、Kuikly 生命周期测试通过。
3. 鸿蒙适配器在具备 DevEco SDK 的构建机通过。
4. POM、sources、KMP metadata、BOM 与签名文件齐全。
5. 使用一个空白示例工程从目标仓库解析并发起脚本请求。

