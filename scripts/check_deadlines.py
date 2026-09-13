#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AdaiOS 到期红线检查（单一事实源）——2026-09-14 建

由来：盘点发现「到期型固定动作」只写在文档里，而文档不会主动叫人
      （REVIEW P2-APNs5 原话：「这条要落到日历才算闭环」；P1-合规1 公安备案
      一度连 task-log / 快照都没有，新会话完全看不到）。

本文件是**唯一事实源**：日期只在这里写一次，三个出口共用——
  ① 人工查看   bash: python3 scripts/check_deadlines.py
  ② 日历提醒   python3 scripts/check_deadlines.py --ics [输出路径]
               默认 ~/Desktop/adaios-deadlines.ics，双击导入 macOS 日历（提前 30 天 + 7 天响铃）
  ③ 每周审查   python3 scripts/check_deadlines.py --one-line（weekly-audit.sh W6 调用）

新增到期项：只改下面 DEADLINES / UNSCHEDULED 两处，然后重跑 --ics 覆盖导入。
"""

import sys
import datetime
import pathlib

TODAY = datetime.date.today()

# ⚠️ 红线阈值：剩余天数 ≤ 该值即告警
WARN_DAYS = 30
CRIT_DAYS = 7

# ── 已登记到期项（date 为到期当天）──────────────────────────────────
DEADLINES = [
    dict(
        date="2026-09-30",
        title="公安联网备案（ICP 备案后 30 天内）",
        impact="逾期可能被要求整改 / 影响站点合规",
        action="见 docs/deployment/icp-filing.md §6（beian.mps.gov.cn 提交）；通过后把公安备案号挂站点底部",
        ref="REVIEW P1-合规1",
    ),
    dict(
        date="2027-01-30",
        title="域名 adaiadai.com 到期（DNSPod）",
        impact="域名失效 → web / API / PWA / iOS App 全部不可达",
        action="DNSPod 控制台续费",
        ref="docs/reference/status.md",
    ),
    dict(
        date="2027-09-13",
        title="Apple Developer Program 付费账号到期（Team 4G3D37YKSB）",
        impact="当天 App 打不开、推送与证书失效（＝新的「打不开日」）",
        action="Apple Developer 续费",
        ref="REVIEW P2-APNs5",
    ),
    dict(
        date="2027-09-13",
        title="iOS 描述文件（Provisioning Profile）到期",
        impact="App 当天打不开",
        action="续费后重新签名打包装机",
        ref="docs/reference/status.md",
    ),
]

# ── 未登记项：连日期都还不知道，比已知到期更该处理 ──────────────────
UNSCHEDULED = [
    dict(
        title="生产服务器（82.156.111.146）续费日",
        action="腾讯云控制台查到到期日后，补进本脚本 DEADLINES",
        ref="docs/reference/status.md（自述「服务器续费日未登记」）",
    ),
]


def parse(d):
    return datetime.date.fromisoformat(d["date"])


def days_left(d):
    return (parse(d) - TODAY).days


def mark(n):
    if n < 0:
        return "❌ 已过期"
    if n <= CRIT_DAYS:
        return "🔥 紧急"
    if n <= WARN_DAYS:
        return "⚠️ 临近"
    return "✅ 尚早"


def report():
    rows = sorted(DEADLINES, key=parse)
    print(f"═══ AdaiOS 到期红线（今天 {TODAY.isoformat()}）═══")
    for d in rows:
        n = days_left(d)
        left = "已过期 %d 天" % (-n) if n < 0 else "%d 天" % n
        print(f"{mark(n)}  剩 {left:<10} {d['date']}  {d['title']}")
        print(f"           后果：{d['impact']}")
        print(f"           处理：{d['action']}   [{d['ref']}]")
    print("")
    if UNSCHEDULED:
        print("⚠️ 未登记（连到期日都不知道，建议先去查）：")
        for u in UNSCHEDULED:
            print(f"   · {u['title']}")
            print(f"     处理：{u['action']}   [{u['ref']}]")
    urgent = [d for d in rows if days_left(d) <= WARN_DAYS]
    print("")
    if urgent:
        print(f"👉 {len(urgent)} 项在 {WARN_DAYS} 天内到期，优先处理第一条：{urgent[0]['title']}")
    else:
        print(f"👉 {WARN_DAYS} 天内无到期项；最近一项：{rows[0]['title']}（{rows[0]['date']}）")


def one_line():
    rows = sorted(DEADLINES, key=parse)
    urgent = [d for d in rows if days_left(d) <= WARN_DAYS]
    if urgent:
        d = urgent[0]
        print(f"🔥 W6 到期红线：{days_left(d)} 天后（{d['date']}）{d['title']} —— {d['action']}")
        return 1
    if UNSCHEDULED:
        print(f"⚠️ W6 到期红线：{WARN_DAYS} 天内无到期项；但有 {len(UNSCHEDULED)} 项未登记到期日")
        return 0
    print(f"✅ W6 到期红线：{WARN_DAYS} 天内无到期项")
    return 0


def esc(s):
    return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")


def write_ics(path):
    stamp = datetime.datetime.now().strftime("%Y%m%dT%H%M%S")
    L = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//AdaiOS//deadlines//CN",
        "CALSCALE:GREGORIAN",
        "X-WR-CALNAME:AdaiOS 到期红线",
    ]
    for i, d in enumerate(sorted(DEADLINES, key=parse)):
        day = parse(d)
        end = day + datetime.timedelta(days=1)
        L += [
            "BEGIN:VEVENT",
            f"UID:adaios-deadline-{i}-{d['date']}@adaiadai.com",
            f"DTSTAMP:{stamp}Z",
            f"DTSTART;VALUE=DATE:{day.strftime('%Y%m%d')}",
            f"DTEND;VALUE=DATE:{end.strftime('%Y%m%d')}",
            f"SUMMARY:{esc('AdaiOS 到期：' + d['title'])}",
            f"DESCRIPTION:{esc('后果：' + d['impact'] + ' | 处理：' + d['action'] + ' | 依据：' + d['ref'])}",
            "TRANSP:TRANSPARENT",
            # 两个提醒：提前 30 天 + 提前 7 天
            "BEGIN:VALARM",
            "TRIGGER:-P30D",
            "ACTION:DISPLAY",
            f"DESCRIPTION:{esc('30 天后到期：' + d['title'])}",
            "END:VALARM",
            "BEGIN:VALARM",
            "TRIGGER:-P7D",
            "ACTION:DISPLAY",
            f"DESCRIPTION:{esc('7 天后到期：' + d['title'])}",
            "END:VALARM",
            "END:VEVENT",
        ]
    for u in UNSCHEDULED:
        L += [
            "BEGIN:VTODO",
            f"UID:adaios-unscheduled-{abs(hash(u['title'])) % 10**8}@adaiadai.com",
            f"DTSTAMP:{stamp}Z",
            f"SUMMARY:{esc('去查到期日：' + u['title'])}",
            f"DESCRIPTION:{esc('处理：' + u['action'] + ' | 依据：' + u['ref'])}",
            "END:VTODO",
        ]
    L.append("END:VCALENDAR")
    out = pathlib.Path(path).expanduser()
    out.write_text("\r\n".join(L) + "\r\n", encoding="utf-8")
    return out


def main():
    args = sys.argv[1:]
    if "--ics" in args:
        i = args.index("--ics")
        target = args[i + 1] if len(args) > i + 1 else "~/Desktop/adaios-deadlines.ics"
        out = write_ics(target)
        n = len(DEADLINES)
        print(f"✅ 已生成日历文件：{out}")
        print(f"   {n} 个到期事件（每个带「提前 30 天 + 提前 7 天」两条提醒）+ {len(UNSCHEDULED)} 条待办")
        print("   导入方式：双击该文件 → 日历 App 询问导入到哪个日历 → 选一个即可")
        return 0
    if "--one-line" in args:
        return one_line()
    report()
    return 0


if __name__ == "__main__":
    sys.exit(main())
