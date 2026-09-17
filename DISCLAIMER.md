# AI 使用声明与免责声明 / AI Usage Statement & Disclaimer

## AI 使用声明 / AI Usage Statement

- 本项目的代码、文档、构建脚本与规则数据管线由 AI（大语言模型代理）在人类项目所有者的
  指令与验收下开发：人类提出需求并验收结果，AI 负责具体实现与迭代。
  This project is developed with heavy AI assistance: a human owner directs requirements
  and reviews the results, while an AI agent implements the code, documentation, and the
  rules data pipeline.
- 规则数据中的组件特征（包名前缀 / 组件类名）为对公开数据与实机 APK 的客观事实提取；
  由 AI 参与整理与合并，人类负责合规边界与最终取舍（见规则仓 NOTICE 与 CONTRIBUTING）。
  Component signatures are objective facts extracted from public datasets and on-device
  APK analysis; AI assisted with curation, while the human owner is responsible for the
  licensing boundary and final editorial decisions.
- 所有提交内容经过项目所有者审阅后发布。如发现错误内容，欢迎提 issue 指正。
  All published content is reviewed by the project owner. If you spot an inaccuracy,
  please open an issue.

## 免责声明 / Disclaimer

1. **按现状提供 / As-is**
   本软件按"现状"提供，不附任何明示或默示的担保。作者与贡献者不对因使用或滥用本软件
   而产生的任何直接或间接损失承担责任。
   This software is provided "as is", without warranty of any kind. The authors and
   contributors are not liable for any damages arising from the use or misuse of this
   software.

2. **组件禁用有后果 / Component blocking has consequences**
   禁用应用组件可能导致相应应用功能异常甚至无法使用。使用前请务必依赖应用内的备份与
   恢复机制；如需外部应急，参见 README 中的恢复说明。你在自己设备上的操作由你自己负责。
   Disabling app components may break the affected app's functionality. Always rely on
   the in-app backup/restore flow before applying rules; for external recovery see the
   instructions in the README. You are responsible for actions taken on your own device.

3. **需要 Root / Root required**
   本软件依赖 Root（如 Magisk）修改 Intent Firewall 规则。Root 本身的风险（设备保修、
   安全策略等）与本项目无关，由使用者自行承担。
   This software relies on root (e.g. Magisk) to modify Intent Firewall rules. Risks
   associated with rooting itself (warranty, security posture) are yours to manage.

4. **与厂商无关联 / No affiliation**
   本项目与规则中提及的任何 SDK 厂商、应用或其商标均无关联、无背书关系。
   提及的第三方名称与商标归其各自所有者所有。
   This project is not affiliated with or endorsed by any SDK vendor, app, or trademark
   mentioned in the rules. Third-party names and trademarks belong to their owners.

5. **规则数据 / Rules data**
   规则库只陈述客观特征与社区观察，不构成法律或隐私合规建议。数据来源与协议见规则仓
   的 NOTICE 与 CONTRIBUTING。
   The rules database only records objective signatures and community observations; it
   does not constitute legal or privacy-compliance advice. See the rules repo's NOTICE
   and CONTRIBUTING for data sources and licensing.

6. **用途自决 / Intended use**
   本项目提供识别与审计能力，如何使用由使用者自决。请遵守所在司法辖区的法律及目标
   应用的服务条款。请勿用于损害他人权益的用途。
   This project provides identification and auditing capability; how you use it is up
   to you. Comply with applicable laws and the terms of service of the target apps. Do
   not use it to harm others.
