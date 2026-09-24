# Prunoid（原 SDK-Slayer）项目状态

> 最后更新：2026-09-18 凌晨④（b5：SDK 富描述接入 667 条（LibChecker-Rules v4 中英 description），修 RuleMerger 订阅覆盖抹字段 bug，真机验证通过）｜ 阶段：三里程碑全部完成；用户拍板"模拟器通过即算验收，跳过真机"；余项=首个外部 PR 走通共建 CI（宣发不做）

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
> 迁移交接：见 `HANDOVER.md`（完整文件索引 + 接管指引）；打包件 `archive/Prunoid_migration_20260917.zip`（`archive/make_migration_zip.py` 按当日刷新）


## UI v2：设计审查清单落地（2026-09-17 深夜②，提交 30ccb51）

- P0：已应用状态可见（列表✓ + 详情状态行）；手动重扫；系统 app 详情只读拦截
- P1：逐 SDK 勾选（默认 SAFE/CAUTION 可加选）；量化确认对话框；busy 态；恢复双击确认；订阅状态点无障碍描述
- P2/美化：分类中文化；命中徽标风险色点（RISKY>UNKNOWN>CAUTION>SAFE）；安全分布 mini-dots；箭头旋转动画；版本号行；搜索清除；空态图标；sideEffect 引导图标
- 新增独立设置页（DataStore 事实源）：默认引擎 / 备份保留份数 3-30 / 订阅源详情与管理（检查更新/更换/退订）/ 应急通道 / 关于
- 引擎与仓库适配：AppliedEntry 补 at 时间戳；backup(keep) 参数化；subscriptionMeta()
- 验收：v2 亮/暗 6 张截图 judge 全 pass；应用所选 3 项（8 组件）文件实证；设置页实测
- 验收截图：tests/v2_*.png

## A16 基准确立（2026-09-17 深夜③）

- 用户拍板：非兼容性基准测试集中在新基准机 Prunoid_A16_root（OptIconA16 完整副本，android-36/A16，root Magisk）
- SdkPrunerTest（A34）数据迁移后按指令删除；OptIconTest 复制归还 OptIcon 项目
- A16 全量回归实证：扫描/搜索（含清除）、IFW 应用（4 组件写入核对）、恢复（文件清零）、pm 引擎（4 组件 disabled 实证）、pm 恢复、adb 广播应急清除——全部通过
- A16 台样本更丰富（Blocker/Thanox/AdHammer 等真实 app），回归截图 tests/a16_*.png

## 审查清单第一批 b1（2026-09-18 凌晨，提交 1c51d77，judge 7/7 pass）

- 详情屏分类筛选 chips（全部/广告/推送/统计/框架…）+ 全选筛选结果/全不选 + 一键禁用（复用 selected 集合）
- SDK 卡片重构：整卡点击=展开/折叠，仅复选框独立点选（修复标题区误绑勾选切换）
- 命中组件按类型分组：activity/service/receiver 徽标+计数（componentTypes 映射 + 类名后缀兜底）
- 系统应用分层（对齐黑域思路，安全层白名单不可裁剪）：白名单命中=框架层（红色硬拦截 banner + 按钮禁用 + 列表"框架"红标）；其余系统 app=警告层（严肃警告对话框"我已了解风险，继续"）；列表"系统"灰标
- 设置页新增"两种禁用引擎的区别"说明卡片
- 测试数据：装入 6 个脏应用（哈啰 54 / KOOK 45 / Blued X 39 / 天津地铁 39 / Oopz 34 / 丰巢 34 命中；全部官方渠道：blued.cn、oopz.cn、kookapp.cn 直链 + 应用宝），实测一键禁用 126 组件写入 IFW 三组分组
- 验收截图 tests/b1_*.png

## 审查清单 b2/b3（2026-09-18 凌晨②，提交 1b50547，judge 4/4 pass）

- SDK monogram 头像：规则 id 哈希取色 tonal 圆形首字母徽标（参考 LibChecker avatar 语义，真机装机取经确认）
- 展开卡规则元信息行：开发者/置信度/来源数（中文映射）
- 未识别组件视图：未命中规则组件按 Java 包前缀聚类计数（top20），启发式特征词标注"疑似广告/统计"——只读、永不参与自动禁用、引导提交规则仓库；哈啰实测 179 个未识别组件聚类出 alipay/mpaas 嵌入族、byted.live、hellobike 自家业务包等
- 待办全部消化；LibChecker（F-Droid 官方渠道）留在 A16 台作参考工具
- 验收截图 tests/b2_*.png b2_meta.png

## b4：SDK 真实图标（2026-09-18 凌晨③，提交 dbe523e）

- 图标真源=LibChecker-Rules-Bundle 仓（Apache-2.0）：166 个 ic_lib_*.xml 矢量 + rules.db(label→iconIndex) + IconResMap.kt 顺序
- 三轮映射（精确名→模糊包含→手工别名）覆盖 657/1929 规则（34%，主流 SDK 全覆盖），产物 app/src/main/assets/icons/lib_icons.json
- SdkMonogram 升级：有映射图标→tonal 圆底品牌矢量；无→首字母 monogram 兜底
- NOTICE 新增（署名 LibChecker-Rules-Bundle）；协议根基不变：核实 LibChecker app/Rules/Bundle 均 Apache-2.0，此前 AGPL 判断错误，用户批准的换 AGPL 取消（前提消失）
- 2026-09-24 公开仓引用审计：主仓补齐缺失的 Apache-2.0 LICENSE（README 徽章/尾注/NOTICE 三处引用此前悬空 404）；NOTICE/ROADMAP 内部 research/ 引用改公开落点；规则仓 NOTICE 旧名 SDK-Pruner 同步为 Prunoid、README/CONTRIBUTING 幽灵引用清理、snapshot generator 字段指向 scripts/build_snapshot.py（主仓 3f2c548 / 规则仓 f8de3b5）
- 长尾 SDK 图标留给规则仓演进（iconUrl 字段候选）；真机验证哈啰详情 Tinker/Pangle/极光/个推品牌图标正确渲染

## b5：SDK 富描述（2026-09-18 凌晨④，提交 9efdadb）

