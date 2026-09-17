# SDK-Pruner（原 SDK-Slayer）迁移交接文档（HANDOVER）

> 生成时间：2026-09-16（最后更新 2026-09-17）｜ 用途：项目迁移 / 新会话无缝接管 / 第三方 AI 审计
> 配套文件：`project_status.md`（当前状态）、`research/2026-09-13_立项调研报告.md`（调研全量报告）

## 1. 一句话项目定义

**SDK-Pruner**（原代号 SDK-Slayer，2026-09-17 定名）：基于 root 权限的 Android SDK 组件禁用工具——识别 app 内广告/统计/推送 SDK → 用 IFW（意图防火墙，主）+ pm disable（辅）禁用其组件（不 hook 目标 app，规避反 hook 检测）→ 安装/更新/重装后自动重应用规则（进阶功能）。配套自建开源社区共建 SDK 知识库（拟 Apache-2.0）。**当前阶段：四轮立项调研完成，等待用户拍板，尚未开发。**

## 2. 四轮调研核心结论（已全部验证，含量化数据）

### 第一轮：立项调研（2026-09-13）
- **技术路线**：IFW 为主（app 无感知/无法自恢复，Android 16 实测仍有效；已知缺陷：不拦隐式广播，需 intent-filter 规则补）+ pm disable 为辅（跨 app 更新保留，但 app 可运行时自行恢复）；Shizuku 仅 root 启动时可用（ADB 模式禁不了普通 app 组件，AOSP 限制）
- **误禁系统组件会 bootloop** → 必须系统白名单 + 一键恢复 + 备份机制
- 本地 4 个 AdHammer 全家桶 APK 逆向：作者 ExtStars 的 dex 确实有 pm disable + PACKAGE_ADDED/REPLACED 链路（机制可行性实证），但无 SDK 数据库（规则走云端 Gist，闭源无共建）

### 第二轮：Thanox / Fuck.AD 深挖（2026-09-13）
- **Thanox v8.6 资料来源挖清**：①468 条组件禁用规则（418 条 Exodus ETIP 程序转化 + 50 条社区，仅 39 条 safeToBlock）——**2026-09-17 溯源修正：其独立上游为 lihenggui/blocker-general-rules（Apache-2.0），Thanox 只是下游消费者，冷启动以该仓库为正源**②2204 条 LibChecker v40 系组件识别规则（SQLite，含国内 SDK 中文标注）
- **Fuck.AD v3.0.6**（com.hujiayucc.hook）：纯 LSPosed，云端源 fkad.hujiayucc.cn，dex 提取 16 家广告 SDK hook 类名
- 结论：知识库生态位空着（安全标注覆盖率 8% 且闭源锁死）；冷启动数据源 7 个，首版知识库预计 300+ SDK

### 第三轮：独立 SDK 库盘点（2026-09-13）
- 独立数据库多家但维度互斥，LibChecker 谁也没完整收录：Exodus ETIP（类签名级，603 tracker，AGPL）、DuckDuckGo tracker-blocklists（域名级，**CC BY-NC-SA 禁商用**）、Disconnect/X-Ray（域名级）、oF2pks/3xodusprivacy-toolbox（Exodus 漏收补充 31 条，Apache-2.0，已停滞）、AppManager 签名库（内嵌 GPL）
- "组件级+开源+活跃共建"仅 LibChecker-Rules 一家；**跨库合并层完全空白 = 本项目规则库的差异化立足点**

### 第四轮：覆盖度实测（2026-09-13，程序化比对）
- 克隆 LibChecker-Rules v44 全库（2673 条）比对：
  - Thanox 468 条禁用规则 → LCR 覆盖**仅 6%**（419 条零覆盖：欧美追踪 SDK + 国产推送联盟 极光/小米/魅族/vivo/OPPO + B站/京东/百度广告）
  - Thanox 识别库 2204 条 → LCR v44 覆盖 **95.8%**（它就是 LCR v40 旧快照，直接用上游 v44）
  - Fuck.AD 14 家广告 SDK → LCR 组件级 **8/14**（TradPlus/Sigmob/Mintegral/AppLovin/趣盟/风车零收录）
