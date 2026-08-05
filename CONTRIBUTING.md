# 贡献指南

提交前请保持模块边界：公共策略进入 `network-core`，Kuikly 生命周期进入 `network-kuikly`，调试能力进入
`network-inspector`，测试替身进入 `network-testing`，平台传输只放对应源集或宿主适配器。

- 新的公共类和非显然函数使用中文 KDoc。
- 不在日志、测试 Fixture 或 Inspector 中保存 token、Cookie、请求正文和用户隐私数据。
- Bug 修复覆盖共享根因，并留下一个能复现问题的最小测试。
- 运行相关单元测试和 `apiCheck`；有意改变 API 时运行 `apiDump` 并审核快照。
- 新依赖必须说明标准库、平台能力和现有依赖为何不能覆盖。

