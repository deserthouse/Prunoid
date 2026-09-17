# SDK-Pruner（原 SDK-Slayer）项目状态

> 最后更新：2026-09-17 夜间（M2 双引擎+订阅、M3 自动重应用全部验证通过）｜ 阶段：M2 基本完成，M3 核心已通，余项=官方订阅源上线+共建流程

## 已实现功能（M1 全部 + M2 双引擎/订阅 + M3 自动重应用，2026-09-17）

- 扫描器：PackageManager 枚举第三方 app 四类组件，规则前缀 + 组件锚点双路匹配，安全分级
- 禁用引擎：**双引擎**——IFW 主（`<activity block="true">` 正确格式，Blocker 对照修正）+ pm disable 辅，详情页切换；恢复=IFW 移除+pm 重启用
- **规则订阅**：自定义源 URL（快照同 schema），OkHttp 拉取 + 缓存留痕（url/fetchedAt）+ 同 id 覆盖合并 + 自动重扫；本地 HTTP + adb reverse 实测通过
- **自动重应用**：AppliedRulesStore 持久化 + RuleGuardService 前台守护（动态注册 receiver，绕过 A15+ cached 进程广播跳过策略）+ PACKAGE_REPLACED 增量重应用（新组件纳入、已卸载剔除）；升级实测 3→4 组件
- 安全层：自动备份 / 一键恢复 / 一键清 IFW / 系统包白名单 / 写前校验原子写入
- StateFlow 竞态修复（update{} 原子更新）
- UI：M3E 列表屏（订阅状态行、SDK 徽标、root 提示）+ 详情屏（双引擎切换、SDK 分组、安全等级、命中组件、应用/恢复）
- 内置规则快照 1929 条 + 冷启动脚本 build_snapshot.py
- 验证记录：IFW A/B（102 vs 0）；PM A/B（Error type 3 ↔ 0）；重装规则保留；订阅双模拟器通过；升级自动重应用 3→4 组件
- 安全层：操作前自动备份（tar.gz）/ 一键恢复（IFW 移除 + pm 重启用）/ 一键清除全部 IFW / 系统包白名单硬拦截 / 写前校验与原子写入
- 验证记录：IFW 拦截 A/B（有规则 result=102 无进程，无规则 result=0 启动）；PM 禁用 A/B（Error type 3 ↔ result=0）；app 重装后 IFW 规则保留
- UI：M3E 列表屏（SDK 徽标、系统 app 只读开关、root 状态提示）+ 详情屏（双引擎切换、SDK 分组、安全等级、命中组件、应用/恢复）
- 内置规则快照：snapshot.json 1929 个 SDK 实体（blocker-general-rules 468 + LCR 组件锚点 + Fuck.AD 16 + oF2pks 619，逐条带 source/confidence）
- 冷启动脚本：research/build_snapshot.py（可重复执行刷新快照）
> 迁移交接：见 `HANDOVER.md`（完整文件索引 + 接管指引）；打包件 `archive/SDK-Pruner_migration_20260917.zip`（`archive/make_migration_zip.py` 按当日刷新）

## 项目定位

基于 root 权限的 Android SDK 组件禁用工具：识别 app 内广告/统计/推送等 SDK → 利用 IFW / pm disable 禁用其组件（不 hook 目标 app，规避反 hook 检测）→ 安装/更新/重装后自动重应用规则（进阶）。

## 拍板记录（2026-09-16）

- **① 立项确定**
- **③ 目标范围**：minSdk 31（Android 12）～ compileSdk/targetSdk 37（Android 17），对齐隔壁 OptIcon；完整覆盖 Material You 世代，**全套 Material 3 Expressive**，不考虑向下兼容；仅第三方 app，不碰系统 app
- **④ 技术栈**（AI 全权决定，对齐 OptIcon 工具链 `C:\Users\deser\Projects\OptIcon`）：
  - 构建：Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / Java 21 / configuration-cache / nonTransitiveRClass
  - UI：Compose BOM（2025.06.00 系）+ material3 1.5.x（M3E API）+ material-icons-extended + navigation-compose + activity-compose
  - Root：libsu（Apache-2.0，topjohnwu）
  - 数据：kotlinx-serialization-json（规则 schema）、OkHttp（规则订阅下载）、Coil（app 图标）、DataStore（偏好）
  - 结构：单 :app 模块起步（对齐 OptIcon；NiA 式模块化推迟到有必要时）、无 DI 框架
  - 命名空间：`io.github.deserthouse.sdkpruner`（applicationId 同）
  - 扫描：PackageManager API（GET_ACTIVITIES/SERVICES/RECEIVERS/PROVIDERS），零第三方 APK 解析依赖
- **② 命名/分发（2026-09-17 用户确认）**：定名 **SDK-Pruner**（文件夹已于同日更名）；双仓（app/规则库）Apache-2.0；GitHub Releases → IzzyOnDroid → F-Droid 渐进 + 镜像备份；对外口径 = 中性"SDK 组件审计/管理工具"，用途由用户自决