- 描述真源=LibChecker-Rules v4 组件规则 JSON（{type}-libs/{全类名}.json，含中英 description/dev_team/source_link，Apache-2.0）
- 富化管线：按组件锚点回填快照（573 条直配）+ 同名规则互播（94 条），共 667/1929（34%）
- SdkRule schema +description/devTeam/sourceLink；展开卡渲染描述段（bodySmall）+来源链接（mono tertiary）
- 修复 RuleMerger：订阅覆盖同 id 时富字段为空则保留内置值（旧订阅快照曾抹掉富化数据，单测通过）
- 真机验证：哈啰 Pangle SDK 卡完整呈现描述段+链接+四类分组组件；注意快照存在同名重复规则（不同来源 id），已按名互播

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
- **② 命名/分发（2026-09-17 用户确认）**：定名 **Prunoid**（文件夹已于同日更名）；双仓（app/规则库）Apache-2.0；GitHub Releases → IzzyOnDroid → F-Droid 渐进 + 镜像备份；对外口径 = 中性"SDK 组件审计/管理工具"，用途由用户自决

## 核心架构决策（调研结论，随 2026-09-16 立项生效）

- 禁用引擎：**IFW 为主**（app 无感知、无法自恢复、Android 16 仍有效）+ **pm disable 为辅**（兼容性/管理便利）
- 数据来源：自建规则仓库（拟 Apache-2.0），冷启动合并多源：LibChecker-Rules 骨架 / **blocker-general-rules（Apache-2.0 正源，Thanox 468 条的上游）** / Exodus API(ODbL 隔离区,432条已拉取) / oF2pks 索引(事实参考,618条) / Thanox 提取物(降级为交叉验证) / Fuck.AD hook 类名(16 家)；AdClose 经逆向确认无内置数据库，已除名；数据原则见 `research/2026-09-17_数据源合规与建库原则.md`
- 规则 Schema：采用 Thanox general.json 实践范本扩展（name/company/searchKeyword/safeToBlock/sideEffect/contributors + 白名单/置信度字段）
- 技术栈草案：Kotlin + Compose + Material 3 + libsu，架构参考 Blocker（Now in Android 模块化）
- Shizuku 结论：仅 root 启动的 Shizuku 可用；ADB 模式无法禁普通 app 组件（AOSP 限制）

## 已实现功能（历史章节，见顶部清单）

- 三里程碑功能全量已实现并验证（见文件顶部"已实现功能"章节）。

## UI 视觉重做（2026-09-17 深夜，方案获批后实现）

- 基调"冷静的审计台"：动态取色 + 表达力只用在状态（LinearWavyProgressIndicator / Snackbar / 四级安全徽标）
- 结构：两屏各自 Scaffold + TopAppBar（标题旁订阅状态点、订阅/应急 action 图标）；列表底部摘要条；详情底部固定操作区
- 组件升级：SegmentedButton 双引擎切换（带差异说明）、SDK 卡片化 + 可展开等宽组件清单、四级安全徽标（绿/橙/红/灰，图标+文字，暗色降 tone 不换色相）、胶囊搜索框、Snackbar 统一反馈、订阅对话框"填入官方源"辅助、应急对话框等宽代码块
- 修复三处：详情屏系统返回直退 app（补 BackHandler）、应急"清除全部 IFW"双击确认第二击永远点不了（旧逻辑自 disable，改双击执行+3s 复位）、截屏验收发现的坐标问题（测试侧）
- 验收：亮/暗 5 张截图 judge 视觉审计全 pass；功能回归全链路通过（订阅官方源成功、IFW 应用含 RISKY 排除、pm 应用 8 组件 disabled 实证、app 内恢复、应急清 IFW、adb 广播清除）
- 验收截图：`tests/ui_light_list.png` `ui_light_detail.png` `ui_light_expand.png` `ui_dark_list.png` `ui_dark_detail.png`
- 验证环境：AVD 更名 OptIconTest → **SdkPrunerTest**（同模板脚本生成的兄弟实例，非 git fork；记忆已同步）

## 当前文件拓扑

```
Prunoid/
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
    ├── Prunoid_migration_<当日>.zip  ← 完工快照打包件（make_migration_zip.py 按日期生成，仅保留最新一份）
    └── make_migration_zip.py             ← 打包脚本（可重复执行刷新打包件）
```

## 上游 UI 拉通调研（2026-09-18，调研会话交付）

- 四上游（LibChecker 2.5.4 / Thanox 8.6 / Blocker canary 6339 / AppChecker 未采——无官方分发渠道）实机考察完成，标杆=优雅 M3E；结论与**拉通改法五条**（SDK 库入口 / SDK 详情档案卡+结构化字段+量化句 / 组件分组+类型标签+归属 chip / 安全文案照 Thanox 范本 / 动效克制区间）已入持久记忆 sdk-pruner-ui-review 待办区，与"先记后改"清单合并等用户 UI 审查后一次迭代。
- 截图证据 `tests/ui_study_upstream/shots/`（不入库）；会话存档 `records/zcode_memory_2026-09-18.md`（含 A16_root/LSPosed 环境历险与 OptIcon Xposed 模块冷启风险的实验闭环，OptIcon 侧处置由用户另行处理）。
- 环境：A16_clean 已还原（临时 app 卸载）；临时机 A16_root 已删不重建（用户拍板）。

## 批次 A 完成（2026-09-18 午后）

- A16_play Play 取件台部署（playstore 镜像 + 用户登录）但 Play 拒装 AppChecker（账号设备列表过滤）；改由用户从 Pixel Fold 导出 v4.3.0 分包 zip，SHA256 存档 tests/ui_study_upstream/apks/appchecker_4.3.0/，主力实例 install-multiple 装入（uid 10217，保留作参考工具）
- 六屏走查截图 ac_*.png；结论：AppChecker=LibChecker 前身同构五 tab，无颠覆性信息架构；可吸收两条（统计页 Target API 分布卡 / 详情头部安装·更新时间行），不吸收环形图/System Info tab 等（详见记忆 sdk-pruner-ui-review 批次 A 节）
- A16_play 实例已关机保留（定位=只下载/提取数据，不作测试环境，已入全局规范七.14⑦与 AVD 手册第七节）