- 结论：规则库不能只靠 LCR 冷启动——**骨架用 LCR（Apache-2.0 可继承），血肉靠 Exodus + Fuck.AD 类名 + 真实 APK 提取**

## 3. 架构决策（2026-09-16~17 拍板：①②③④ 全部确定，立项闭环）

**拍板记录（2026-09-16，本会话）**：
- ① 立项确定
- ③ 目标范围：**minSdk 31（Android 12）～ compileSdk/targetSdk 37（Android 17），对齐隔壁 OptIcon**，完整覆盖 Material You 世代，**全套 Material 3 Expressive 设计语言，不考虑向下兼容**；仅第三方 app，不碰系统 app
- ④ 技术栈（AI 全权决定，对齐 OptIcon 工具链）：Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / Java 21；Compose BOM + material3 1.5.x（M3E）+ material-icons-extended + navigation-compose；**libsu**（root shell）；kotlinx-serialization-json（规则 schema）+ OkHttp（规则订阅）+ Coil（图标）+ DataStore（偏好）；无 DI 框架、单 :app 模块起步；命名空间 `io.github.deserthouse.sdkpruner`；扫描走 PackageManager API 零第三方解析依赖

- 禁用引擎：IFW 主 + pm disable 辅（libsu/root shell）
- 规则 Schema：照 Thanox general.json 范本扩展（name/company/searchKeyword/safeToBlock/sideEffect/contributors + 白名单/置信度字段）
- 技术栈：Kotlin + Compose + Material 3 + libsu，架构参考 Blocker（Now in Android 模块化）
- 分期：M1 扫描+手动禁用+内置规则快照 → M2 规则订阅+双引擎 → M3 安装/更新自动重应用+Magisk 模块模式
- 分发策略：考虑版权炮风险（MyAndroidTools/AdHammer 先例），避免"Ad"命名与素材，GitHub+F-Droid 路线

## 4. 完整文件索引

| 路径 | 内容 | 来源 |
|---|---|---|
| `project_status.md` | 当前项目状态（架构决策/TODO/拓扑） | 本项目维护 |
| `HANDOVER.md` | 本交接文档 | 本项目维护 |
| `records/对话纪要.md` | 对话纪要（2026-09-13 起持续追加；WorkBuddy 六会话 + ZCode 两会话，用户指令→执行→产出，含决策语境） | 本项目维护 |
| `records/workbuddy_memory_2026-09-13.md` / `_09-16.md` | WorkBuddy 工作区记忆日志副本（逐日工作记录） | 本项目维护 |
| `records/zcode_memory_2026-09-17.md` | ZCode 持久记忆副本（OptIcon 对齐约定、更名与口径；文件夹更名后新会话可据此恢复记忆） | 本项目维护 |
| `research/2026-09-13_立项调研报告.md` | 四轮调研全量报告（含增补一/二/三章节、竞品矩阵、风险矩阵、协议红线、来源清单） | 本项目产出 |
| `research/2026-09-17_数据源合规与建库原则.md` | 逐源协议合规判定 + 五条核心原则 + 自建库决策与形态（含 Exodus ODbL、AdClose 无协议两处查实） | 本项目产出 |
| `research/2026-09-17_AdClose拆解记录.md` | AdClose 4.3.2 APK 逆向结论（无内置规则库，从冷启动源除名） | 本项目产出 |
| `research/2026-09-17_Blocker拆解记录.md` | Blocker APK+源码+规则仓拆解：**Thanox 468 条的正源就是 blocker-general-rules(Apache-2.0)**，含 875 条框架组件知识 | 本项目产出 |
| `research/exodus_odbl/` | Exodus API 全量 432 tracker（2026-09-17 拉取）；ODbL 隔离区，不并入 Apache-2.0 主体 | reports.exodus-privacy.eu.org |
| `research/blocker_rules_repo/` | lihenggui/blocker-general-rules 本地副本（Apache-2.0，468 SDK 规则 + 875 框架组件知识） | github.com/lihenggui/blocker-general-rules |
| `research/blocker_src/` | lihenggui/blocker 源码副本（MIT，M1 参考实现：core/ifw-api 等） | github.com/lihenggui/blocker |
| `research/of2pks_toolbox/` | oF2pks 补充索引 618 条（name/package/url 事实）；无协议声明，仅事实参考 | gitlab.com/oF2pks/3xodusprivacy-toolbox |
| `reference_apk/AdClose_4.3.2.apk` | AdClose 调研样本（2026-09-17 增补，共 7 APK） | Xposed-Modules-Repo 官方发布仓 |
| `research/thanox_blocker_rules_zh_CN.json` | Thanox 468 条禁用规则完整提取（含 safeToBlock/贡献者） | 从 thanox_8.6-prc.apk 提取 |
| `research/thanox_lcrules_full.txt` | Thanox 内置识别库 2204 条完整导出（TSV：名称/标注/类型/正则） | 从 thanox_8.6-prc.apk 提取 |
| `research/thanox_blocker_rules_names.txt` | 468 条规则的名称索引（SAFE/RISK 标注） | 本项目产出 |
| `research/coverage_gap_thanox_blocker_rules.json` | Thanox 468 条与 LCR v44 逐条对照（覆盖度缺口数据） | 第四轮比对产出 |
| `research/lcr_repo/` | LibChecker-Rules v44 全库本地副本（git clone，勿提交其 .git 到任何仓库） | github.com/LibChecker/LibChecker-Rules |
| `reference_apk/*.apk`（6 个） | 调研样本：AdHammer 全家桶 4 个（ExtStars 闭源）+ thanox_8.6-prc + FuckAD_3.0.6 | 用户下载/GitHub Releases |
| `archive/` | 归档区：迁移打包件 zip + make_migration_zip.py 打包脚本（2026-09-16 已清理早期嵌套旧 zip） | 本项目维护 |

