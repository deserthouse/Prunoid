<!-- 标题示例：新增 XX SDK 规则 / 修正 XX 的 safeToBlock -->

## 变更内容

<!-- 新增/修正了哪些 SDK？多少条？ -->

## 数据来源（必填）

<!-- 实机 APK 分析 / 公开文档 / dex 逆向 / 上游仓库（注明哪个）…
     无法说明出处的规则会被拒绝 -->

## safeToBlock 依据（若为 true 必填）

<!-- 如何验证禁用不影响 app 核心功能？ -->

## 自查

- [ ] `python scripts/validate_rules.py rules/snapshot.json` 通过
- [ ] id 无重复、sources 非空、未复制他人文案