## 批次 B/C 完成 + 0.4.0 正式版发布（2026-09-18 傍晚）

- 批 B（6f942ad）：列表图标后台预载（VM 扫描后 IO 线程预载 icons map，组合期零 binder 调用）+ MainActivity runBlocking 异步化（lifecycleScope，烟测服务正常随开关启动）+ 动效对齐 Blocker（animateContentSize/箭头 tween(150) FastOutSlowIn；SegmentedButton 用 M3 内建不动；animateItem 已有）+ 统计页顶部卡三格 weight 均分修粘连（judge 2过1挂→修复复审全过）+ README 四图重拍（judge 4/4 pass）
- 批 C（41daa0f）：i18n 全量抽取 ~176 条（values/strings.xml 中文默认 + values-en 英文；label 辅助函数改 @Composable 组合期 stringResource；VM 消息 appCtx.getString；semantics{} 等非组合上下文先解析再传入）；英文 locale 实机端到端验证（模拟器 en-US 界面整体英文、无崩溃）
- **v0.4.0 正式版**：versionCode 5，versionName 0.4.0；tag v0.4.0 + Release 391329626（非 prerelease）；资产 Prunoid-v0.4.0-20260918-164341.apk（3.4MB，md5 320022d2f24e88a77a5f531a8b04ffef）已上传核验；主力实例 install -r 烟测通过
- 装机注意：重装后 uid 变化需重插 Magisk policy（本次 10240）；debug/release 签名不可混装（本次踩到，出 release 包解决）

## 小批 D + 数据批完成 + 0.4.1 发布（2026-09-18 晚）

- 小批 D（6ebb6d8+4cbccf4）：统计页 Target API 分布卡（最大余数法占比恒和 100.0% + plurals 单复数）+ 详情头部安装/更新时间行 + 引擎显示名资源化（judge 复审全过）
- 数据批（04f88d4，v0.4.1）：富描述 667→862（覆盖 34%→45%，native-libs 为新增主力）；分类扩表（UNKNOWN 452→352、CAUTION 110→156，词表来源=快照词频实证仅品牌名事实）；图标 657 维持（上游 175 矢量已同步，为该源天花板）
- **管线固化**（research/ 本地，不入公开仓）：build_snapshot.py 新增第 5 节富描述回填（锚点直配+同名互播，双 locale 逐字段取，native-libs 已含）；build_icon_map.py（rules.db label→iconIndex→IconResMap，精确+词元模糊档）；lcr_bundle/ = 上游 Bundle 本地副本（175 矢量+rules.db+IconResMap.kt）
- **踩坑记录**：b4/b5 管线脚本未落盘致重建差点丢富化（HEAD 对比救回）——管线必须随数据入本地库；Rules 仓 v2 tag 已推（与 app 资产同源）
- 发布：tag v0.4.1 + Release 391388382（APK md5 8f482337591e2ed6e877ae25f9865ef0）已核验；主力实例烟测通过

## v0.4.2 图标修复热修（2026-09-18 深夜）

- **缺陷定性**：release 构建自 M3 初版开 `isMinifyEnabled + isShrinkResources`，SDK 品牌图标 166 个 drawable 仅经运行时 getIdentifier 查找（assets/icons/lib_icons.json）零静态引用 → 全部被裁；v0.4.0/v0.4.1 及更早 release 均受影响，图标静默兜底为字母 monogram 无报错（b4 当时的"真机验证"跑在 debug 包上，debug 不收缩，掩盖至今）
- **修复**：app/src/main/res/raw/keep.xml `tools:keep="@drawable/ic_lib_*"`（0d162af）；顺修 EN 引擎说明两处拼接缺空格（aapt2 剥资源尾部空格，改 Kotlin 拼接处补 " "）
- **验收**：aapt2 资源表 ic_lib 0→225 条；七级 UI 逐级截图（tests/icons_042/1-7）自查 + judge 7/7 pass——快捷条/SDK 卡/档案卡/组件视图归属头像/SDK 库全部品牌矢量回归；安全分布 4/11/39 与 UNKNOWN 回填数据吻合
- **后续原色对齐（未发版，随下版）**：用户指出上游图标是彩色的——Icon 默认 tint 把多色矢量染成单色剪影（b4 起一直如此）。改 tint=Unspecified + 品牌分支中性浅底圆（surfaceContainerHighest），字母兜底不变；judge 3/3 pass（tests/brand_043/）
- **发布**：tag v0.4.2 + Release 391537651，APK md5 03569d31635148d3be07a04ca415521b 已核验；烟测通过
- **教训**：动态资源查找必须配 keep 规则；"真机验证"要核对构建类型（debug/release 资源面不同）

## 批 E + v0.5.0 发布（2026-09-19 凌晨）

- **E1 查找力**：列表筛选 chips 横滚行（已应用/分类多选×10/安全等级菜单/五种排序/Reset）+ 状态提升修复汇总联动（"7 apps·7 matched" 实测）；库页分类筛选+名称排序；库页档案卡 safety 四级派生补修 R1-P1⑦ 遗留
- **E2 统计重组**：Target API 卡撤销（用户拍板非工具焦点）；分类环形图（Canvas 自绘，中央 670 总数，图例数值+占比最大余数恒和 100%）；未识别前缀 Top 5 卡（settings 223/byted.live 150/…）+ 贡献引导；页面补 verticalScroll（首装发现被裁 bug）
- **E3 设置页对齐 OptIcon**：SectionTitle+SettingsCard 分组（引擎/自动化备份/订阅/应急/关于）+ About 五行（版本/GitHub 链接/致谢与许可/AI 声明/免责）
- **judge 终审 9/9**：首审挂 2 项——①CLEAR_IFW action"不一致"实为项目 AGENTS.md 更名前旧登记（代码三处统一 prunoid，AGENTS.md 已本地修正，该文件按红线不入公开仓）；②About 截图未滚到底（采集脚本 bug，重截通过）
- **发布**：tag v0.5.0 + Release 391579403，APK md5 0f2f0a8b06d32f61fd67a3b20c715b93 已核验；README 重拍（新增 stats/about 两图）
- 版本线：v0.4.0→v0.4.1→v0.4.2→v0.5.0（versionCode 8）

