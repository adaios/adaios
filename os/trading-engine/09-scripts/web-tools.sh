#!/bin/bash
# web-tools.sh — 搜索 + 抓取一键封装
# 用法:
#   source 09-scripts/web-tools.sh
#   search 宇树科技 IPO
#   fetch https://finance.eastmoney.com/a/12345.html
#   search 宇树科技 -n 20 | head -5 | while read url; do fetch "$url"; done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

search() {
    python "$SCRIPT_DIR/search_web.py" "$@" 2>/dev/null
}

fetch() {
    python "$SCRIPT_DIR/fetch_url.py" "$@" 2>/dev/null
}

# 组合：搜索并自动抓取第一条结果
search_and_fetch() {
    local url
    url=$(python "$SCRIPT_DIR/search_web.py" "$@" --silent -n 1 2>/dev/null)
    if [ -n "$url" ]; then
        echo ">>> 抓取: $url"
        python "$SCRIPT_DIR/fetch_url.py" "$url" 2>/dev/null
    else
        echo "没有找到结果" >&2
    fi
}

echo "✅ web-tools 已加载"
echo "    search <关键词>     — 搜索"
echo "    fetch <URL>         — 抓取全文"
echo "    search_and_fetch     — 搜索并打开第一条"
