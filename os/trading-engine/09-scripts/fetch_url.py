#!/usr/bin/env python3
"""
fetch_url.py — 抓取网页全文并转为 Markdown
替代 WebFetch，国内网络可用

用法：
  python 09-scripts/fetch_url.py <URL>
  python 09-scripts/fetch_url.py <URL> --browser    # 浏览器渲染模式（处理JS动态加载）

依赖：pip install requests html2text beautifulsoup4 markdownify
      浏览器模式需：npx playwright install chromium
"""

import sys
import re
import json
import html
import argparse
from urllib.parse import urlparse

# ---------------------------------------------------------------------------
# 普通模式：requests + html2text
# ---------------------------------------------------------------------------

def safe_print(text: str, end="\n"):
    """Windows GBK 兼容打印 — stderr 用"""
    sys.stderr.buffer.write((text + end).encode("utf-8", errors="replace"))


def write_stdout(text: str):
    """主输出用 UTF-8 写到 stdout，不依赖终端编码"""
    sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")


def fetch_plain(url: str) -> str:
    import requests

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
        "Accept": (
            "text/html,application/xhtml+xml,application/xml;"
            "q=0.9,image/avif,image/webp,*/*;q=0.8"
        ),
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": "https://www.google.com/",
    }

    try:
        resp = requests.get(url, headers=headers, timeout=30)
        resp.encoding = resp.apparent_encoding or "utf-8"
        resp.raise_for_status()
        return resp.text
    except Exception as e:
        safe_print(f"[WARN] 请求失败: {e}")
        # 尝试简易 fallback: 去掉 https 重试 http
        if url.startswith("https://"):
            safe_print("[INFO] 尝试 http 降级...")
            try:
                http_url = "http://" + url[len("https://"):]
                resp = requests.get(http_url, headers=headers, timeout=30)
                resp.encoding = resp.apparent_encoding or "utf-8"
                resp.raise_for_status()
                return resp.text
            except Exception as e2:
                safe_print(f"[WARN] http 降级也失败: {e2}")
        return ""


# ---------------------------------------------------------------------------
# 浏览器模式：Playwright 渲染 JS 后抓取
# ---------------------------------------------------------------------------

def fetch_with_browser(url: str) -> str:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        safe_print("[WARN] 需要安装 playwright: pip install playwright && npx playwright install chromium")
        return ""

    html_content = ""
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        try:
            page.goto(url, wait_until="networkidle", timeout=60000)
            html_content = page.content()
        except Exception as e:
            safe_print(f"[WARN] 浏览器加载失败: {e}")
        finally:
            browser.close()
    return html_content


# ---------------------------------------------------------------------------
# HTML → Markdown 转换
# ---------------------------------------------------------------------------

def html_to_markdown(html_content: str, url: str = "") -> str:
    import html2text
    from bs4 import BeautifulSoup

    # 清理无用标签
    html_content = re.sub(r'<(script|style|noscript)[^>]*>.*?</\1>', '', html_content, flags=re.DOTALL)

    # 尝试用 BeautifulSoup 定位正文区域
    soup = BeautifulSoup(html_content, "html.parser")

    # 常见中文新闻站点的正文选择器
    content_selectors = [
        "article",                             # <article> 标签
        ".article-content",                    # 通用文章内容
        ".article-body",                       # 通用文章正文
        "#article-content",                    # ID 定位
        "#article_body",                       # ID 定位
        ".article-text",                       # 通用
        ".content-article",                    # 通用
        ".post-content",                       # 通用
        ".main-content",                       # 通用
        ".detail-content",                     # 通用
        '[class*="article-content"]',          # 模糊匹配
        '[class*="article_body"]',
        '[class*="article-text"]',
    ]

    main_content = None
    for selector in content_selectors:
        el = soup.select_one(selector)
        if el and len(el.get_text(strip=True)) > 200:
            main_content = el
            break

    # 如果找不到，退而求其次：取 <body> 里文本最长的 <div>
    if not main_content and soup.body:
        divs = soup.body.find_all("div", recursive=True)
        if divs:
            main_content = max(divs, key=lambda d: len(d.get_text(strip=True)))

    # 用找到的正文区域或全文
    source_html = str(main_content) if main_content else html_content

    h = html2text.HTML2Text()
    h.body_width = 0           # 不自动换行
    h.ignore_links = False
    h.ignore_images = True
    h.ignore_emphasis = False
    h.protect_links = True
    h.skip_internal_links = True
    h.images_to_alt = True
    h.decode_errors = "replace"
    h.unicode_snob = True      # 保留 Unicode 不转义

    md = h.handle(source_html)

    # 清理多余空行
    md = re.sub(r'\n{4,}', '\n\n\n', md)
    md = md.strip()

    # 在开头加上来源 URL
    if url:
        md = f"> 来源：{url}\n\n---\n\n{md}"

    return md


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="抓取网页全文并转为 Markdown")
    parser.add_argument("url", help="要抓取的网页 URL")
    parser.add_argument("--browser", "-b", action="store_true", help="使用浏览器渲染模式（处理 JS 动态加载）")
    parser.add_argument("--raw", "-r", action="store_true", help="输出原始 HTML（不转 Markdown）")
    args = parser.parse_args()

    url = args.url
    if not url.startswith(("http://", "https://")):
        url = "https://" + url

    # 1. 抓取 HTML
    if args.browser:
        html_content = fetch_with_browser(url)
    else:
        html_content = fetch_plain(url)

    if not html_content:
        safe_print("[ERROR] 未能获取页面内容")
        sys.exit(1)

    # 2. 输出
    if args.raw:
        write_stdout(html_content)
    else:
        md = html_to_markdown(html_content, url)
        write_stdout(md)


if __name__ == "__main__":
    main()