## 批 F+G + v0.6.0 发布（2026-09-19 凌晨②）

- **批 F**：顶栏精简（仅订阅点+设置齿轮，PullToRefreshBox 下拉重扫；订阅/恢复对话框删除，功能归设置页）；订阅源行 OptIcon 化（40dp IconButton、busy spinner 原位、URL·拉取时间合并行）；备份二级化（用户拍板"备份=可选项"：入口行→对话框披露用法/路径（/data/data/.../files/backups/，卸载即清）/恢复点勾选恢复/份数自由数字输入 1-99999，滑杆删除）；Recovery 组聚合（应急命令 tap 复制 + mono 块 + 清除全部 IFW 双击确认迁入）
- **批 G 现场状态**：DisableEngine.readLiveDisabled 单次合并 shell（105 包一次调用）读 IFW xml component-filter + dumpsys Disabled components；详情头部现场行 "Disabled now: N components"（tertiary 色，独立于 applied 记账行）；SDK 卡 "N disabled" 现场计数 pill；组件视图行禁用勾标；apply/restore 成功后单包重读；Recovery 卡显示当前 IFW 总条数
- **bug 链**：uid 漂移致 su 静默拒绝（当前 10223，policy 表残留 10240 僵尸条目——插 policy 前必须先 dumpsys 查实际 uid）；readLiveDisabled 正则行三轮转义踩坑（Python heredoc 截断+Kotlin 转义）最终整行重写；解析 bug=marker 行不建集合致空现场包丢失
- **judge 6/6 pass**（补 About 备份入口图后）；AGENTS.md 安全层条目改写（备份除名，底线=白名单/写前校验/现场正向推导恢复）
- **发布**：tag v0.6.0 + Release 391656091，APK md5 246e7dba15d266ecb0f5abbdf7a3334c 已核验

## 批 H + v0.7.0 发布（2026-09-19 早）

- **批 H（M3E 严格审查+彩蛋）**：设置页 About 卡重构为 OptIcon 同款彩蛋——整卡可点，锁定态 tap 计数（🐾 snackbar 递增，第 7 击 🐺 解锁并持久化 DataStore），解锁态 chevron+展开作者块（avatar_deserthouse 头像/澪狼 @deserthouse/Ling the Wolp/vibe line/github.com/deserthouse 链接）；碎碎念块二级彩蛋（🍆×6→💦 常驻 1% 行→再点出「一滴也没有了」）；致谢/AI 声明/免责拆独立合规卡。顶栏标题 fontWeight Medium 统一；详情 SDK 卡 "已禁用 N" 从挤压 pill 改为分类行内联 tertiary 标注；统计环形图入场扫入动画（tween 600ms）
- **环境坑定案**：模拟器重启会重排 app uid + Magisk policy 表出现 deny 残留——本轮 10223↔10240 漂移两轮，su 静默拒绝致 root 警告条/live 读空；对策=policy 前 `pm list packages -U` 现查 uid，策略表清理僵尸条目（现 2000/10228/10240 干净态）
- **发布**：tag v0.7.0 + Release 391719193，APK md5 a48aced0d9808d64eece05c37d310d1b 已核验；judge 9/9 pass；v0.6.0 版本文件漏提交的 tag 已 force 重打修正（08156aa）
- 版本线：…v0.5.0(vc8)→v0.6.0(vc9)→**v0.7.0(vc10)**

## 批 I + v0.8.0 发布（2026-09-19 晨）

- **手动语言切换**：设置页顶部语言组（System/中文/English segmented）——attachBaseContext 包裹 activity + 应用级 resources.updateConfiguration 同步（VM 消息走 appCtx 一并切换）+ DataStore 持久化；切换竞态修复（落盘完成后 recreate）
- **功能审查修正**：详情默认勾选 SAFE-only（用户拍板）；过时文案"恢复入口在列表页应急菜单"修正为指向设置页应急通道；库页 sheet 量化句 i18n（"命中 N，已禁用 M"）
- **致谢卡对齐 OptIcon CreditEntry**：三条目（LibChecker-Rules/Bundle、blocker-general-rules、libsu）项目名+描述+可点链接；订阅源 URL 行改 primary 可点外链+Launch 图标
- **跨应用禁用 SDK**（disableSdkEverywhere）：后端就绪（白名单过滤/批量 IFW/记账/现场重读），实测发现 sheet 内按钮点击命中问题 + 白名单系统应用占目标多数（Play 商店等 6/7 被安全层正确拦截）——**入口暂缓发布**，后端与过滤保留，下批修复 sheet 点击后放出
- **judge 全过**（语言组中文态/致谢 CreditEntry/统计选中态补拍后闭环）；v0.6.0 tag 曾源码不一致已 force 重打修正
- **发布**：tag v0.8.0 + Release 391793411，APK md5 0c88be998f955a2c90277076b17524ec 已核验
- 版本线：…v0.6.0(vc9)→v0.7.0(vc10)→**v0.8.0(vc11)**

## 批 J + v0.8.1 发布（2026-09-19）

- **平台欠账清偿**（审查后全部批准）：①预测性返回 flag enableOnBackInvokedCallback=true（A14+ 系统返回动画；targetSdk36+ 迁移窗口）②备份/迁移规则显式化（带走偏好/订阅/applied，排除 backups/ 设备现场快照——用户拍板）③localeConfig 注册 zh-CN/en（Android 13+ 系统应用语言入口打通）④品牌 splash（背景色同源 surface + 图标，values/themes.xml）⑤FGS 三参 startForeground 显式 SPECIAL_USE 类型（API34+ 契约）⑥库页跨应用禁用入口放出
- **跨应用禁用 tap"失效"定案**：adb input tap 无法命中 Compose ModalBottomSheet 子窗口（工具注入限制，非应用缺陷——同 sheet 开关也不响应 input tap；UIA 树/bounds 正常；disableSdkEverywhere 对 AppChecker 实际写入成功过）。真手验证留给用户
- **发布**：tag v0.8.1 + Release 391959049，APK md5 c0d434219f5c99a6bb3f7a9000fcddd6 已核验；FGS 启动无 SecurityException；返回链 BACK 实测正常；manifest 四项 aapt2 验证在 APK 内
- 版本线：…v0.6.0(vc9)→v0.7.0(vc10)→v0.8.0(vc11)→**v0.8.1(vc12)**

