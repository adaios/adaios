#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync-stock-names.py — 全 A 股票名称表同步（2026-08-31 批量导入兜底）
东财 suggest 对部分名称（昂立康/百普塞斯等）返回空——本地精确名称表兜底。

用法: python3 ai-engineering/09-scripts/sync-stock-names.py
产出: data/market/names.json [{"symbol":"600519","name":"贵州茅台"}, ...]（沪深 A 股 ~5554）
"""
import json
import time
import urllib.request

OUT = "data/market/names.json"
API = ("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
       "Market_Center.getHQNodeData?page={page}&num=100&node=hs_a")

def fetch(page):
    for attempt in range(3):
        try:
            req = urllib.request.Request(API.format(page=page),
                                         headers={"User-Agent": "Mozilla/5.0",
                                                  "Referer": "https://finance.sina.com.cn"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            print(f"  页 {page} 第 {attempt+1} 次失败: {e}")
            time.sleep(2)
    return []

def main():
    names = []
    for p in range(1, 60):  # 新浪 hs_a 沪深 A 约 5500 只，每页 100 → 最多 55 页
        rows = fetch(p)
        if not rows:
            break
        for x in rows:
            sym = x.get("symbol", "")
            name = x.get("name", "")
            # 过滤北交所（bj 前缀）——只留沪深 A
            if sym.startswith("sh") or sym.startswith("sz"):
                names.append({"symbol": sym[2:], "name": name})
        print(f"  页 {p}：累计 {len(names)}")
        time.sleep(0.3)
        if len(rows) < 100:
            break
    import os
    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(names, f, ensure_ascii=False)
    print(f"全 A 名称表完成：{len(names)} 只 → {OUT}")
    # 抽验
    by_name = {n["name"]: n["symbol"] for n in names}
    for test in ["昂立康", "百普塞斯", "贵州茅台", "华纳药厂"]:
        print(f"  抽验 {test} → {by_name.get(test)}")

if __name__ == "__main__":
    main()
