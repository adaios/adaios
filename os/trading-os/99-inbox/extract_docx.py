import zipfile, re, os, glob

path = "D:/Projects/adai-trading-os/00-temp/股探报告文章/"
outdir = "D:/Projects/adai-trading-os/99-inbox/"
files = sorted(glob.glob(path + "*.docx"))

for f in files:
    name = os.path.basename(f).replace(".docx","")[:40]
    z = zipfile.ZipFile(f)
    xml = z.read("word/document.xml").decode("utf-8", errors="replace")
    texts = re.findall(r"<w:t[^>]*>(.*?)</w:t>", xml)
    content = "".join(texts)
    outpath = outdir + name + ".txt"
    with open(outpath, "w", encoding="utf-8") as o:
        o.write(content)
    print(f"OK: {outpath}  ({len(content)} chars)")