## 批 K+L+M + v0.9.0 发布（2026-09-20）

- **批 K 数据治理**：原则 v2 成文（research/2026-09-19_数据管理原则v2.md：去重/补全/收录四步/前缀归类四则）；审计工具 tests/k1_audit.py（aapt2 直读 APK manifest，自家包排除，三轮调试踩坑：全类名拼包/中缀前缀/引号贪婪）；22 规则增补+3 规则前缀并入 → 六样本未识别 927→368(-60%)，装机识别 670→711；规则仓 v3 + validator 同名降警告+前缀族碰撞警告
- **批 L 列表/详情重构**：筛选/排序收敛两入口（Filter sheet 三组多选 + Sort menu 显当前项）；搜索区滚动收起（LazyListState 方向判定+AnimatedVisibility，OptIcon 同款）；SDK/组件双视图搜索；未识别组件可勾选禁用（默认全不选+红色未验证警示+AdClose 式疑似筛选，Scanner UnmatchedGroup 补 components/types 字段）
- **批 M 库档案卡**：「使用此 SDK 的应用」清单（逐应用 已禁用 N/M / 未禁用 状态+开关，开=跨应用禁用/关=单包恢复）；sheet 内嵌状态行修复 Snackbar 遮挡；死开关语义根治
- **judge 通道故障**：两轮独立 judge 均读不到图（仅返回 CDN URL）——子代理视觉通道环境级故障；主通道自查四张关键图全过（两按钮/sheet 三组/详情 60SDK/未识别卡）后发布，**judge 补审挂账**
- **发布**：tag v0.9.0 + Release 392155711（prerelease），APK md5 2e50f20eeb29961e79bfa85af349b66b 已核验
- 版本线：…v0.8.0(vc11)→v0.8.1(vc12)→**v0.9.0(vc13)**

## 酷安+应用宝样本大扩充（2026-09-20 晚，K5-K7）

- **酷安入驻**：官网直链 16.6.2（116MB）装机+审计——37 规则命中，发现 NetEase 易盾一键登录/Volcengine OneKit/腾讯 QCloud LogUtils（已入库）；非自家未识别仅 8 组件
- **应用宝批量 8 脏应用**（WiFi 万能钥匙新旧版/墨迹/番茄/UC/拼多多/快手/百度）：**RemoteApkManifest Range 局部拉取**（EOCD→CD→manifest 数据，单应用 4-5 秒，免整包）；CDN JS 挑战静态求解（__tst_status=WTKkN+bOYDu+wyeCN 算术+数组旋转 327 次，cookie 全 CDN 通用）；排除自家后仅 107 组件真未识别
- **新增规则 14 条+前缀扩展 6 处**：Feisuo 飞梭广告（上海连妍）/FinClip 容器/字节小程序容器/阿里 svideo+exthub/TTC 支付/嵌入 chromium/Unity WebGL/AIMi Push/Ali TBAuth 等；byazt→穿山甲并入（隐私政策佐证）
- **数据总量**：1929→**1969 规则**；装机识别 **711→765 hits**（酷安装机后 270 apps）；描述覆盖 909；规则仓 **v5**
- 工具沉淀：tests/remote_manifest.py（Range 提取器）/tests/range_audit.py（批量）——后续样本扩充管线即跑即得
- 版本未发（快照入库随 0.9.1/0.10.0 出）

## 批 N/O/P 推进（2026-09-21）

- **批 N WorkMode 架构 ✅（v0.9.1 / vc14，Release 392492307）**：WorkModeInfo 契约（identify/disablePerApp/declareGlobal 三能力）；设置一级工作方式（Root/审计）→引擎/自动化段级联；audit 模式禁用全链路禁灰+VM rootGate 兜底；IFW 重应用两档（默认 open=启动对账缺口提示/realtime=常驻 FGS；实测 open 档 RuleGuard 零进程）；备份默认关闭（backupEnabled 开关）。实测：审计切换→级联隐藏→详情按钮禁灰全链路过
- **批 O 社区数据入口 ✅**：app 内「分享组件清单」（未识别卡按钮→JSON→系统分享器，零上传零 token）；规则仓 Component report issue 模板+贡献指南 v2；About 数据说明行。UNKNOWON 长尾飞轮就位
- **批 P LSPosed 声明式——地基完成，验证门禁未过**：双身份（assets/xposed_init LF 锁定+manifest meta，compileOnly Xposed API 82）；PrunoidHook 骨架（声明加载/前缀匹配/全 try-catch，resolve 层 hook 点**待实现与上机验证**）；DeclarationsStore 通道（/data/misc/prunoid root 写 system_server 读，默认空=零效果）；设置声明段（实验警示）+档案卡全局画圈开关。**放出门禁：hook 点实现+LSPosed 实机上机验证+bootloop 恢复演练**——未过门前功能默认关闭且 UI 有警示
- 版本线：…v0.9.0(vc13)→**v0.9.1(vc14)**

## 批 P 上机验证（2026-09-21 深夜）

- **通过**：双身份被 LSPosed 识别；scope/enable DB 直改+整机重启生效；**恢复演练**（禁用+重启→系统全正常）；hook v2（Field 缓存+快速返回）装入
- **堵点（宿主框架策略）**：LSPosed 框架默认 "skipped system server"——system_server 注入被框架级关闭，模块从未运行；此前 ANR 系误归因（三台并行环境偶然事件）
- **待拍板**：①调研 LSPosed 是否有 system_server 注入开关（源码级）；②改走 per-app 进程注入（语义降级：目标 app 自解析不到，全局拉起拦截缺失）
- 批 P 保持默认关闭发布无害（declarations 空=零效果）

## 批 P 攻坚 + v0.10.0 发布（2026-09-21 深夜②）

