#!/usr/bin/env python3
"""审核环境一键化 —— 交易插件 web 端「起环境 → 精确点按语义锚点 → 截图」。

（2026-10-09 立，动机见 `.agents/workspace/trading-plugin/handoff-20261009.md` §五「取证纪律」）

为什么有它：UI 走查原先每次都要手工 `flutter build web`（17s）+ 起无头 Chrome + 登录取 token +
注入 localStorage + 截图，一晚重建了 11 次；且 CanvasKit 渲染下 DOM 无元素，点击只能靠
截图目测坐标 —— 于是反复点偏（案例 K 线入口 3 次没中、Tab 因横幅下移而用旧 y）。

本脚本把这两件事一次做掉：
  · `init`  —— 起「后端 + web + 无头 Chrome + 登录」，全部**脱离会话常驻**，重复调用幂等；
  · `tab/click/shot` —— 按**语义锚点**（`tab:<id>` 等，见 trading_page.dart 的 `_zoneIds`）精确定位，
    不再猜坐标；`tab` 会先把页面滚到顶部（Tab 随内容滚动，这是踩过的坑）。

用法：
    python3 .agents/mechanism/scripts/audit-web.py init          # 起环境（幂等）
    python3 .agents/mechanism/scripts/audit-web.py tab cases     # 切到「案例」区（语义锚点）
    python3 .agents/mechanism/scripts/audit-web.py click 查看详情  # 按可见文案点
    python3 .agents/mechanism/scripts/audit-web.py shot my-shot  # 截图
    python3 .agents/mechanism/scripts/audit-web.py status        # 看环境状态
    python3 .agents/mechanism/scripts/audit-web.py stop          # 全停 + 清临时文件

截图默认落 `.agents/workspace/<当前分支的任务目录>/audit-shots-<日期>/`；可用 `--out` 覆盖。
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]          # .agents/mechanism/scripts/ → 仓库根
CHROME_PORT = 9230
PROFILE = Path('/tmp/adai-audit-chrome')
BACKEND = 'http://localhost:8080'
WEB = 'http://localhost:8082'
TOKEN_FILE = Path('/tmp/adai-audit-token')
BOOTSTRAP = ROOT / 'apps/adai-web/build/web/__audit_login.html'


# ── 小工具 ────────────────────────────────────────────────────────────────
def sh(cmd, **kw):
    return subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, **kw)


def http_ok(url, timeout=3):
    try:
        urllib.request.urlopen(url, timeout=timeout)
        return True
    except urllib.error.HTTPError:
        return True          # 401/404 也算「服务在」
    except Exception:
        return False


def spawn_detached(cmd, cwd, log):
    """脱离当前会话起进程（macOS 无 setsid ⇒ start_new_session）；日志落 log。"""
    with open(log, 'w') as f:
        return subprocess.Popen(cmd, cwd=str(cwd), stdout=f, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)


def env_value(key):
    for line in (ROOT / 'services/adai-core/.env').read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and line.startswith(key + '='):
            return line.split('=', 1)[1].strip()
    raise SystemExit(f'❌ .env 里找不到 {key}')


def web_build_is_fresh():
    """build/web/main.dart.js 是否新于 lib/ 下任何 dart 源（过期才重建，省掉每次都 17s）。"""
    js = ROOT / 'apps/adai-web/build/web/main.dart.js'
    if not js.exists():
        return False
    newest = max((p.stat().st_mtime for p in (ROOT / 'apps/adai-web/lib').rglob('*.dart')), default=0)
    return js.stat().st_mtime >= newest


# ── CDP ──────────────────────────────────────────────────────────────────
class Tab:
    def __init__(self):
        from websocket import create_connection
        page = [t for t in json.load(urllib.request.urlopen(f'http://127.0.0.1:{CHROME_PORT}/json/list'))
                if t['type'] == 'page'][0]
        self.ws = create_connection(page['webSocketDebuggerUrl'], timeout=60, suppress_origin=True)
        self._id = 0

    def cmd(self, method, **params):
        self._id += 1
        self.ws.send(json.dumps({'id': self._id, 'method': method, 'params': params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get('id') == self._id:
                if 'error' in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get('result', {})

    def js(self, expr):
        return self.cmd('Runtime.evaluate', expression=expr, returnByValue=True).get('result', {}).get('value')

    def click(self, x, y):
        for t in ('mousePressed', 'mouseReleased'):
            self.cmd('Input.dispatchMouseEvent', type=t, x=x, y=y, button='left',
                     clickCount=1, buttons=1 if t == 'mousePressed' else 0)
            time.sleep(0.05)

    def wheel(self, x, y, dy, times=1, pause=0.2):
        for _ in range(times):
            self.cmd('Input.dispatchMouseEvent', type='mouseWheel', x=x, y=y, deltaX=0, deltaY=dy)
            time.sleep(pause)

    def nodes(self, needle=None):
        """语义节点（label / 文本 / 坐标）。needle 非空时按 aria-label 或 textContent 包含匹配。"""
        raw = self.js("""JSON.stringify(Array.from(document.querySelectorAll('flt-semantics')).map(e=>{
            const r=e.getBoundingClientRect();
            return {l:(e.getAttribute('aria-label')||'').trim(), t:(e.textContent||'').trim(),
                    x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height)};}))""")
        items = json.loads(raw or '[]')
        if needle:
            items = [i for i in items if needle in i['l'] or needle in i['t']]
        return [i for i in items if i['w'] > 0 and i['h'] > 0]

    def ensure_semantics(self):
        if self.js("document.querySelectorAll('flt-semantics').length") or 0 > 0:
            return
        self.js("document.querySelector('flt-semantics-placeholder')?.click()")
        time.sleep(2)

    def to_top(self):
        self.wheel(800, 500, -900, times=12, pause=0.12)

    def shot(self, path):
        r = self.cmd('Page.captureScreenshot', format='png')
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_bytes(base64.b64decode(r['data']))
        return path


def default_out_dir():
    branch = sh('git rev-parse --abbrev-ref HEAD', cwd=ROOT).stdout.strip().split('/')[-1]
    d = ROOT / '.agents/workspace' / branch / f"audit-shots-{time.strftime('%Y%m%d')}"
    d.mkdir(parents=True, exist_ok=True)
    return d


# ── 子命令 ────────────────────────────────────────────────────────────────
def cmd_init(_):
    # 1) 后端
    if http_ok(f'{BACKEND}/api/v1/identity'):
        print('后端已在跑')
    else:
        print('起后端…（日志 /tmp/adai-audit-backend.log）')
        spawn_detached(['./gradlew', 'bootRun', '--offline'], ROOT / 'services/adai-core',
                       '/tmp/adai-audit-backend.log')
        for _ in range(45):
            time.sleep(2)
            if http_ok(f'{BACKEND}/api/v1/identity'):
                break
        print('后端就绪' if http_ok(f'{BACKEND}/api/v1/identity') else '⚠️ 后端 90s 未就绪，看日志')

    # 2) web（构建过期才重建）
    if not web_build_is_fresh():
        print('web 产物过期 → 重建（约 20s）')
        r = sh(['sh', 'scripts/serve_web.sh', BACKEND, '--build-only'], cwd=ROOT / 'apps/adai-web')
        if r.returncode != 0:
            print(r.stdout[-800:]); raise SystemExit('❌ web 构建失败')
    if not http_ok(WEB):
        print('起 web 静态服务…')
        spawn_detached(['python3', '-m', 'http.server', '8082'],
                       ROOT / 'apps/adai-web/build/web', '/tmp/adai-audit-web.log')
        for _ in range(15):
            time.sleep(1)
            if http_ok(WEB):
                break
    print('web 就绪' if http_ok(WEB) else '⚠️ web 未就绪')

    # 3) Chrome
    if http_ok(f'http://127.0.0.1:{CHROME_PORT}/json/version'):
        print('Chrome 已在跑')
    else:
        print('起无头 Chrome…')
        exe = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
        spawn_detached([exe, '--headless=new', f'--remote-debugging-port={CHROME_PORT}',
                        f'--user-data-dir={PROFILE}', '--window-size=1600,1200',
                        '--no-first-run', '--no-default-browser-check', '--disable-extensions',
                        '--hide-scrollbars', 'about:blank'],
                       ROOT, '/tmp/adai-audit-chrome.log')
        for _ in range(20):
            time.sleep(1)
            if http_ok(f'http://127.0.0.1:{CHROME_PORT}/json/version'):
                break

    # 4) 登录注入
    if TOKEN_FILE.exists() and TOKEN_FILE.read_text().strip():
        token = TOKEN_FILE.read_text().strip()
    else:
        import urllib.request as ur
        body = json.dumps({'account': env_value('ADAI_SMOKE_ACCOUNT'),
                           'password': env_value('ADAI_SMOKE_PASSWORD')}).encode()
        req = ur.Request(f'{BACKEND}/api/v1/auth/login', data=body,
                         headers={'Content-Type': 'application/json'})
        token = json.loads(ur.urlopen(req).read())['token']
        TOKEN_FILE.write_text(token)
    BOOTSTRAP.write_text(
        "<!doctype html><meta charset=utf-8><title>audit</title><script>"
        f"localStorage.setItem('auth_token','{token}');"
        "localStorage.setItem('current_user_id','adai');location.replace('/');</script>")
    t = Tab()
    t.cmd('Page.enable'); t.cmd('Runtime.enable')
    t.cmd('Emulation.setDeviceMetricsOverride', width=1600, height=1200, deviceScaleFactor=1, mobile=False)
    t.cmd('Page.navigate', url=f'{WEB}/__audit_login.html')
    for _ in range(25):
        time.sleep(1)
        if t.js("document.querySelectorAll('flutter-view, flt-glass-pane, canvas').length"):
            break
    time.sleep(4)
    t.ensure_semantics()
    t.click(60, 402)          # 左导航「交易」（全局侧栏，位置稳定）
    time.sleep(6)
    t.ensure_semantics()
    print(f'✅ 环境就绪（{WEB}）· 语义节点 {t.js("document.querySelectorAll(" + chr(39) + "flt-semantics" + chr(39) + ").length")}')


def _tab():
    t = Tab()
    t.ensure_semantics()
    return t


def cmd_tab(args):
    t = _tab()
    t.to_top()                     # Tab 随内容滚动 → 先回顶（踩过的坑）
    t.ensure_semantics()
    hits = t.nodes(f'tab:{args.id}')
    if not hits:
        hits = [n for n in t.nodes() if n['y'] > 200 and n['y'] < 420 and n['w'] < 140]
        if not hits:
            raise SystemExit(f'❌ 没找到 tab:{args.id}（先确认环境在跑：audit-web.py status）')
    hit = hits[0]
    t.click(hit['x'] + hit['w'] / 2, hit['y'] + hit['h'] / 2)
    time.sleep(5)
    if args.shot:
        print('📸', t.shot(default_out_dir() / f'{args.shot}.png'))
    else:
        print(f"切到 tab:{args.id} @ {(hit['x'], hit['y'])}")


def cmd_click(args):
    t = _tab()
    # 找不到就**自动向下滚**再找（最多 12 次 × 300px）——这正是"滚动到恰好位置"那类坑的解药：
    # 案例库表行末的「查看详情」入口曾让手工脚本连试三次都没点中。
    if args.top:
        t.to_top()
    hits = [h for h in t.nodes(args.label) if 60 < h['y'] < 1150]
    for _ in range(12 if not hits else 0):
        t.wheel(800, 800, 300, times=1, pause=0.5)
        hits = [h for h in t.nodes(args.label) if 60 < h['y'] < 1150]
        if hits:
            break
    if not hits:
        raise SystemExit(f'❌ 没找到「{args.label}」（已自动下滚 12 次；确认它在当前区、或先 `tab <id>` 切区）')
    hit = hits[0]
    t.click(hit['x'] + hit['w'] / 2, hit['y'] + hit['h'] / 2)
    time.sleep(args.wait)
    if args.shot:
        print('📸', t.shot(default_out_dir() / f'{args.shot}.png'))
    else:
        print(f"点了「{args.label}」@ {(hit['x'], hit['y'])}")


def cmd_shot(args):
    t = _tab()
    print('📸', t.shot(default_out_dir() / f'{args.name}.png'))


def cmd_status(_):
    print(f"后端 {'✅' if http_ok(f'{BACKEND}/api/v1/identity') else '❌'} · "
          f"web {'✅' if http_ok(WEB) else '❌'} · "
          f"Chrome {'✅' if http_ok(f'http://127.0.0.1:{CHROME_PORT}/json/version') else '❌'}")


def cmd_stop(_):
    for pat in ('http.server 8082', 'AdaiCoreApplication', f'remote-debugging-port={CHROME_PORT}'):
        sh(['pkill', '-f', pat])
    for p in (BOOTSTRAP, TOKEN_FILE):
        try:
            p.unlink()
        except FileNotFoundError:
            pass
    sh(['rm', '-rf', str(PROFILE)])
    print('已停（后端 / web / Chrome）并清掉含 token 的临时文件')


def main():
    ap = argparse.ArgumentParser(description='交易插件 web 端审核环境一键化')
    sub = ap.add_subparsers(dest='cmd', required=True)
    sub.add_parser('init', help='起环境（幂等）').set_defaults(func=cmd_init)
    p = sub.add_parser('tab', help='按语义锚点切一级区'); p.add_argument('id'); p.add_argument('--shot')
    p.set_defaults(func=cmd_tab)
    p = sub.add_parser('click', help='按可见文案点击'); p.add_argument('label')
    p.add_argument('--shot'); p.add_argument('--wait', type=float, default=6)
    p.add_argument('--top', action='store_true', help='先回到顶部再找')
    p.set_defaults(func=cmd_click)
    p = sub.add_parser('shot', help='截图'); p.add_argument('name'); p.set_defaults(func=cmd_shot)
    sub.add_parser('status', help='看环境状态').set_defaults(func=cmd_status)
    sub.add_parser('stop', help='全停 + 清理').set_defaults(func=cmd_stop)
    args = ap.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
