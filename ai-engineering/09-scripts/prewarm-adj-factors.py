#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
prewarm-adj-factors.py — 全 A 复权因子预热（2026-08-30 建议 #7，可选）
把全 A 股票的除权因子预先拉取到 data/market/adj/factors/（对齐 AdjFactorRepository
的 FactorFile JSON 格式）——减少用户标注时首次拉取的网络等待。

⚠️ 谨慎使用：全 A ~9000 只 × 东财接口 = 大量请求，可能触发风控。
   默认分批 + 限速（每批 50 只 + 0.5s 间隔 → 约 2-3 分钟）。
   懒加载（标注哪只拉哪只）已覆盖主要场景；预热仅用于「想提前备好」时。

用法: python3 ai-engineering/09-scripts/prewarm-adj-factors.py [最大只数，默认 2000]
"""
import json
import os
import sys
import time
import urllib.request
import urllib.parse

ADJ_ROOT = "data/market/adj/factors"
MAX_COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
API = ("https://datacenter-web.eastmoney.com/api/data/v1/get"
       "?reportName=RPT_SHAREBONUS_DET&columns=ALL&pageSize=200"
       "&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1")
TDX_ROOT = "data/market/tdx"
IMPORT_RE = None  # 仅 A 股个股（与 verify 脚本一致）

def list_stocks():
    import re
    stock_re = re.compile(r"^(60|68|00|30)\d{4}$")
    out = []
    for mkt in ("sh", "sz"):
        d = os.path.join(TDX_ROOT, mkt, "lday")
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            sym = f[2:-4]
            if stock_re.match(sym):
                out.append(sym)
    return out

def fetch(symbol):
    filter_ = urllib.parse.quote(f'(SECURITY_CODE="{symbol}")')
    url = API + "&filter=" + filter_
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=10) as r:
        body = json.loads(r.read().decode())
    events = []
    for n in body.get("result", {}).get("data", []):
        date_text = n.get("EX_DIVIDEND_DATE", "")
        profile = n.get("IMPL_PLAN_PROFILE", "")
        if not date_text or not profile:
            continue
        import re
        cash = re.search(r"派([\d.]+)", profile)
        send = re.search(r"送(\d+)", profile)
        transfer = re.search(r"转(\d+)", profile)
        ev = {
            "exDate": date_text[:10],
            "cashPerShare": float(cash.group(1)) / 10 if cash else 0.0,
            "sendPerShare": int(send.group(1)) / 10.0 if send else 0.0,
            "transferPerShare": int(transfer.group(1)) / 10.0 if transfer else 0.0,
            "profile": profile,
        }
        if ev["cashPerShare"] <= 0 and ev["sendPerShare"] <= 0 and ev["transferPerShare"] <= 0:
            continue
        events.append(ev)
    if events:
        os.makedirs(ADJ_ROOT, exist_ok=True)
        with open(os.path.join(ADJ_ROOT, symbol + ".json"), "w", encoding="utf-8") as f:
            json.dump({"symbol": symbol, "updatedAt": time.strftime("%Y-%m-%d"),
                       "events": events}, f, ensure_ascii=False, indent=1)
    return len(events)

def main():
    stocks = list_stocks()
    print(f"全 A 个股 {len(stocks)} 只，预热前 {MAX_COUNT} 只（分批 50 + 0.5s 限速）")
    done, with_events = 0, 0
    for i, sym in enumerate(stocks[:MAX_COUNT]):
        try:
            n = fetch(sym)
            if n:
                with_events += 1
        except Exception as e:
            print(f"  [跳过] {sym}: {e}")
        done += 1
        if done % 50 == 0:
            print(f"  进度 {done}/{min(MAX_COUNT, len(stocks))}（有除权 {with_events}）")
            time.sleep(0.5)
    print(f"预热完成：{done} 只，其中 {with_events} 只有除权记录")
    print(f"因子文件：{ADJ_ROOT}/（{len(os.listdir(ADJ_ROOT)) if os.path.isdir(ADJ_ROOT) else 0} 个）")

if __name__ == "__main__":
    main()
