# exodus_odbl/ —— ODbL 隔离区

> 依据 `research/2026-09-17_数据源合规与建库原则.md` §3 设立：任何源自 Exodus εxodus 数据库的衍生数据
> 只能存放在本目录，**不得合并进 Apache-2.0 规则仓库主体**（ODbL v1.0 同源共享义务）。

- 拉取时间：2026-09-17
- 来源：https://reports.exodus-privacy.eu.org/api/trackers （Exodus Privacy，法国非营利组织）
- 协议：数据库与 API 结果依 **ODbL v1.0**（个别内容 DbCL v1.0）提供；平台代码为 AGPL-3.0（本目录不含任何平台代码）
- 文件：`exodus_trackers_20260917.json` —— 432 个 tracker 全量（428 个含 code_signature，260 个含 network_signature），
  字段：id/name/categories/code_signature/network_signature/website/description/documentation/creation_date
- 使用限制：
  1. 消费方式 = 「来源引用」或「独立 ODbL 分发」，规则库主体条目只能引用其事实并标注 `source: exodus (ODbL)`
  2. 若对本库做衍生数据库并对外分发，衍生库整体须以 ODbL 授权
  3. description 字段为 Exodus 社区创作表达，**不进入我们的规则库文案**
- 刷新：重新拉取同 URL 覆盖保存，并更新本文件的拉取时间与数量统计
