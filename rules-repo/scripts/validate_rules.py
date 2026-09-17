# -*- coding: utf-8 -*-
"""规则快照校验：schema/枚举/id 唯一性/sources 必填。CI 与本地共用。"""
import json, sys, re

CATEGORIES = {"ads","push","analytics","quality","social_or_pay","maps","infra","security","framework","other"}
CONF = {"high","medium","low"}
TYPES = {"activity","service","receiver","provider","native"}

def main(path):
    errs = []
    d = json.load(open(path, encoding="utf-8"))
    if "schemaVersion" not in d: errs.append("missing schemaVersion")
    sdks = d.get("sdks", [])
    if not sdks: errs.append("sdks empty")
    seen = {}
    for i, r in enumerate(sdks):
        rid = r.get("id")
        if not rid: errs.append(f"[{i}] missing id"); continue
        if rid in seen: errs.append(f"[{i}] duplicate id {rid} (first at [{seen[rid]}])")
        seen[rid] = i
        if not r.get("name"): errs.append(f"[{rid}] missing name")
        if not r.get("sources"): errs.append(f"[{rid}] sources empty (来源必填)")
        if r.get("category") not in CATEGORIES: errs.append(f"[{rid}] bad category {r.get('category')}")
        if r.get("confidence") not in CONF: errs.append(f"[{rid}] bad confidence {r.get('confidence')}")
        if not isinstance(r.get("safeToBlock"), bool): errs.append(f"[{rid}] safeToBlock not bool")
        for c in r.get("components", []):
            if c.get("type") not in TYPES: errs.append(f"[{rid}] bad component type {c.get('type')}")
            if not c.get("class"): errs.append(f"[{rid}] component missing class")
    if errs:
        print("INVALID:")
        for e in errs[:50]: print(" -", e)
        sys.exit(1)
    print(f"OK: {len(sdks)} rules valid ({path})")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "rules/snapshot.json")
