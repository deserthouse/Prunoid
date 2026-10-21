# 审计后续工作安排（2026-10-21）

> 来源：GitHub 维护审计轮（worklog：`C:\Users\deser\Projects\github-audit-2026-10-21\remediation-worklog-2026-10-21.md`）。时机类待办，随后续版本流程执行。

## PRUNOID-01 剩余：通道字段统一（构建侧）

现状：全部 Release 均为 prerelease=true（正确），CHANGELOG 头部已声明"Alpha 阶段全部为 pre-release"口径；但历史 Release 标题/正文混用 Beta（v0.21.1"Beta status stays"）与 Alpha（v0.21.2/3）措辞。

待办（随下个版本出包流程）：
1. 构建侧引入唯一通道字段（如 build.gradle 的 `channel = "alpha"`），由它生成 Release 标题后缀与 prerelease 标志，不再手写。
2. v0.21.1 Release 正文加一行更正记录："通道口径统一为 Alpha；本文原 'Beta status stays' 作废"（gh release edit，一次元数据操作）。
3. 新版本发布时标题带通道（例：`v0.22.2 (vc38) — Alpha`），与 CHANGELOG 头部口径互证。

## 已关闭项（勿重做）

- PRUNOID-02 文档侧：CHANGELOG 已建（0.18.0→0.22.1 + Unreleased，Keep a Changelog 结构）。
- PRUNOID-03：IFW 绝对承诺已改已测范围表述（ab09026，中英同步）。
- PRUNOID-04：About 已更新；README 重复"不上传"段收敛为下述补丁方向（可随下次 README 改动顺手：首屏一句数据行为摘要+链接 DISCLAIMER）。