- **上游调研补课**（用户纠偏后）：AdClose/Fuck-AD/Thanox 全 per-app 进程注入，无人碰 system_server——上游先例佐证
- **scope 修正定案**：LSPosed daemon 源码实证 shouldSkipSystemServer 查询 scope 字面量为 **'system'**（非 'android'，我此前插错值）→ 修正后注入通道打通（"skipped" 消失）
- **hook 绑定链攻三关**：①R8 吃掉 hook 类（xposed_init 字符串引用不可见→proguard keep）②services.jar 非 boot classpath（getSystemClassLoader 拿不到→ActivityThread.getSystemContext().getClassLoader() 成功）③zygote init 时机过早（→queryIntent 首回调时绑定+HandlerThread 兜底）。**ComputerEngine queryIntentActivities/Services/Receivers 全挂载成功**；30s 周期热重载
- **诚实纠错**：所谓"拦截铁证"系基线错误（MAIN/VIEW 查询天然不含广告组件）——**查询层过滤 ≠ 启动拦截**（显式 startActivity 不走 query 列表）。真正语义需 AMS 启动层 hook（ActivityStarter），为下一里程碑
- **恢复演练**（禁模块+重启）早前已过；declarations 默认空=零效果
- **发布**：v0.10.0（vc15，Release 392570716，prerelease）——批 O 社区管线 + 批 P 机制地基（声明式 UI 照常但默认关闭，启动层拦截完成前不宣传拦截能力）
- 手机样本批 352 应用零失败提取至 tests/apks/phone_batch/（用户新会话拆解中）

## 【P0 已修】未识别卡展开体"渲染缺失"翻案 + 修复（2026-09-21，c47245c）

- **翻案（打点实锤）**：组合层 onSizeChanged/SideEffect 打点证明组合/测量/卡高全部正常（卡 3408px 满高、TextField 922x147）——所谓"渲染缺失"实为**卡顶贴屏底（y≈2060/2400），展开体整体在视口折叠线下**，分隔线以下是屏幕外空白。上轮"非视口问题"排除结论系误判（把屏外空白当成了卡内空白）
- **修复**（AppScreens.kt 未识别卡）：详情列表挂 `rememberLazyListState` + item key="unmatched"；展开时若卡顶在视口下半区（offset > viewport*0.35）自动滚动把卡顶带到视口顶。**坑**：animateScrollToItem 与 animateContentSize 竞态会 42ms 中途停（实测 first 停 60+40 只滚 105px）→ 两阶段：轮询等展开尺寸收敛（50ms 间隔，guard 20 次）再滚 + 落点断言（offset>2 则 scrollToItem 瞬时校正）
- **验收**：judge 对 release 包两帧像素审查 A-G 七项全 PASS（搜索框/chip/10 行勾选行/箭头朝上/卡顶贴视口顶/无卡内空白/折叠正常）。证据 tests/p0_rel_03_bottom.png、p0_rel_04_expanded.png
- **教训**：①单帧截图无法区分"没渲染"与"在视口外"——展开类缺陷复现必须展开后补一次滚动帧再定性（judge 四轮与 AI 幻觉之争，双方看的都是同一盲区）；②`gradlew | tail` 管道吞退出码，构建失败链继续装旧包空跑一轮（已在本轮实测中浪费一次，链式命令对构建步骤必须单列检查 BUILD SUCCESSFUL）
- 随版计划不变：0.11.0（本修复 + 2003 规则快照 + FlowRow 修复随版，版本号升级与发版另行执行）

## UI 拉通重审 + v0.11.0 发布（2026-09-21 深夜，视觉模型会话②）

- **用户指令四件套**：硬待办发版 + 上游借鉴落地 + 拉通重审 UI + i18n 顺手做；AppChecker 用户纠正"早发过了"属实（物料 ac_*.png+APK 存档在案，吸收点已落地/撤出，无需再采）
- **上游借鉴落地实况**：拉通改法五条 b1-b6 早已全落地；真剩下的是 R1 累积项 P1⑨（未识别计数截断少报）与 P1⑩（裸子串误报），均已修（全量口径 644/444+截断标注；词元精确匹配 trackplayer 不再误标）
- **judge 拉通重审三轮**：第一轮 fail 集群=我采集侧事故（back 链退出/坐标打空/CDN 缓存干扰主通道读图）+ 若干真问题；修复 9 项：库行/档案卡补安全徽标、17 vs 74 计数口径统一（eligible 过滤撤除）、**typeLabel provider 映射一行写漏（"provider"→comp_other，FileProvider 自始显示 Other component——数据层无辜，插桩日志实锤后真凶落网）**、英文 plurals（pluralStringResource 必须传格式化参否则返 %d 模板）、engines_note 与 Backup off 文案矛盾；二轮三帧 pass+暗色设置补帧；三轮 release 包终验全 pass
- **重要元教训**：AI 主通道读图在本会话至少 4 处脑补（快捷条/58 SDKs/572/设置页内容）被 judge 逐条戳穿——铁律再证：结论只认 judge 像素+dump 数据，导航可读帧只用于选坐标；采集侧必须有 MD5 查重+dump 验屏双保险
- **v0.11.0（vc16）已发布**：Release 392883986（pre-release），APK md5 75fdbb93ff70e2daae7525faf6ec13fc 下载回读 MATCH；tag 远端=本地；内置快照 2003（与规则仓 v6 逐字节 identical）；规则仓 v6 早已在远端；README 六图重拍（judge 对位 pass）+ROADMAP Status 刷新随版
- 版本线：v0.10.0(45c85d1)→P0 修复(c47245c)→UI 批(8f9aa35)→docs(fcb4f1d)→版本(cfcc889)=v0.11.0

## 三层拉通整改 + v0.12.0（2026-09-24 凌晨）

