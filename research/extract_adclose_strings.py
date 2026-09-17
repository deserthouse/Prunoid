import re, sys, zipfile

# 从 dex 提取可打印字符串（MUTF-8 粗提），供人工/脚本筛选 SDK 特征
z = zipfile.ZipFile(sys.argv[1] if len(sys.argv) > 1 else "reference_apk/AdClose_4.3.2.apk")
out = open("research/adclose_dex_strings.txt", "w", encoding="utf-8")
pat = re.compile(r"[\x20-\x7e\u4e00-\u9fff]{6,}")
n = 0
for name in z.namelist():
    if not name.endswith(".dex"):
        continue
    data = z.read(name)
    # L 类名形态优先（Lcom/foo/Bar;），再补普通长字符串
    for m in set(pat.findall(data.decode("utf-8", "ignore"))):
        out.write(m + "\n")
        n += 1
out.close()
print(f"{n} strings -> research/adclose_dex_strings.txt")
