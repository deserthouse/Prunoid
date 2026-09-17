# SDK-Pruner Rules

SDK-Pruner 的组件识别与安全禁用规则库（独立仓库，Apache-2.0）。

## 这是什么

面向 Android「SDK 组件审计/管理工具」的开放规则库：识别第三方 app 内嵌的
广告 / 统计 / 推送 / 基础设施 SDK，并给出组件级的安全处置建议。

- **识别**：包名前缀 + 组件类名精确锚点 + 组件类名前缀（三路，端侧确定性匹配）
- **处置**：`safeToBlock` / `sideEffect` / `confidence` 逐条标注，不预测、不夸大
- **可溯**：每条规则带 `sources[]`（数据出处）与 `contributors`，合并脚本可复现

## 使用

- **端侧消费**：SDK-Pruner app 支持「内置快照 + 订阅源」双层加载；
  订阅 URL 指向本仓库的 `rules/snapshot.json`（原始文件直链即可）。
- **合并语义**：订阅条目按 `id` 覆盖内置快照，新 `id` 追加。

## 数据来源与协议

| 来源 | 协议 | 用法 |
|---|---|---|
| [LibChecker-Rules](https://github.com/LibChecker/LibChecker-Rules) | Apache-2.0 | 组件识别锚点（直接继承） |
| [blocker-general-rules](https://github.com/lihenggui/blocker-general-rules) | Apache-2.0 | SDK 禁用规则（468 条，直接继承） |
| Fuck.AD dex 提取 | 客观事实 | 聚合广告 SDK 根包名（16 家） |
| oF2pks/3xodusprivacy-toolbox | 无声明（仅事实字段） | 包名前缀补充（低置信度） |
| 实机 APK 分析 | 一手数据 | 置信度最高 |

明确不使用：DuckDuckGo tracker-blocklists（CC BY-NC-SA 禁商用）、
AppManager 数据文件（GPL）、Exodus 数据库直接合并（ODbL 同源义务）。
详见主项目 `research/2026-09-17_数据源合规与建库原则.md`。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。PR 会被 CI 自动校验 schema 与
id 冲突；描述文案请自行撰写（不要从其他项目复制）。

## 协议

Apache-2.0。继承数据的署名见 [NOTICE](NOTICE)。