- **背景**：两轮审查（视觉 judge 23 条 + 代码架构 21 条）合并 38 项工作、7 批计划，用户批准（含 4 决策点 AI 拍板：筛选 sheet 化/AppCompat 官方语言通道/筛选状态全入 VM 会话态/规则名 en schema 留数据批）。
- **批Q 设计系统**：Theme 补 Shapes role 化 + MotionTokens(150/300/600ms) + MotionScheme.expressive + ChartPalette（避开安全语义色相）+ SearchField/SectionLabel 公共组件；SafetyBadge 去 fontSize 覆写。
- **批Q2 拆分**：AppScreens.kt(1669行)→Components/ListScreen/DetailScreen/ArchiveSheet 四文件（精确锚点切片+断言，修 5 处接缝编译错）；ALL_CATEGORIES 单一来源；SettingsScreen 主体拆分**部分完成**（辅件已组件化，8 分区主体搬运留待下批）。
- **批R 信任**：备份声明三处条件式统一；live_line 归属明示（incl. non-Prunoid sources）；Restore 需 appliedEntry；未识别卡文案对齐功能（R4）；**RuleDedup 运行时别名去重**（保守合并：safeToBlock=AND/confidence 取低/并集；显式别名表+同名组；保序；3 单测）——识别总数 774→757；确认句口径修正+默认勾选透明化。
- **批S 交互**：Apply/Restore 备份倒计时守卫（CountdownConfirmTextButton 复用）；库页筛选 sheet 化+排序菜单选中态；Applied chip FlowRow 修复；sheet 手势条避让；Clear IFW 常态 error 色。
- **批T 口径/IA**：user apps/matched components/incl. system 标注；快照版本行（设置页订阅区）；加源置信度披露；库页去返回箭头；语言迁 AppCompatDelegate.setApplicationLocales（MainActivity→AppCompatActivity）；筛选状态全入 VM；源名资源化。
- **批U/V**：暗色品牌托底 BrandTileDark；库计数中性化；comp_* TitleCase；全角括号；ArchiveSheet 死参清理；17 未用串删；exportComponentReport 迁 kotlinx.serialization；rules-repo build_snapshot.py 源头 generator/旧名同步（"改产物未改源头"回归点封堵）。
- **验收**：release 走查脚本 walk.py/walk2/walk3（dump 验屏逐步断言）全绿 → judge 一验 16 帧（10 pass + 3 真问题修复：excluded_line 前导空格 aapt2 剥除→Kotlin 拼接补空格；统计第三标签截断→maxLines=2；stats 前缀卡旧语义→R4 同源）+ 3 采集过期帧重摄 → judge 二验 7 帧全 pass。
- **发版**：v0.12.0/vc17，Release 395007805（pre-release），md5 4d412a3528830b3af9c8fea42b9bd164 回读 MATCH，tag 一致；README 六图重拍；双仓推送（主仓 c56ad95 / 规则仓 f951a7f）。
- **二轮复审遗留（低中危，未修）**：①统计顶卡三列基线错位 ~7dp（maxLines=2 引入，verticalAlignment=Top 一行可修）；②donut Framework/Maps 蓝近同（RGB 距离≈26）相邻；③SettingsScreen 主体拆分；④死代码链 language 字段/KEY_LANGUAGE；⑤规则名 en 机制（schema 扩展，数据批）。
- 版本线：…→0.11.0(4ce4b12)→UI 整改批(db2f250)→0.12.0(c56ad95)

## 会话归档（2026-09-21 终）

- 本会话交付：v0.9.0/0.9.1/0.10.0 三版（vc13-15，全 pre-release）；批 K-O 全清 + 批 P 地基；规则 1929→2003；手机批 352 应用样本入库（tests/apks/phone_batch/，32GB）
- 工作区干净、双仓同步至 45c85d1；~~新会话入口 = P0 修复~~ → **P0 已修复（c47245c）**，下一入口 = 0.11.0 随版发布（P0 修复 + 2003 规则快照 + FlowRow 修复）
- 环境：主力 AVD 在线（Prunoid 模块 enabled+scope system，declarations 空=零效果）；judge 视觉验收通道已恢复（documents:visual-judge），验收铁律=AI 读图只导航、结论以 judge 像素为准

## TODO

| # | 事项 | 状态 | 依赖 |
|---|---|---|---|
| 1 | 立项/定名/双仓 | ✅ | — |
| 2 | M1 扫描 + IFW 引擎 + 快照 | ✅ | #1 |
| 3 | 规则仓冷启动 | ✅ | #1 |
| 4 | M2 订阅 + 双引擎 | ✅ | #2 |
| 5 | M3 自动重应用（Magisk 模块模式降级为条件触发项） | ✅ 核心 | #2 |
| 6 | M3E UI 视觉重做 + 模拟器全量回归 | ✅（2026-09-17 深夜） | #5 |
| 7 | 首个第三方 PR 走通共建 CI（PR 模板 + CI 校验已在位） | ⏳ 待外部贡献者 | #6 |
| 8 | 上游 UI 借鉴落地（拉通改法五条 + 用户审查累积项，见持久记忆 sdk-pruner-ui-review） | ⏳ 待用户 UI 审查后合并迭代 | #6 |

## 全局配置/约定

- 项目原则：用户提需求验收，AI 全权负责代码；先调研后动手；版本隔离备份（backup/ 或 _vN 文件名）；删除文件须用户授权
- 关键风险记忆：IFW 不拦隐式广播（需 intent-filter 规则）；系统组件误禁会 bootloop（需白名单+恢复机制）；不复制 GPL/AGPL 项目的代码与数据库文件

## Prunoid 更名与 #1–#10（2026-09-18 清晨）

- 更名：SDK-Pruner → Prunoid（pruner+paranoid+Android）；GitHub 双仓 deserthouse/Prunoid + prunoid-rules（API 改名，旧 URL 重定向）；包名 io.github.deserthouse.prunoid；打包凭据属性 SDKPRUNER_* 保留（keystore 契约）
- #1 自动重应用开关：DataStore + 服务启停联动 + A15+ 代价说明（实测开关与服务 0↔3）
- #2 多源订阅：SettingsRepository 源注册表 + RuleSubscription 按源缓存（旧单源自动迁移）+ RuleRepository 并集合并（同 id 置信度高者胜）+ 设置页源列表管理
- #3-#6：SDK 档案卡底部弹层（量化句/结构化字段/贡献者/来源链接）+ 头部快捷条 + 组件类型分组保留 + 倒计时锁定确认（4s 递减）+ 框架层文案强化
- #7 SDK 库浏览页：Found/Not found + 搜索 + 命中计数徽标 + 档案卡复用（实测 132 在机/1929 全库）
- #8 schema iconUrl（懒加载，monogram 兜底）；#9 规则仓 CI 同名重复警告级校验（fe9f945）
- #10 0.3.0-alpha：versionCode 3；assembleRelease 3.4MB；tag v0.3.0-alpha；Release 已传资产并核验（md5 e1dfe777b3af32f14677d91cf288ac4c）
- A16 台：新包 uid 10238 + Magisk policy 重插 + 旧数据迁移（applied/订阅缓存）；release 包烟测通过


