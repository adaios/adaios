#!/usr/bin/env python3
"""
monitor.py — 情报监控调度

角色：定时拉起所有数据源 → 对比上次状态 → 发现新信号 → 更新文档 + 生成简报

调用方式（供定时任务/手动使用）:
  python 09-scripts/monitor.py                # 全量更新
  python 09-scripts/monitor.py --brief        # 仅生成今日简报
  python 09-scripts/monitor.py --source es    # 仅检查东方财富板块
  python 09-scripts/monitor.py --dry-run      # 只查不写

数据流:
  API/页面 → monitor.py 拉取 → 对比 state.json → 有新的？→ 更新 07-monitor/ 文档
                                                          → 生成简报
                                                          → 更新 state.json

依赖: pip install requests
"""

import sys
import os
import json
import time
import re
import argparse
from datetime import datetime, date
from pathlib import Path

# 项目根目录 (脚本在 09-scripts/，根是它的父目录)
ROOT = Path(__file__).resolve().parent.parent
MONITOR_DIR = ROOT / ".monitor"
BRIEFS_DIR = MONITOR_DIR / "briefs"
RESEARCH_DIR = ROOT / "12-research" / "07-monitor"
STATE_FILE = MONITOR_DIR / "state.json"

# 确保目录存在
for d in [MONITOR_DIR, BRIEFS_DIR, RESEARCH_DIR]:
    d.mkdir(parents=True, exist_ok=True)

# 加载东方财富脚本
sys.path.insert(0, str(ROOT / "09-scripts"))
try:
    from fetch_eastmoney import cmd_sector_top, api_get, HEADERS
    EASTMONEY_AVAILABLE = True
except ImportError:
    EASTMONEY_AVAILABLE = False

try:
    import requests
except ImportError:
    requests = None

# ---------------------------------------------------------------------------
# 状态管理
# ---------------------------------------------------------------------------

def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return _default_state()
    return _default_state()


def _default_state():
    return {
        "version": 1,
        "sources": {
            "eastmoney_sector": {"last_check": None, "last_findings": []},
            "eastmoney_quotes": {"last_check": None, "last_findings": []},
            "stcn_announcements": {"last_check": None, "last_findings": []},
        },
        "seen_news": {},
        "stats": {"total_checks": 0, "total_briefs": 0},
    }


def save_state(state):
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def seen_key(item):
    """生成新闻去重指纹"""
    title = item.get("title", "")
    url = item.get("url", "")
    return f"{title}|{url}"


def is_seen(state, source, key):
    return key in state.get("seen_news", {}).get(source, {})


def mark_seen(state, source, key, title=""):
    if source not in state["seen_news"]:
        state["seen_news"][source] = {}
    state["seen_news"][source][key] = {
        "seen_at": datetime.now().isoformat(),
        "title": title[:80],
    }
    # 清理太旧的记录（保留最近200条）
    if len(state["seen_news"][source]) > 200:
        keys = sorted(state["seen_news"][source].keys(),
                      key=lambda k: state["seen_news"][source][k]["seen_at"])
        for k in keys[:-200]:
            del state["seen_news"][source][k]


# ---------------------------------------------------------------------------
# 数据源采集
# ---------------------------------------------------------------------------

def check_sectors(state):
    """东方财富板块涨幅榜 — 发现异动行业"""
    if not EASTMONEY_AVAILABLE:
        return []

    findings = []
    try:
        # 拉概念板块涨幅前20
        resp = api_get("https://push2.eastmoney.com/api/qt/clist/get", {
            "pn": 1, "pz": 20,
            "fs": "m:90+t:2",
            "fields": "f12,f14,f3,f4,f62",
            "po": 1, "fid": "f3",
        })
        if not resp:
            return findings

        raw = resp.json()
        if raw.get("rc") != 0 or not raw.get("data", {}).get("diff"):
            return findings

        for v in raw["data"]["diff"].values():
            pct = v.get("f3", 0) / 100
            name = v.get("f14", "")
            code = v.get("f12", "")
            inflow = v.get("f62", 0)

            # 关注涨跌幅异常（>3%）的板块，且跟 watchlist 相关的
            key = f"sector:{code}"
            if abs(pct) >= 3 and not is_seen(state, "sector", key):
                findings.append({
                    "type": "sector_alert",
                    "code": code,
                    "title": f"{name} {'📈' if pct>0 else '📉'} {pct:+.2f}%",
                    "detail": f"主力净流入: {inflow/10000:.0f}万" if inflow else "",
                    "pct": pct,
                })
                mark_seen(state, "sector", key, f"{name} {pct:+.2f}%")

    except Exception as e:
        pass

    return findings