## 5. 关键风险与红线（新会话必读）

1. **协议**：不复制 GPL/AGPL 项目的代码与数据库文件（AppManager/Exodus 平台代码）；DDG 名单 CC BY-NC-SA 禁商用只可学习思路；可直接用的白名单源 = LibChecker-Rules（Apache-2.0）、oF2pks 补充索引（Apache-2.0）；从 Thanox/Fuck.AD APK 提取的组件名/包名为客观事实数据，可重建但要自建 schema
2. **安全**：误禁系统组件致 bootloop（白名单+恢复机制必备）；IFW 隐式广播缺陷用 intent-filter 规则补
3. **法律/平台**：版权炮风险（谷歌/腾讯先例），命名与分发需低调
4. **工作原则**：用户提需求验收、AI 全权负责代码；先调研后动手；删除文件须用户授权；阶段性输出完工快照文本

## 6. 当前 TODO（迁移后从 #1 继续）

| # | 事项 | 状态 |
|---|---|---|
| 1 | 用户拍板立项（命名/分发策略/目标系统范围/技术栈） | ✅ 完成（①③④ 2026-09-16；② 2026-09-17 定名 SDK-Pruner） |
| 2 | M1 开发：扫描器 + IFW 禁用引擎 + 内置规则快照 + 备份/恢复 | ⏸️ |
| 3 | 规则库冷启动脚本：LCR 骨架 + Exodus API + Fuck.AD 类名 + 实机 APK 提取合并去重 | ⏸️ |
| 4 | M2：规则订阅更新 + 双引擎切换 + 安全等级细化 | ⏸️ |
| 5 | M3：PACKAGE_ADDED/REPLACED 自动重应用 + Magisk 模块模式 + 社区共建流程上线 | ⏸️ |

## 7. 迁移指引

- 整个项目文件夹即完整项目现场，全部记录与调研成果均在其内，可整体拷贝迁移。**2026-09-17 文件夹由用户更名为 SDK-Pruner**（旧记录中的 `C:\Users\deser\Projects\SDK-Slayer` 路径同指现文件夹）
- 打包件：`archive/SDK-Pruner_migration_<当日>.zip`（make_migration_zip.py 按当天日期生成，已排除 lcr_repo/.git 与 archive 本身）
- 新会话接管：将本文件 + `project_status.md` + `records/对话纪要.md` + 调研报告投喂即可零信息衰减恢复上下文
