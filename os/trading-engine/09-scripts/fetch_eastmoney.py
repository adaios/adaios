#!/usr/bin/env python3
"""
fetch_eastmoney.py — 东方财富 API 封装

无需 API Key，零成本，覆盖 A 股行情、板块、新闻、公告。

用法:
  # ---- 行情 ----
  python 09-scripts/fetch_eastmoney.py quote 688825              # 个股行情（代码）
  python 09-scripts/fetch_eastmoney.py quote 300750              # 创业板股票
  python 09-scripts/fetch_eastmoney.py sector top 10             # 概念板块涨幅榜前10
  python 09-scripts/fetch_eastmoney.py sector industry top 5     # 行业板块涨幅榜前5
  python 09-scripts/fetch_eastmoney.py sector members BK0988     # 板块成分股（BK代码）

  # ---- 新闻/资讯 ----
  python 09-scripts/fetch_eastmoney.py search 长鑫科技            # 搜索财经新闻
  python 09-scripts/fetch_eastmoney.py search 商业航天 -n 10     # 指定返回条数

  # ---- 板块BK代码查询 ----
  python 09-scripts/fetch_eastmoney.py bk-list                    # 列出所有板块及BK代码

依赖：pip install requests
"""

import sys
import json
import time
import argparse
from urllib.parse import quote_plus

try:
    import requests
except ImportError:
    print("[ERROR] 需要 requests 库: pip install requests", file=sys.stderr)
    sys.exit(1)

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Referer": "https://quote.eastmoney.com/",
}

# 市场代码映射
MARKET_MAP = {
    1: "上海", 0: "深圳",
}

# 常用字段映射
FIELDS_STOCK = {
    "f43": "最新价", "f44": "最高", "f45": "最低", "f46": "今开",
    "f47": "昨收", "f48": "成交量", "f50": "成交额",
    "f57": "代码", "f58": "名称",
    "f170": "涨跌幅",
    "f15": "最高", "f16": "最低", "f17": "今开", "f18": "昨收",
    "f3": "涨跌幅", "f4": "涨跌额", "f62": "主力净流入",
    "f6": "总市值", "f7": "流通市值",
}


def safe_print(text, end="\n"):
    """兼容 GBK 终端的 UTF-8 输出"""
    sys.stdout.buffer.write((text + end).encode("utf-8", errors="replace"))


def api_get(url, params, retries=3):
    """带重试的 GET 请求"""
    for i in range(retries):
        try:
            r = requests.get(url, params=params, headers=HEADERS, timeout=15)
            if r.status_code == 200:
                return r
            # 400/403/429 → 等待后重试
            time.sleep(2 * (i + 1))
        except requests.RequestException as e:
            if i == retries - 1:
                raise
            time.sleep(2 * (i + 1))
    return None


# ---------------------------------------------------------------------------
# 检测市场代码
# ---------------------------------------------------------------------------

def detect_market(code: str) -> int:
    """根据股票代码判断市场"""
    code = code.strip()
    if code.startswith("6") or code.startswith("688") or code.startswith("689"):
        return 1  # 上海
    if code.startswith("0") or code.startswith("3") or code.startswith("2"):
        return 0  # 深圳
    if code.startswith("4") or code.startswith("8"):
        return 0  # 北交所（深圳市场编码）
    return 1  # 默认上海


# ---------------------------------------------------------------------------
# 个股行情
# ---------------------------------------------------------------------------

