#!/usr/bin/env python3
"""
search_web.py — 搜索网页（替代 WebSearch）
国内网络可用，无需 API Key

用法：
  python 09-scripts/search_web.py <关键词>
  python 09-scripts/search_web.py <关键词> -n 20    # 返回 20 条结果
  python 09-scripts/search_web.py <关键词> --site eastmoney.com  # 指定站点
  python 09-scripts/search_web.py <关键词> -o /tmp/result.json   # 输出 JSON

依赖：pip install requests beautifulsoup4
"""

import sys
import re
import json
import html
import time
import argparse
from urllib.parse import quote_plus, urlparse

# ---------------------------------------------------------------------------
# 搜索引擎后端
# ---------------------------------------------------------------------------

def search_duckduckgo(query: str, num_results: int = 10) -> list:
    """DuckDuckGo HTML 版搜索，无需 API Key"""
    import requests
    from bs4 import BeautifulSoup

    results = []
    url = f"https://html.duckduckgo.com/html/?q={quote_plus(query)}"

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
    }

    try:
        resp = requests.get(url, headers=headers, timeout=15)
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, "html.parser")

        for item in soup.select(".result")[:num_results]:
            title_el = item.select_one(".result__title a")
            snippet_el = item.select_one(".result__snippet")

            if not title_el:
                continue

            result_url = title_el.get("href", "")
            # DuckDuckGo 的链接是重定向格式，需要提取真实 URL
            if "uddg=" in result_url:
                from urllib.parse import parse_qs, urlparse as up
                parsed = up(result_url)
                qs = parse_qs(parsed.query)
                result_url = qs.get("uddg", [""])[0]

            title = title_el.get_text(strip=True)
            snippet = snippet_el.get_text(strip=True) if snippet_el else ""

            results.append({
                "title": title,
                "url": result_url,
                "snippet": snippet,
            })

    except Exception as e:
        sys.stderr.buffer.write(f"[WARN] DuckDuckGo 搜索失败: {e}\n".encode("utf-8", errors="replace"))

    return results


def search_bing(query: str, num_results: int = 10) -> list:
    """Bing 搜索（备用方案）"""
    import requests
    from bs4 import BeautifulSoup

    results = []
    url = f"https://www.bing.com/search?q={quote_plus(query)}&count={num_results}"

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
    }

    try:
        resp = requests.get(url, headers=headers, timeout=15)
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, "html.parser")

        for item in soup.select(".b_algo")[:num_results]:
            title_el = item.select_one("h2 a")
            snippet_el = item.select_one(".b_caption p")

            if not title_el:
                continue

            result_url = title_el.get("href", "")
            # Bing 有时用 https://www.bing.com/... 的重定向链接
            if result_url.startswith("https://www.bing.com/"):
                # 跳过 Bing 自身页面
                continue

            title = title_el.get_text(strip=True)
            snippet = snippet_el.get_text(strip=True) if snippet_el else ""

            # 清理 HTML 实体
            title = html.unescape(title)
            snippet = html.unescape(snippet)

            results.append({
                "title": title,
                "url": result_url,
                "snippet": snippet,
            })

    except Exception as e:
        sys.stderr.buffer.write(f"[WARN] Bing 搜索失败: {e}\n".encode("utf-8", errors="replace"))

    return results


# ---------------------------------------------------------------------------
# 显示
# ---------------------------------------------------------------------------

def safe_print(text: str, end="\n"):
    """Windows GBK 兼容打印 — 直接写 UTF-8 到 stdout"""
    sys.stdout.buffer.write((text + end).encode("utf-8", errors="replace"))


def print_results(results: list):
    if not results:
        safe_print("-- 没有找到结果")
        return

    safe_print(f"\n== 共 {len(results)} 条结果 ==\n")

    for i, r in enumerate(results, 1):
        safe_print(f"[{i}] {r['title']}")
        safe_print(f"    链接: {r['url']}")
        safe_print(f"    摘要: {r['snippet'][:200]}")
        safe_print("")


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="搜索网页（替代 WebSearch）")
    parser.add_argument("query", nargs="+", help="搜索关键词")
    parser.add_argument("-n", "--num", type=int, default=10, help="返回结果数量（默认 10）")
    parser.add_argument("--site", "-s", help="限定站点，如 eastmoney.com")
    parser.add_argument("--engine", "-e", choices=["duckduckgo", "bing"], default="bing",
                        help="搜索引擎（默认 bing，国内网络推荐）")
    parser.add_argument("--json", "-o", help="输出到 JSON 文件")
    parser.add_argument("--silent", action="store_true", help="静默模式，只输出精简结果")

    args = parser.parse_args()

    # 拼接多词查询
    query = " ".join(args.query)
    if args.site:
        query += f" site:{args.site}"

    # 搜索
    if args.engine == "bing":
        results = search_bing(query, args.num)
    else:
        results = search_duckduckgo(query, args.num)

    # 输出
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        safe_print(f"[OK] 结果已保存到 {args.json}")

    if args.silent:
        # 精简模式：只输出 URL 列表
        for r in results:
            safe_print(r["url"])
    else:
        print_results(results)


if __name__ == "__main__":
    main()
