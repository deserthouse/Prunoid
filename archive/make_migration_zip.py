import os
import zipfile
from datetime import date

# 项目根 = 本脚本所在 archive/ 的上一级
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = os.path.join("archive", f"SDK-Pruner_migration_{date.today():%Y%m%d}.zip")
os.makedirs("archive", exist_ok=True)

# 刷新语义：archive/ 内只保留当日最新一份打包件，旧包（含 SDK-Slayer 时代命名）清除
keep = os.path.basename(out)
for old in os.listdir("archive"):
    if old.endswith(".zip") and old != keep:
        os.remove(os.path.join("archive", old))

n = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in (".git", "archive", "build", ".gradle", ".idea", ".cxx", "captures")]
        for f in files:
            if f == "local.properties":
                continue
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, "."))
            n += 1
print(f"packed {n} files -> {out}, size {os.path.getsize(out)/1048576:.1f} MB")