def cmd_quote(code: str):
    secid = f"{detect_market(code)}.{code}"
    resp = api_get("https://push2.eastmoney.com/api/qt/stock/get", {
        "secid": secid,
        "fields": "f43,f44,f45,f46,f47,f48,f50,f57,f58,f170,f15,f16,f17,f18,f6,f7",
    })
    if not resp:
        safe_print("-- 请求失败")
        return
    raw = resp.json()
    if raw.get("rc") != 0 or not raw.get("data"):
        safe_print("-- 无数据（可能非交易时间或代码错误）")
        return

    d = raw["data"]
    safe_print(f"\n📈 {d.get('f58', '?')} ({d.get('f57', code)})")
    safe_print(f"━━━━━━━━━━━━━━━━━━━━━━")
    safe_print(f"  最新价: {d.get('f43', '-')}  |  涨跌幅: {d.get('f170', 0)/100:.2f}%")
    safe_print(f"  最高: {d.get('f44', '-')}  |  最低: {d.get('f45', '-')}")
    safe_print(f"  今开: {d.get('f46', '-')}  |  昨收: {d.get('f47', '-')}")
    safe_print(f"  成交量: {d.get('f48', 0)}  |  成交额: {d.get('f50', 0)}")


# ---------------------------------------------------------------------------
# 板块数据
# ---------------------------------------------------------------------------

_SECTOR_MAP = {
    "concept": "m:90+t:2",   # 概念板块
    "industry": "m:90+t:3",  # 行业板块
    "area": "m:90+t:1",      # 地域板块
}


