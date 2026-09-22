<div align="center">

# Prunoid

**Android SDK 组件审计与禁用工具 —— pruner + paranoid + Android**

看清每一个 App 里藏着的 SDK，并由你决定哪些组件可以禁用

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)](#-兼容性)
[![Root](https://img.shields.io/badge/Requires-Root-orange.svg)](#-安装使用)
[![Release](https://img.shields.io/github/v/release/deserthouse/Prunoid?include_prereleases&color=yellow&style=flat-square)](https://github.com/deserthouse/Prunoid/releases)

[简体中文](README.md) · [English](README_EN.md)

</div>

---

> ⚠️ **本项目依然处于非常早期的阶段。** 在开始使用前，请确保你已完整阅读并理解本文档。本项目不对其未来做出任何承诺：可能大规模修改与重构，可能变更方向，也可能随时放弃或停止维护。

> **Prunoid** 是一个需要 root 的 Android 工具：扫描设备上每一个应用，识别其内嵌的广告 / 统计 / 推送等 SDK，然后通过系统级 Intent Firewall 选择性禁用对应组件——**不 hook 目标应用**，应用无感知、无法自行恢复。

## ✨ 功能特性

### 识别

- **三路匹配引擎**：应用包名前缀 + 组件全类名精确锚点 + 类名前缀，对 1,900+ 条 SDK 规则（内置快照，可订阅扩展）
- **四级安全分级**：可安全禁用 / 谨慎禁用 / 禁用有风险 / 影响未知——每条规则带来源与置信度，可溯源
- **SDK 档案卡**：品牌图标、中英描述、开发者团队、副作用说明、规则贡献者，一卡看全
- **未识别组件视图**：不在规则库中的组件按包名前缀聚类展示，启发式标注「疑似广告/统计」（仅提示，永不自动禁用）

### 禁用与恢复

| 引擎 | 原理 | 特点 |
|---|---|---|
| **IFW（意图防火墙，主引擎）** | 向 `/data/system/ifw/` 写入拦截规则 | 应用完全无感知、无法自行恢复；规则独立于 APK，跨更新生效 |
| **pm disable（辅引擎）** | 组件置为系统级停用 | 兼容性好；应用可检测并可能自行恢复；跨更新保留 |

- **逐 SDK 勾选 / 分类筛选（广告·推送·统计…）/ 全选筛选结果一键禁用**
- **自动重应用**：应用安装/更新后由前台守护服务增量重应用（新组件自动纳入；可在设置中关闭）
- **安全层（不可裁剪）**：每次操作前自动备份；单应用恢复 / 备份还原 / 一键清空 / adb 应急广播四条回滚通道；系统框架层白名单硬拦截（防 bootloop）

### 规则生态

- **多源订阅**：官方源 + 任意自定义源并发生效（同 id 冲突时置信度高者胜），支持自建规则仓库
- **SDK 库浏览**：全量规则「在机检出 / 未检出」两段浏览，看你装的应用命中了什么
- **社区共建**：规则仓独立 Apache-2.0 开源，PR + CI 校验，每条规则携带 `sources[]` 署名

## 📸 界面预览

<p float="left">
  <img src="docs/screenshots/list.png" width="270" alt="应用列表"/>
  <img src="docs/screenshots/archive_sheet.png" width="270" alt="SDK 档案卡"/>
  <img src="docs/screenshots/library.png" width="270" alt="SDK 库"/>
  <img src="docs/screenshots/settings.png" width="270" alt="设置"/>
  <img src="docs/screenshots/stats.png" width="270" alt="统计"/>
  <img src="docs/screenshots/about.png" width="270" alt="关于"/>
</p>

## 🚀 安装使用

**[📥 前往 Releases 下载最新版本](https://github.com/deserthouse/Prunoid/releases)**

### 环境要求

- Android 12+（API 31）
- 已 root（Magisk 等）；首次运行会请求 su 授权

### 步骤

1. 安装 Prunoid APK，授予通知权限（规则守护服务需要）
2. 授予 Magisk su 权限
3. 在应用列表点开任意应用 → 查看命中的 SDK（档案卡可看描述/副作用/贡献者）
4. 勾选要禁用的 SDK（默认勾选「可安全禁用/谨慎禁用」）→ 应用规则
5. 出问题？应用内「应急恢复」可还原备份；UI 无法进入时用 adb 广播清空全部规则

## ❓ 常见问题

**禁用后应用会崩溃吗？**
可能。规则自带四级安全分级与副作用说明；默认只勾选「可安全禁用/谨慎禁用」级，「禁用有风险」需显式加选。任何操作前都有自动备份，可随时恢复。

**会被应用检测到吗？**
IFW 引擎不会——拦截发生在系统框架层，应用无法感知也无法自行恢复。pm disable 引擎则可能被应用检测并恢复。

**更新应用后规则还在吗？**
在。IFW 规则文件独立于 APK；且规则守护服务会在更新后自动重应用并把新引入的组件增量纳入。

**和 Blocker / Thanox 是什么关系？**
Prunoid 的规则冷启动合并了 blocker-general-rules（Apache-2.0）与 LibChecker-Rules 组件锚点，图标与描述资产来自 LibChecker-Rules-Bundle（Apache-2.0，见 NOTICE 署名）；IFW 规则格式与 Blocker 实测对照修正。相互独立，无隶属关系。

**会联网吗？上传数据吗？**
仅在你主动订阅/刷新规则源时访问对应 URL；无遥测、无崩溃上报、不上传任何数据。

## 📊 兼容性

| 项目 | 支持情况 |
|---|---|
| 最低版本 | Android 12 (API 31) |
| 目标版本 | Android 17 (API 37) |
| 已验证 | Android 16 (API 36) 端到端（root 模拟器，含 6 个真实国产应用样本集） |
| root 方案 | Magisk 已实测；理论支持其他能写 `/data/system/ifw/` 的方案 |
| 非 root（Shizuku/ADB） | 不支持——shell 身份无法修改普通应用组件状态（AOSP 限制），无绕行 |

## 🛠️ 工作原理

```
┌────────────┐   扫描（PackageManager 枚举四类组件）
│  Prunoid   │ ──────────────▶ 三路规则匹配 ──▶ SDK 识别报告
│  (root)    │
│            │   应用规则（先自动备份）
│  IFW 主引擎 │ ──────────────▶ /data/system/ifw/<pkg>.xml（按组件类型分组）
│  pm 辅引擎  │ ──────────────▶ pm disable <pkg>/<component>
│            │
│  规则守护   │ ◀── PACKAGE_ADDED/REPLACED ── 应用更新后增量重应用
└────────────┘
```

- **只改第三方应用组件，不碰系统组件**——白名单硬拦截兜底，不存在内生 bootloop 路径
- IFW 不拦隐式广播的场景由组件类型分组规则 + pm 引擎互补覆盖

## 🤝 致谢

- **[LibChecker / LibChecker-Rules / LibChecker-Rules-Bundle](https://github.com/LibChecker)** —— 组件识别锚点、SDK 品牌图标与中英描述资产（Apache-2.0），本项目规则库与档案卡的地基
- **[lihenggui / blocker-general-rules](https://github.com/lihenggui/blocker-general-rules)** —— 467 条禁用规则与 safeToBlock/sideEffect 标注（Apache-2.0）
- **[topjohnwu / libsu](https://github.com/topjohnwu/libsu)** —— root shell（Apache-2.0）

## 🔐 权限说明（QUERY_ALL_PACKAGES 豁免声明 / Permission Disclosure）

Prunoid 申请 `QUERY_ALL_PACKAGES`（查询全部应用）权限。**该权限仅用于在应用列表中枚举设备上已安装的应用并扫描其组件清单**——SDK 组件不限于带桌面图标的应用。Prunoid 不收集、不上传任何数据，无遥测；网络访问仅限用户主动订阅规则源。

## ⚠️ 免责声明 / Disclaimer

> 本项目为**兴趣使然的个人作品**，按「现状」提供，不含任何明示或默示的担保。
>
> - **功能不保证**：不保证任何功能在您的设备、ROM 或系统版本上正常运行；定制 ROM 的私有行为可能造成差异。
> - **无开发承诺**：不对后续开发计划、排期或是否继续维护作任何承诺。
> - **风险自担**：禁用组件可能影响应用功能（推送丢失、登录异常等）；使用产生的任何后果由使用者自行承担；启用 root 本身存在风险，请自行评估。
> - **无关联声明**：本项目与 Google、Android 及文中提及的任何厂商/项目无隶属关系；各商标归其各自所有者所有。
> - **用途自决**：项目用途由使用者自行决定，开发者不对任何滥用行为负责。

完整版见 [DISCLAIMER.md](DISCLAIMER.md)（中英双语）。

## 🤖 AI 使用声明 / AI Disclosure

> 本项目由 AI（大语言模型）深度参与开发——包括架构设计、代码实现、测试与文档；人类（[@deserthouse](https://github.com/deserthouse)）提出需求、进行验收并拥有最终决策权。

## ⚖️ 开源协议

本项目采用 [Apache-2.0](LICENSE) 协议开源。

- 规则库在独立仓库 [Prunoid-Rules](https://github.com/deserthouse/Prunoid-Rules)（Apache-2.0），每条规则携带 `sources[]` 来源署名
- SDK 图标与描述资产来自 LibChecker-Rules-Bundle（Apache-2.0），署名见 [NOTICE](NOTICE)

---

<div align="center">

如果这个项目对你有帮助，欢迎 ⭐ Star 支持

</div>
