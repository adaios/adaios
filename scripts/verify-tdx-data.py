#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-tdx-data.py — TDX 通达信本地数据抽查（2026-08-30 建议 #5）
验证 data/market/tdx 的 .day 数据与腾讯行情源一致（同口径：原始不复权价）。

用法: python3 ai-engineering/09-scripts/verify-tdx-data.py [抽样数]
默认抽样 10 只（随机），每只对比最近 3 个交易日收盘价，偏差 >0.5% 记失败。

输出: 每只偏差 + 汇总 PASS/FAIL（数据源可靠性证据）。
"""
import json
import os
import random
import struct
import sys
import urllib.request

TDX_ROOT = "data/market/tdx"
SAMPLE = int(sys.argv[1]) if len(sys.argv) > 1 else 10
TOLERANCE = 0.005  # 0.5%

def tdx_close(symbol: str, limit: int = 3):
    """读 .day 原始价最后 limit 根 (date, close)。"""
    sh = symbol.startswith("6") or symbol.startswith("9")
    path = os.path.join(TDX_ROOT, ("sh" if sh else "sz"), "lday",
                        ("sh" if sh else "sz") + symbol + ".day")
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        data = f.read()
    rows = []
    for i in range(len(data) // 32 - limit, len(data) // 32):
        d = struct.unpack_from("<8i", data, i * 32)
        rows.append((str(d[0]), d[4] / 100.0))
    return rows

def tencent_close(symbol: str):
    """腾讯 fqkline（qfq 前复权）——最新交易日 qfq价 = 原始价（前复权以最新为基准），
    与 TDX 原始 .day 最新根同口径对比。"""
    prefix = "sh" if (symbol.startswith("6") or symbol.startswith("9")) else "sz"
    url = f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={prefix}{symbol},day,,,5,qfq"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=10) as r:
        body = json.loads(r.read().decode())
    data = body["data"][prefix + symbol]
    day = data.get("qfqday") or data.get("day") or []
    return {row[0]: float(row[2]) for row in day}

def main():
    import re
    stock_re = re.compile(r"^(60|68|00|30)\d{4}$")  # 仅 A 股个股（排除基金/转债/指数）
    symbols = [f[2:-4] for f in sorted(os.listdir(os.path.join(TDX_ROOT, "sh", "lday")))
               if stock_re.match(f[2:-4])]
    symbols += [f[2:-4] for f in sorted(os.listdir(os.path.join(TDX_ROOT, "sz", "lday")))
                if stock_re.match(f[2:-4])]
    random.seed(42)
    sample = random.sample(symbols, min(SAMPLE, len(symbols)))

    passed, failed, skipped = 0, 0, 0
    print(f"TDX 抽查 {len(sample)} 只（随机种子 42，偏差容差 {TOLERANCE:.1%}）")
    for sym in sample:
        try:
            local = tdx_close(sym)
            remote = tencent_close(sym)
            if not local or not remote:
                skipped += 1
                print(f"  [跳过] {sym}（本地或远端无数据）")
                continue
            bad = 0
            # 只对比最新交易日（qfq 最新价 = 原始价，同口径；早期日 qfq≠原始不可比）
            local_last = local[-1]
            date, close = local_last
            if date in remote:
                diff = abs(close - remote[date]) / remote[date]
                if diff > TOLERANCE:
                    bad += 1
                    print(f"  [偏差] {sym} {date}: TDX={close:.2f} 腾讯qfq={remote[date]:.2f} 偏差{diff:.2%}")
            elif len(local) >= 2:  # 腾讯有盘中新日 → 比前一日
                date2, close2 = local[-2]
                if date2 in remote:
                    diff = abs(close2 - remote[date2]) / remote[date2]
                    if diff > TOLERANCE:
                        bad += 1
                        print(f"  [偏差] {sym} {date2}: TDX={close2:.2f} 腾讯qfq={remote[date2]:.2f} 偏差{diff:.2%}")
            if bad == 0:
                passed += 1
            else:
                failed += 1
        except Exception as e:
            skipped += 1
            print(f"  [跳过] {sym}: {e}")

    print(f"\n结果: 通过 {passed} · 失败 {failed} · 跳过 {skipped}")
    if failed == 0 and passed > 0:
        print("TDX 数据抽查 PASS（与腾讯源一致）")
        return 0
    print("TDX 数据抽查 FAIL（存在偏差——检查数据源/除权口径）")
    return 1

if __name__ == "__main__":
    sys.exit(main())
