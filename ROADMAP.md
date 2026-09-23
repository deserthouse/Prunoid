# Prunoid 路线图与开发计划

> Prunoid development roadmap. Milestones land when their acceptance criteria pass on emulator (rooted) before anything else.

## 总路线：三里程碑 + 一个持续动作

```
M1 最小可用 ──→ M2 规则生态 ──→ M3 自动化与共建
（扫描+禁用+内置规则）  （订阅+双引擎）  （自动重应用+Magisk+社区）
        └──── 持续动作：规则库运营（冷启动 → 社区 PR → CI 校验）
```

## M1：最小可用（目标：实机上能安全地完成一次"扫描→识别→禁用→恢复"）

**R1 项目脚手架**（半天级）
- 对齐 OptIcon 工具链：Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / Java 21 / compileSdk=targetSdk=37 / minSdk 31
- 单 `:app` 模块、无 DI、命名空间 `io.github.deserthouse.sdkpruner`
- 依赖：libsu / kotlinx-serialization-json / DataStore / Coil / OkHttp / Compose BOM + material3 1.5.x（M3E）
- 验收：空壳 app 在模拟器构建安装运行

**R2 规则 schema + 冷启动快照**（1~2 天级）
- schema：Thanox/Blocker 共用范本扩展 `name/company/category/detect/components/safeToBlock/sideEffect/whitelist/source[]/confidence/contributors`（source/confidence 为我们独有，逐条可溯源）
- 冷启动合并脚本（Python，本地留档）：正源 blocker-general-rules(468) + LCR v44 骨架（识别锚点）+ Fuck.AD 类名(16 家) + oF2pks 事实参考；产出内置快照 JSON 打进 assets
- 验收：快照条数 ≥400，逐条带 source 字段，schema 校验通过
- 红线执行：Exodus ODbL 数据不进快照；GPL/AGPL/无协议源零拷贝

**R3 扫描器**（1 天级）
- PackageManager 枚举第三方 app + 四类组件；按规则 detect 匹配 → SDK 识别报告（命中规则、组件清单、safeToBlock 分级）
- 系统 app 直接排除（M1 不碰）
- 验收：模拟器上对装有多家 SDK 的样本 app 出正确识别报告

**R4 禁用引擎 + 安全层**（2 天级，本项目的心脏，宁可慢）
- IFW 主引擎：libsu root shell 写 `/data/system/ifw/<pkg>.xml`（参考 blocker core/ifw-api 的 CRUD 语义：空规则删文件、按包分文件）
- pm disable 辅引擎：按组件粒度 + 状态回读
- 安全层（M1 门槛，不可裁剪）：操作前自动全量备份（IFW 规则 + pm 状态导出）；一键恢复/一键清除全部 IFW 规则；系统/框架组件白名单硬拦截（数据底座 = blocker-general-rules 的 875 条框架组件知识）
- 验收：模拟器验证 UI 与状态机；root 写入逻辑在脚本级单测；实机验收由用户执行

**R5 M3E UI**（1~2 天级）
- 三屏：app 列表（图标+已识别 SDK 徽标）→ app 详情（SDK 分组、组件清单、安全等级着色）→ 操作（选规则应用/恢复，含确认与备份提示）
- 验收：扫描 100+ app 不卡顿，中性审计口径文案（不营销化、不预装自动禁用）

**M1 完工标准**：模拟器全流程通过 + 实机（用户 root 设备）完成一次真实禁用与恢复，全程有备份可回滚。

## M2：规则生态（依赖 M1）

- 规则订阅：OkHttp 拉取版本化规则源（官方源 = 我们的双仓之二），本地缓存与增量更新，支持自定义源 URL
- 双引擎切换：IFW / pm disable 用户可选，UI 呈现两引擎差异说明（app 可自恢复 vs 无感知）
- 安全等级细化：safeToBlock 三级展示、sideEffect 文案完善（数据来自冷启动快照的 source/confidence）
- 可选：Exodus ODbL 通道（独立订阅源形式接入，不并入主库——规避同源义务）
- 完工标准：换机/重装后仅凭订阅 URL 恢复全部规则

## M3：自动化与共建（依赖 M2）

- PACKAGE_ADDED/REPLACED 监听 → 后台重扫描 → 增量重应用（AdHammer 已实证可行；更新引入新组件的场景正好是价值点）
- 轻量恢复入口：adb 广播触发"清除全部 IFW 规则"（am broadcast 即可救砖）+ README 恢复教程
- ~~安全模式（N 次启动失败自动清规则）~~ → **2026-09-17 降级**：本项目只改第三方 app 不碰系统，bootloop 为 bug 类风险而非设计类风险，由写前 XML 校验 + 原子写入 + 强制备份覆盖（见 M1-R4），不再做启动计数机制
- ~~Magisk 模块模式~~ → **条件触发项**：仅当实机验收发现 OEM ROM 限制 app 直写 /data/system/ifw 时再做（AOSP 系模拟器直写+热加载已实证 OK）
- 规则库社区共建上线：双仓 Apache-2.0，PR 模板 + CI schema 校验 + 冲突检测（效仿 LCR/ETIP 流程）
- 完工标准：第三方 PR 能走完 CI 合入并被 app 订阅生效

## 分发与口径（贯穿）

- 双仓：app 仓 + 规则仓，均 Apache-2.0 + NOTICE（LCR/blocker-general-rules 继承署名）
- 渠道：GitHub Releases → IzzyOnDroid → F-Droid 渐进 + Codeberg/GitLab 镜像
- 对外口径：中性"SDK 组件审计/管理工具"；命名与素材无"Ad"字样（版权炮规避）

## 风险对照（详见调研报告 §5）

| 风险 | 缓解 | 落点 |
|---|---|---|
| 误禁系统组件 bootloop | 系统包白名单硬拦截（设计上只碰第三方 app，够不到开机链路）；写前 XML 校验 + 原子写入防坏文件；强制备份 + 一键恢复 + adb 广播清除 | R4（已实现）→ M3 补广播入口 |
| IFW 不拦隐式广播 | intent-filter 规则补 + pm 兜底 | R4 起步，M2 细化 |
| 版权炮 | 中性命名/口径/分发 | 贯穿 |
| 协议传染 | ODbL 隔离、GPL 零接触、source 溯源 | R2 起每阶段 |

## Status

- ✅ M1/M2/M3 complete and verified (rooted emulator, Android 16 baseline)
- ✅ v0.4–v0.6: i18n (zh default + en), batch polish, filter/sort & stats donut, settings regrouping, pull-to-refresh, optional backup model, live disable-state readout
- ✅ v0.7–v0.9: community pipeline (component report share), rule governance (1929 entities), list/detail rework (LibChecker-aligned), WorkMode architecture
- ✅ v0.10.0 (versionCode 15): phone-batch rule intake (2003 entities / 774 hits), LSPosed declarative groundwork (off by default; system_server query-layer engine mounted, launch-layer interception descoped by decision)
- ⏭️ Next: v0.11.0 (versionCode 16) — unmatched-card expand UX fix (auto-scroll-to-top), full-scope unmatched totals with truncation note, heuristic token matching, safety badges in library rows & archive sheet, provider type-label fix, plurals; 2003-rule bundled snapshot

> 本地维护文档，进度事实源另见 project_status.md 与持久记忆（本文件由仓库同步，Status 段对外可读）。