def cmd_sector_top(n: int, sector_type="concept"):
    """板块涨幅榜"""
    fs = _SECTOR_MAP.get(sector_type, _SECTOR_MAP["concept"])
    resp = api_get("https://push2.eastmoney.com/api/qt/clist/get", {
        "pn": 1, "pz": n,
        "fs": fs,
        "fields": "f12,f14,f3,f4,f62",
        "po": 1,  # 降序
        "fid": "f3",  # 按涨幅排序
    })
    if not resp:
        safe_print("-- 请求失败")
        return

    raw = resp.json()
    if raw.get("rc") != 0 or not raw.get("data", {}).get("diff"):
        safe_print("-- 无数据")
        return

    type_name = {"concept": "概念", "industry": "行业", "area": "地域"}.get(sector_type, sector_type)
    safe_print(f"\n🏆 {type_name}板块涨幅榜 TOP {n}")
    safe_print(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    for k, v in sorted(raw["data"]["diff"].items(), key=lambda x: -abs(x[1].get("f3", 0))):
        pct = v.get("f3", 0) / 100
        inflow = v.get("f62", 0)
        inflow_str = f" 主力净流入: {inflow/10000:.0f}万" if inflow > 10000 else ""
        safe_print(f"  {v['f12']} {v['f14']}  {'📈' if pct>0 else '📉'} {pct:+.2f}%{inflow_str}")


def cmd_sector_members(bk_code: str):
    """板块成分股"""
    resp = api_get("https://push2.eastmoney.com/api/qt/clist/get", {
        "pn": 1, "pz": 50,
        "fs": f"b:{bk_code}",
        "fields": "f12,f14,f3,f4,f7,f62,f170",
        "po": 1,
        "fid": "f3",
    })
    if not resp:
        safe_print("-- 请求失败")
        return

    raw = resp.json()
    if raw.get("rc") != 0 or not raw.get("data", {}).get("diff"):
        safe_print(f"-- 板块 {bk_code} 无数据（BK代码是否正确？）")
        return

    safe_print(f"\n📋 板块 {bk_code} 成分股（按涨跌幅排序）")
    safe_print(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    for k, v in sorted(raw["data"]["diff"].items(), key=lambda x: -abs(x[1].get("f3", 0))):
        pct = v.get("f170", v.get("f3", 0)) / 100
        safe_print(f"  {v['f12']} {v['f14']}  {'📈' if pct>0 else '📉'} {pct:+.2f}%")


def cmd_bk_list():
    """列出所有概念板块及其BK代码"""
    resp = api_get("https://push2.eastmoney.com/api/qt/clist/get", {
        "pn": 1, "pz": 600,  # 概念板块通常 < 500
        "fs": "m:90+t:2",
        "fields": "f12,f14,f3,f4",
        "po": 1,
        "fid": "f3",
    })
    if not resp:
        safe_print("-- 请求失败")
        return

    raw = resp.json()
    if raw.get("rc") != 0 or not raw.get("data", {}).get("diff"):
        safe_print("-- 无数据")
        return

    # 按BK代码排序展示
    safe_print(f"\n📚 概念板块全量（共 {raw['data']['total']} 个）")
    safe_print(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    # 按涨幅排序取前50
    items = sorted(raw["data"]["diff"].items(), key=lambda x: -abs(x[1].get("f3", 0)))
    for k, v in items:
        pct = v.get("f3", 0) / 100
        safe_print(f"  {v['f12']}  {v['f14']}  {pct:+.2f}%")


# ---------------------------------------------------------------------------
# 新闻搜索
# ---------------------------------------------------------------------------

def cmd_search(keyword: str, n: int = 5):
    """搜索东方财富财经新闻"""
    # 编码参数
    resp = api_get("https://search-api-web.eastmoney.com/search/jsonp", {
        "param": keyword,
        "type": "8197",
        "page": 1,
        "pageSize": n,
    })
    if not resp:
        safe_print("-- 请求失败")
        return

    # 返回的是 JSONP 格式，需要去掉外层
    text = resp.text
    if text.startswith("jQuery"):
        text = text[text.find("(")+1:text.rfind(")")]
    try:
        raw = json.loads(text)
    except json.JSONDecodeError:
        safe_print("-- 解析失败")
        return

    if not raw.get("result"):
        safe_print(f"-- 未找到「{keyword}」相关新闻")
        return

    # 新闻在不同字段，尝试多个可能的位置
    news_list = (
        raw["result"].get("news", [])
        or raw["result"].get("list", [])
        or []
    )
    if not news_list:
        safe_print(f"-- 未找到新闻内容（接口返回结构: {json.dumps(raw, ensure_ascii=False)[:200]}）")
        return

    safe_print(f"\n📰 「{keyword}」财经新闻（来源: 东方财富）")
    safe_print(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    for i, item in enumerate(news_list[:n], 1):
        title = item.get("title") or item.get("art_title") or item.get("Art_Title", "")
        date = item.get("date") or item.get("art_date") or item.get("Art_Date", "")
        url = item.get("url") or item.get("art_url") or item.get("Art_Url", "")
        safe_print(f"  [{i}] {title}")
        safe_print(f"      {date}")
        if url:
            safe_print(f"      {url}")
        safe_print("")


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="东方财富 API 工具 — 行情/板块/新闻",
        formatter_class=argparse.RawTextHelpFormatter,
    )
    sub = parser.add_subparsers(dest="cmd")

    # quote
    p_quote = sub.add_parser("quote", help="个股行情")
    p_quote.add_argument("code", help="股票代码，如 688825")

    # sector
    p_sector = sub.add_parser("sector", help="板块数据")
    p_sector.add_argument("action", choices=["top", "members", "list"],
                          help="top=涨幅榜 members=成分股 list=全量板块")
    p_sector.add_argument("param", nargs="?", default="10",
                          help="top:数量(默认10) / members:BK代码")
    p_sector.add_argument("--type", "-t", choices=["concept", "industry", "area"],
                          default="concept", help="板块类型（默认 concept）")

    # search
    p_search = sub.add_parser("search", help="搜索财经新闻")
    p_search.add_argument("keyword", help="搜索关键词")
    p_search.add_argument("-n", type=int, default=5, help="返回条数（默认5）")

    # bk-list
    sub.add_parser("bk-list", help="列出所有概念板块BK代码")

    args = parser.parse_args()

    if args.cmd == "quote":
        cmd_quote(args.code)
    elif args.cmd == "sector":
        if args.action == "top":
            cmd_sector_top(int(args.param), args.type)
        elif args.action == "members":
            cmd_sector_members(args.param.upper())
        elif args.action == "list":
            cmd_bk_list()
    elif args.cmd == "search":
        cmd_search(args.keyword, args.n)
    elif args.cmd == "bk-list":
        cmd_bk_list()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
