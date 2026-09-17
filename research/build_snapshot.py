# -*- coding: utf-8 -*-
"""
SDK-Pruner 冷启动快照构建脚本（M1-R2）
正源与协议（见 2026-09-17_数据源合规与建库原则.md）：
  1. blocker-general-rules (Apache-2.0)   —— SDK 禁用规则 468 条（含 safeToBlock/sideEffect/contributors），正源
  2. LibChecker-Rules v44  (Apache-2.0)   —— 组件级识别锚点（四类组件 + native so），骨架
  3. Fuck.AD dex 提取物（客观事实）        —— 16 家聚合广告 SDK 根包名
  4. oF2pks/3xodusprivacy-toolbox（无协议，仅事实）—— 包名前缀补充（低置信度）
红线：Exodus ODbL 数据不进快照；GPL/AGPL/无协议源的文件与文案不复制（oF2pks 只取名称/包名事实字段）。
输出：app/src/main/assets/rules/snapshot.json（自建 schema，逐条带 source/confidence）
"""
import json, glob, os, re, hashlib
from datetime import date, datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LCR = os.path.join(ROOT, "research", "lcr_repo")
BLOCKER = os.path.join(ROOT, "research", "blocker_rules_repo", "rules", "en", "general.json")
OF2PKS = os.path.join(ROOT, "research", "of2pks_toolbox", "trackers448NEW.txt")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "rules")

# Fuck.AD v3.0.6 dex 提取的 16 家聚合广告 SDK 根包名（调研报告增补一 §B2，客观事实）
FUCKAD_ROOTS = [
    ("Pangle 穿山甲", "ByteDance", "com.bytedance.sdk.openadsdk"),
    ("GDT 优量汇", "Tencent", "com.qq.e.ads"),
    ("Baidu 百青藤", "Baidu", "com.baidu.mobads.sdk.api"),
    ("Kwai 快手联盟", "Kuaishou", "com.kwad.sdk"),
    ("TopOn", "AnyThink", "com.anythink"),
    ("TradPlus", "TradPlus", "com.tradplus.ads"),
    ("Sigmob", "Sigmob", "com.sigmob"),
    ("Mintegral", "Mintegral", "com.mbridge.msdk"),
    ("AppLovin MAX", "AppLovin", "com.applovin.mediation"),
    ("Unity Ads", "Unity", "com.unity3d.ads"),
    ("Unity Services Ads", "Unity", "com.unity3d.services.ads"),
    ("Vungle", "Liftoff", "com.vungle.ads"),
    ("IronSource", "Unity", "com.ironsource.sdk"),
    ("Qumeng 趣盟", "Qumeng", "com.qumeng.advlib"),
    ("Douban Ad", "Douban", "com.douban.ad"),
    ("Windmill 风车", "WindMill", "com.windmill.sdk"),
]

CAT_RULES = [
    (re.compile(r"广告|Ad[s]? SDK|Ads|Advertising|Monetization", re.I), "ads"),
    (re.compile(r"推送|Push|Notification|Message|messaging|GCM|FCM", re.I), "push"),
    (re.compile(r"统计|分析|Analytics|Statistics|Tracking|Tracker|Attribution|测量", re.I), "analytics"),
    (re.compile(r"崩溃|Crash|性能|Performance|APM|监控|Monitoring", re.I), "quality"),
    (re.compile(r"登录|分享|Share|Login|Account|支付|Pay", re.I), "social_or_pay"),
    (re.compile(r"地图|Location|定位|Map", re.I), "maps"),
    (re.compile(r"云|存储|Storage|数据库|Database|下载|Download|Update", re.I), "infra"),
]

def categorize(name):
    for pat, cat in CAT_RULES:
        if pat.search(name):
            return cat
    return "other"