## 核心架构决策（调研结论，随 2026-09-16 立项生效）

- 禁用引擎：**IFW 为主**（app 无感知、无法自恢复、Android 16 仍有效）+ **pm disable 为辅**（兼容性/管理便利）
- 数据来源：自建规则仓库（拟 Apache-2.0），冷启动合并多源：LibChecker-Rules 骨架 / **blocker-general-rules（Apache-2.0 正源，Thanox 468 条的上游）** / Exodus API(ODbL 隔离区,432条已拉取) / oF2pks 索引(事实参考,618条) / Thanox 提取物(降级为交叉验证) / Fuck.AD hook 类名(16 家)；AdClose 经逆向确认无内置数据库，已除名；数据原则见 `research/2026-09-17_数据源合规与建库原则.md`
- 规则 Schema：采用 Thanox general.json 实践范本扩展（name/company/searchKeyword/safeToBlock/sideEffect/contributors + 白名单/置信度字段）
- 技术栈草案：Kotlin + Compose + Material 3 + libsu，架构参考 Blocker（Now in Android 模块化）
- Shizuku 结论：仅 root 启动的 Shizuku 可用；ADB 模式无法禁普通 app 组件（AOSP 限制）

## 已实现功能

- 无（尚未开发）

## 当前文件拓扑

```
SDK-Pruner/
├── HANDOVER.md                ← 迁移交接文档（四轮调研结论汇总 + 文件索引 + 新会话接管指引）
├── project_status.md          ← 本文件
├── records/
│   ├── 对话纪要.md                     ← 对话纪要（2026-09-13 起持续追加，八次会话，决策语境）
│   ├── workbuddy_memory_2026-09-13.md ← WorkBuddy 工作区记忆日志副本
│   ├── workbuddy_memory_2026-09-16.md
│   └── zcode_memory_2026-09-17.md     ← ZCode 持久记忆副本（新会话可据此恢复约定）
├── research/
│   ├── 2026-09-13_立项调研报告.md       ← 四轮调研全量报告（含增补一/二/三）
│   ├── 2026-09-17_数据源合规与建库原则.md ← 逐源协议判定 + 五条核心原则 + 建库决策（待批准）
│   ├── 2026-09-17_AdClose拆解记录.md   ← AdClose 4.3.2 逆向结论（无内置规则库，已除名冷启动源）
│   ├── adclose_dex_strings.txt         ← AdClose dex 字符串提取产物
│   ├── extract_adclose_strings.py      ← dex 字符串提取脚本（可复用）
│   ├── exodus_odbl/                    ← Exodus API 全量数据（432 tracker，ODbL 隔离区 + PROVENANCE）
│   ├── of2pks_toolbox/                 ← oF2pks 补充索引 618 条（无协议声明，仅事实参考）
│   ├── thanox_blocker_rules_zh_CN.json ← Thanox 468 条禁用规则完整提取（含 safeToBlock 标注）
│   ├── thanox_blocker_rules_names.txt  ← 上述规则的名称索引
│   ├── thanox_lcrules_full.txt         ← Thanox 内置 LibChecker 系 2204 条组件识别规则完整导出
│   ├── coverage_gap_thanox_blocker_rules.json ← 468 条与 LCR v44 逐条覆盖对照
│   └── lcr_repo/                       ← LibChecker-Rules v44 全库本地副本（git clone）
├── reference_apk/             ← 调研样本（6 APK，见 HANDOVER.md 索引）
└── archive/
    ├── SDK-Pruner_migration_<当日>.zip  ← 完工快照打包件（make_migration_zip.py 按日期生成，仅保留最新一份）
    └── make_migration_zip.py             ← 打包脚本（可重复执行刷新打包件）
```

## TODO

| # | 事项 | 状态 | 依赖 |
|---|---|---|---|
| 1 | 用户拍板立项（报告 §7 四项决策） | ✅ 完成（② 2026-09-17 定名 SDK-Pruner） | — |
| 2 | M1 开发：扫描器 + IFW 禁用引擎 + 内置规则快照 | ⏸️ | #1 |
| 3 | 规则仓库冷启动脚本（合并 5 个数据源） | ⏸️ | #1 |
| 4 | M2：规则订阅 + 双引擎切换 | ⏸️ | #2 |
| 5 | M3：安装/更新自动重应用 + Magisk 模块模式 | ⏸️ | #2 |

## 全局配置/约定

- 项目原则：用户提需求验收，AI 全权负责代码；先调研后动手；版本隔离备份（backup/ 或 _vN 文件名）；删除文件须用户授权
- 关键风险记忆：IFW 不拦隐式广播（需 intent-filter 规则）；系统组件误禁会 bootloop（需白名单+恢复机制）；不复制 GPL/AGPL 项目的代码与数据库文件
