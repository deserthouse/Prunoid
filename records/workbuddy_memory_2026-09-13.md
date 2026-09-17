# 2026-09-13

## SDK-Slayer 立项调研（新项目）
- 项目路径 `C:\Users\deser\Projects\SDK-Slayer`，用户需求：root 权限禁用 app 内广告/统计/推送 SDK 组件（IFW/pm disable，不 hook 目标 app 规避反检测）
- 调研完成，报告在 `research/2026-09-13_立项调研报告.md`，项目状态在 `project_status.md`
- 关键结论：IFW 为主引擎（app 无感知，Android 16 仍有效；不拦隐式广播需 intent-filter 规则）；pm disable 为辅（跨更新保留但 app 可自恢复）；Shizuku ADB 模式禁不了普通 app 组件
- 数据源：LibChecker-Rules（Apache-2.0，组件级规则，最优源）、Exodus ETIP（API）、AdClose 30+ 国内 SDK 名单、GKD subscription；无现成"组件级安全禁用知识库"→ 需自建
- 本地 4 APK（AdHammer 全家桶，作者 ExtStars）已逆向：AdHammer dex 含 pm disable + PACKAGE_ADDED/REPLACED（机制可行性实证），无内置 SDK 库，规则云端 gist 分发；已移入 reference_apk/
- 状态：等用户拍板（立项/命名/目标系统范围/技术栈）

## 第二轮：Thanox/Fuck.AD 深挖（同日）
- 两个 APK 经代理 10808 从 GitHub Releases 直接下载成功（无需用户手动）
- **Thanox v8.6 资料来源挖清**：①assets/blocker_rules/{en,zh_CN}/general.json = 468 条组件禁用规则（418 条 Exodus ETIP 程序转化 + 50 条社区，仅 39 条 safeToBlock，规则锁在 APK 内无独立上游 repo）②assets/lcrules/rules.db = SQLite 2204 条 LibChecker 系组件识别规则（含国内 SDK 中文标注）
- 完整提取物已存 SDK-Slayer/research/（json+txt 三份）
- **Fuck.AD v3.0.6**（com.hujiayucc.hook）：纯 LSPosed，云端源 fkad.hujiayucc.cn，dex 提取 16 家广告 SDK hook 类名（穿山甲/优量汇/百度/快手/TopOn/TradPlus/Sigmob/Mintegral/AppLovin/Unity/Vungle/IronSource/趣盟/豆瓣/SmartDigi/风车），hook 类名可平移为组件禁用目标
- 结论强化：知识库生态位确实空着（Thanox 安全标注覆盖率仅 8% 且闭源锁死）；冷启动数据源增至 7 个，首版知识库预计 300+ SDK

## 第三轮：独立 SDK 库盘点（同日）
- 结论：独立数据库存在多家但维度互斥，LibChecker 谁也没完整收录——Exodus ETIP（类签名级，603 tracker/574+ 签名，AGPL）、DuckDuckGo tracker-blocklists app/android-tds.json（域名级+包名+数据类型信号，**CC BY-NC-SA 禁商用**）、Disconnect/X-Ray（域名级）、oF2pks/3xodusprivacy-toolbox（Exodus 漏收补充 31 条，Apache-2.0，2024 后停滞）、AppManager 签名库（内嵌源码，GPL）
- "组件级+开源+活跃共建"仅 LibChecker-Rules 一家；跨库合并层（按 SDK 实体对齐多源特征+安全标注）完全空白 → SDK-Slayer 规则库差异化定位确认

## 第四轮：覆盖度实测（同日）
- 克隆 LibChecker-Rules v44 全库到 research/lcr_repo（2673 条规则）程序化比对
- **Thanox 468 禁用规则 → LCR 覆盖仅 6%**（全覆盖31/部分18/零覆盖419）：零覆盖大头=Exodus欧美追踪SDK+国产推送(极光/小米/魅族/vivo/OPPO)+B站/京东广告
- **Thanox lcrules 2204 → LCR v44 覆盖 95.8%**（它就是 LCR v40 旧快照，87 条未收录多为已重组的静态库类目；直接用上游 v44 即可）
- **Fuck.AD 14 家广告 SDK → LCR 组件级覆盖 8/14**：TradPlus/Sigmob/Mintegral/AppLovin/趣盟/风车 完全没有
- 结论：LibChecker 只收"识别锚点类"，不能单独冷启动；骨架用 LCR(Apache-2.0)，血肉靠 Exodus+Fuck.AD类名+真实APK提取
- 存档：research/coverage_gap_thanox_blocker_rules.json（468条对照）+ lcr_repo/（LCR全库副本）