def jload(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

sdks = {}   # merge_key -> rule dict
comps = {}  # merge_key -> list of {type, class}

def put(key, name, company, cat, prefixes, safe, side, sources, confidence, contributors):
    r = sdks.get(key)
    if r is None:
        sdks[key] = {
            "id": hashlib.md5(key.encode()).hexdigest()[:12],
            "name": name, "company": company, "category": cat,
            "packPrefixes": sorted(set(prefixes)),
            "safeToBlock": safe, "sideEffect": side or "",
            "sources": sources, "confidence": confidence,
            "contributors": contributors[:6],
        }
        comps[key] = []
    else:
        r["packPrefixes"] = sorted(set(r["packPrefixes"]) | set(prefixes))
        for s in sources:
            if s not in r["sources"]:
                r["sources"].append(s)
        if confidence == "high" and r["confidence"] != "high":
            r["confidence"] = "high"
        r["contributors"] = list(dict.fromkeys(r["contributors"] + contributors))[:6]
    return key

# ── 1. blocker-general-rules（正源，Apache-2.0）────────────────────
n_blocker = 0
for r in jload(BLOCKER):
    put(f"blocker:{r['name']}", r["name"], r.get("company", ""),
        categorize(r["name"]), r.get("searchKeyword", []),
        bool(r.get("safeToBlock", False)), r.get("sideEffect", ""),
        ["blocker-general-rules(Apache-2.0)"], "high",
        r.get("contributors", []))
    n_blocker += 1

# ── 2. Fuck.AD 16 家根包名（dex 逆向事实）─────────────────────────
n_fuckad = 0
for name, company, root in FUCKAD_ROOTS:
    put(f"fuckad:{root}", name, company, "ads", [root],
        False, "", ["Fuck.AD v3.0.6 dex(facts)"], "medium", [])
    n_fuckad += 1

# ── 3. LibChecker-Rules v44 组件锚点（Apache-2.0）─────────────────
def lcr_locale_meta(path):
    d = jload(path)
    zh = en = None
    for loc in d.get("data", []):
        if loc.get("locale") == "zh-Hans": zh = loc.get("data", {})
        elif loc.get("locale") == "en": en = loc.get("data", {})
    return zh or en or {}

comp_count = 0
TYPE_MAP = [("activities-libs", "activity"), ("services-libs", "service"),
            ("receivers-libs", "receiver"), ("providers-libs", "provider")]
for dirname, ctype in TYPE_MAP:
    for p in glob.glob(os.path.join(LCR, dirname, "**", "*.json"), recursive=True):
        rel = os.path.relpath(p, os.path.join(LCR, dirname))[:-5].replace("\\", "/")
        cls = rel.replace("/", ".")
        meta = lcr_locale_meta(p)
        label = meta.get("label") or cls.rsplit(".", 1)[0]
        team = meta.get("dev_team", "")
        key = put(f"lcr:{label}", label, team, categorize(label), [],
                  False, "", ["LibChecker-Rules v44(Apache-2.0)"], "high",
                  meta.get("rule_contributors", []))
        comps[key].append({"type": ctype, "class": cls})
        comp_count += 1
# native so：锚点 = 文件名（如 chaquopy.so）；regex/ 子目录无模式字段，跳过
so_count = 0
for p in glob.glob(os.path.join(LCR, "native-libs", "*.json")):
    so_name = os.path.basename(p)[:-5]
    meta = lcr_locale_meta(p)
    label = meta.get("label") or so_name
    key = put(f"lcrso:{label}", label, meta.get("dev_team", ""),
              categorize(label), [],
              False, "", ["LibChecker-Rules v44(Apache-2.0)"], "high",
              meta.get("rule_contributors", []))
    comps[key].append({"type": "native", "class": so_name})
    so_count += 1

# ── 4. oF2pks 补充索引（无协议，仅名称/包名事实，低置信度）────────
n_of2pks = 0
if os.path.exists(OF2PKS):
    with open(OF2PKS, encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("____")
            if len(parts) < 2:
                continue
            name = re.sub(r"^[µ°²?]+", "", parts[0]).strip()
            prefix = parts[1].strip()
            if not name or not prefix or len(prefix) < 5:
                continue
            key = put(f"of2pks:{prefix}", name, "", categorize(name), [prefix],
                      False, "", ["oF2pks/3xodusprivacy-toolbox(facts)"], "low", [])
            n_of2pks += 1

# ── 输出 ──────────────────────────────────────────────────────────
out = {
    "schemaVersion": 1,
    "generatedAt": datetime.now().isoformat(timespec="seconds"),
    "generator": "research/build_snapshot.py",
    "license": "Apache-2.0 (sources: blocker-general-rules, LibChecker-Rules; facts: Fuck.AD dex, oF2pks index)",
    "stats": {"sdks": len(sdks), "blocker": n_blocker, "fuckad": n_fuckad,
              "lcrComponents": comp_count, "lcrSo": so_count, "of2pks": n_of2pks},
    "sdks": [],
}
for key, r in sdks.items():
    r["components"] = comps[key]
    out["sdks"].append(r)

os.makedirs(OUT_DIR, exist_ok=True)
outp = os.path.join(OUT_DIR, "snapshot.json")
with open(outp, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
print(f"sdks={len(sdks)} blocker={n_blocker} fuckad={n_fuckad} lcrComp={comp_count} lcrSo={so_count} of2pks={n_of2pks}")
print(f"size={os.path.getsize(outp)/1024:.0f}KB -> {os.path.relpath(outp, ROOT)}")