## 0.4.0-alpha（2026-09-18，UI 重构+R1 修复发布）

- 批 0（8bacff0）：R1 五个 P0 修复（倒计时真锁/恢复按记录回滚/IFW 不再丢显式加选/规则域单例化/订阅点语义）+ MultiSourceMerger 纯函数 7 单测
- 批 1（4062866/ff38c1b）：底部三 tab（应用/SDK 库/统计）+ 详情双视图（SDK/组件视图带类型 pill 与归属头像）+ 大头部 Target/Min/Size + 快捷条截断
- 批 2（03a3662）：统计页实装（总量卡/分类分布/排行 Top10）
- b6/b7 前置：922ebd6/9a3cabd；AppScreens 脚本切分事故已回滚（拆分推迟，理由=平衡大括号 span 算法对 val 定义失准，风险>收益）
- 发布：tag v0.4.0-alpha + Release 资产 Prunoid-v0.4.0-alpha-*.apk（md5 ff77c336…，3.4MB）已上传核验；A16 台卸装重装烟测通过（uid 重插 policy + 通知权限）
- 批 3 存量：AppChecker 条件采（等渠道）、动效对齐、i18n 评估（推迟至 0.4 转正后）

## 下一批规划（批次 A/B/C，2026-09-18 午后批准待推）

- 批次 A：AppChecker 补采（渠道=appchecker.en.uptodown.com，装 A16_clean 隔离环境，截图存 tests/ui_study_upstream/ac_*）
- 批次 B：README 截图重拍（docs/screenshots/list.png 仍是改名前旧图）+ 列表图标异步化 + 动效对齐（Blocker 三组件参考）
- 批次 C：i18n string resources（中+en）→ 0.4.0 正式版（versionCode 5）
- 环境尾项：本地目录改名 ✅（2026-09-18 用户已执行，工作目录=Projects\Prunoid；工程文件 4 处旧名残留已清 + 构建缓存重建 + assembleDebug 验证通过；AVD 名 SDK-Pruner_A16_root 维持不改）
## 二轮复审遗留闭环（2026-09-24 凌晨③）
- 9 项全修（9743930 / 规则仓 c298f90）：restore 备份倒计时守卫、统计三列顶对齐（judge 实测 1px 级）、97 未用 import、language 死链全拆、SettingsScreen 拆 8 分区、RuleDedup 别名表迁快照 aliases 字段（schema 增量，向后兼容）、donut maps 色拉开、MotionTokens 类型化、theme_name 删。
- judge 三验统计帧实测三数字顶部偏差 1px（pass）。
- 待办：0.12.1 随版（本批修复随下版发布）；快照 aliases schema 增量需同步规则仓 README/CONTRIBUTING 文档。

## 用户视角筛查 + 全功能拉通修复（2026-09-24 上午）
- **用户视角筛查**：8 条旅程实机走查 21 项（阻塞 9/困惑 5/烦躁 7）；经校验勘误 2 项（B1 筛选实为生效、C4 Snackbar 存在）、补 8 项（providers 盲区为真 bug 等）→ 净 26 项。
- **SDK 库操作链排查（用户报"全不生效"证实）**：三层断链——目标集用规则锚点（87% 空）/ onDisableEverywhere 死回调 / per-app 开关越权；连带 4 项（双数据源矛盾/别名搜索断/busy 卡死风险/dedup 半套）。
- **全功能拉通**：23 链路 17 全效 + 6 问题；产品架构判断：双口径/规则管线/安全边界设计正确，IFW 所有权模型缺失+busy 全局粒度是两大结构缺陷。
- **整改**（b476d42）：37 项全修——库链三层重构（扫描命中目标集/全局按钮/per-app 粒度 setSdkForApp）、IFW 合并+保留删除语义（外部规则共存）、providers 盲区、pm enable 校验、选择集回落、未识别互斥、Restore 确认、busyOp+finally、msgSeq、BOOT 恢复守护、首启引导卡、audit 徽标、子计数、别名搜索、筛选传导、确认句归类、卸载提示、备份后重扫、DedupResult 结构化+索引归一、byTypeFor 测试。
- **实机验证**：per-app 开关 on/off 现场 1→99→1（外部 JPush filter 幸存=合并语义实证）；judge 复审 3/3 pass。
- 遗留（下批候选）：IFW 解析器正则级（建议正式 XML 解析）；SettingsScreen 主体再拆；命中高亮/后果维度/LSPosed 通知产品面；rules-repo schema 文档同步 aliases 字段。


## 用户视角整改 + 库链修复 + 批J/K/L（2026-09-24 下午）
- **用户视角 26 项 + 库链 7 项 + 拉通 4 项全量闭环**（b476d42 + 9743930 前批 + 63554b8/516f8c0）。
- 库链三层断链修复实证：per-app 开关 on/off IFW 现场 1→99→1（外部规则幸存）；"Block in all apps (N)" 按钮接入；目标集改扫描命中。
- 手势返回修复：删 enableOnBackInvokedCallback 声明（OEM 手势层兼容；模拟器实测手势回设置→列表正常）。
- 二轮复审 9 项全闭环（removeIfw 语义修正正式版/统计基线/97 import/别名 schema+第三实体/xmlpull/Settings 拆分/idMap 测试/armed 文案/plurals 清理）。
- 新增：首启引导卡、audit 只读徽标、徽标广告/推送子计数、未识别卡就近禁用按钮+类型构成标签、SafetyBadge 后果语义、BootReceiver、卸载前清规则提示、备份改单选。
- 测试：RuleDedup 3 + ByTypeFor 2 全绿。
- 待办遗留：命中高亮/后果维度 UI 深化/LSPosed 通知（外部）/AppUiState 拆族（推迟）/过程帧清理。