def check_announcements(state):
    """证券时报首页 — 跟踪IPO/合同等公告"""
    if not requests:
        return []

    findings = []
    try:
        resp = requests.get("https://www.stcn.com/",
                            headers={
                                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            }, timeout=15)
        if resp.status_code != 200:
            return findings

        # 提取公告链接
        html = resp.text
        links = re.findall(
            r'<a[^>]*href="(https?://[^"]*stcn[^"]*)"[^>]*>(.*?)</a>',
            html
        )

        for url, title_html in links[:30]:
            title = re.sub(r'<[^>]+>', '', title_html).strip()
            if not title or len(title) < 8:
                continue

            key = seen_key({"title": title, "url": url})
            if not is_seen(state, "stcn", key):
                findings.append({
                    "type": "announcement",
                    "title": title,
                    "url": url,
                    "source": "stcn",
                })
                mark_seen(state, "stcn", key, title)

    except Exception:
        pass

    return findings


# ---------------------------------------------------------------------------
# 简报生成
# ---------------------------------------------------------------------------

def generate_brief(state, findings):
    """生成今日简报"""
    today = date.today().isoformat()
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M")

    lines = []
    lines.append(f"# 📡 情报简报 — {now_str}")
    lines.append("")
    lines.append("> 自动扫描结果，仅展示新的或异常信号")
    lines.append("")

    if not findings:
        lines.append("本次扫描未发现新的异常信号。")
        lines.append("")

    # 分类展示
    sector_alerts = [f for f in findings if f["type"] == "sector_alert"]
    announcements = [f for f in findings if f["type"] == "announcement"]

    if sector_alerts:
        lines.append("## 📊 板块异动")
        lines.append("")
        lines.append("| 板块 | 涨跌幅 | 主力净流入 |")
        lines.append("|:-----|:------|:----------|")
        for f in sorted(sector_alerts, key=lambda x: -abs(x["pct"])):
            lines.append(f"| {f['title']} | {f['pct']:+.2f}% | {f['detail']} |")
        lines.append("")

    if announcements:
        lines.append("## 📰 新公告")
        lines.append("")
        for f in announcements[:10]:
            lines.append(f"- [{f['title']}]({f['url']})")
        lines.append("")

    # 统计
    lines.append(f"---")
    lines.append(f"*检查次数: {state['stats']['total_checks']} | 简报次数: {state['stats']['total_briefs'] + 1}*")

    content = "\n".join(lines)

    # 写入今天简报
    brief_file = BRIEFS_DIR / f"brief-{today}.md"
    brief_file.write_text(content, encoding="utf-8")

    # 更新RESEARCH_DIR下的latest_brief.md（引用锚点）
    latest_file = RESEARCH_DIR / "latest_brief.md"
    latest_file.write_text(content, encoding="utf-8")

    return content


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="情报监控调度")
    parser.add_argument("--dry-run", action="store_true", help="只查不写")
    parser.add_argument("--brief", action="store_true", help="仅生成今日简报（不上拉取）")
    parser.add_argument("--source", "-s", choices=["es", "stcn", "all"],
                        default="all", help="指定数据源")
    args = parser.parse_args()

    state = load_state()

    if args.brief:
        content = generate_brief(state, [])
        print(content)
        return

    # 采集
    all_findings = []

    if args.source in ("all", "es"):
        print("[monitor] 检查东方财富板块...")
        findings = check_sectors(state)
        all_findings.extend(findings)
        print(f"  -> {len(findings)} 条新信号")

    if args.source in ("all", "stcn"):
        print("[monitor] 检查证券时报公告...")
        findings = check_announcements(state)
        all_findings.extend(findings)
        print(f"  -> {len(findings)} 条新公告")

    state["stats"]["total_checks"] += 1

    if all_findings and not args.dry_run:
        print("[monitor] 发现新信号，生成简报...")
        content = generate_brief(state, all_findings)
        sys.stdout.buffer.write((content[:500] + '\n').encode('utf-8', errors='replace'))
        state["stats"]["total_briefs"] += 1
    else:
        print("[monitor] 无新信号或 dry-run 模式")

    save_state(state)
    print(f"[monitor] 完成 (总检查: {state['stats']['total_checks']} 次)")


if __name__ == "__main__":
    main()
