#!/usr/bin/env python3
"""TestFlight 外部测试管理（App Store Connect API）。

背景（2026-09-21）：要把阿呆发给**外部测试员**（非团队成员，邮箱邀请）。
外部测试与内部测试的关键差别：**首次必须过苹果 Beta App Review（24~48h）**，
而提交审核需要三样东西全部就位——
  ① 隐私政策公开 URL（测试信息必填）→ https://adaiadai.com/privacy
  ② 审核联系人（姓名 / 电话 / 邮箱）
  ③ 可独立登录的测试账号（applereview + 密码）
本脚本把「建外部组 → 填测试信息 → 填审核信息 → 提交审核 → 邀请邮箱」串成一条命令，
省掉在 App Store Connect 网页上逐项点击（也避免漏填导致被拒）。

它做什么：
  --status          只读：外部组 / 测试员 / 审核信息填写情况 / 审核状态 / 构建
  --fill            填「测试信息」：隐私政策 URL + 反馈邮箱 + Beta App 描述 + What to Test
                    （两处隐私政策 URL 都填：TestFlight 测试信息 + App Store 上架资料）
  --review-info     填「App 审核信息」：联系人 + 测试账号（密码经 --demo-password / 环境变量传入）
  --submit-review   提交 Beta App Review（自动取最新 VALID 构建）
  --invite A@b.com  邀请外部测试员（可多个邮箱；加入外部组后苹果自动发邀请邮件）

凭据（与 testflight_status.py / asc_signing.py 同口径）：
  ASC_ISSUER_ID（可省，自动发现 ~/.appstoreconnect/issuer_id）
  ASC_KEY_ID / ASC_KEY_PATH（可省，自动发现 ~/.appstoreconnect/private_keys/*.p8）

密码纪律：审核用测试账号密码**永不落盘、永不打印**——只经 ASC_DEMO_PASSWORD
环境变量或 --demo-password 传入，直接写进 App Store Connect。

用法：
  python3 scripts/testflight_external.py --status
  python3 scripts/testflight_external.py --fill
  python3 scripts/testflight_external.py --review-info \
      --contact-first 名 --contact-last 姓 --contact-phone 138... --contact-email me@x.com \
      --demo-account applereview
  ASC_DEMO_PASSWORD='...' python3 scripts/testflight_external.py --submit-review
  python3 scripts/testflight_external.py --invite friend@example.com
"""

import argparse
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

try:
    import jwt
except ImportError:
    sys.exit("❌ 缺 PyJWT：pip3 install --user pyjwt cryptography")

API = "https://api.appstoreconnect.apple.com"
BUNDLE_ID = "com.adaiadai.adaiApp"
HOME = pathlib.Path.home()
LOCALE = "zh-Hans"

PRIVACY_URL = "https://adaiadai.com/privacy"
FEEDBACK_EMAIL = "rottokaka@gmail.com"
GROUP_NAME = "阿呆外测"

# 「测试信息」里的 Beta App Description（对外可见，讲清这是什么）
BETA_DESCRIPTION = (
    "阿呆是一个个人 AI 助手：你把每天的一句话、一张图交给它，它记下来、"
    "帮你回看，并把值得留下的东西整理成可以复习的卡片。"
    "本版本为 TestFlight 测试版，用于验证记录、问答与学习三条主链路，功能仍在调整中。"
)

# 「What to Test」——审核员按这个走（与 docs/deployment/testflight-external-testing.md §3.1 同口径）
WHAT_TO_TEST = """本版重点验证三条主链路：
1. 登录：用测试账号登录（冷启动若出现生物识别提示，可直接取消）。
2. 记录与问答（核心链路）：在主页输入任意一句话（如「今天心情不错」），
   观察阿呆的回复与卡片生成。
3. 学习模块：进入学习页查看卡片列表与详情（新账号为空态属正常）。

说明：本 App 为个人 AI 助手，所有数据属于该测试账号，无社交与支付。"""

# 提交审核时若没传 --notes 用的默认备注（模板见 docs/deployment/testflight-external-testing.md §3.2）
DEFAULT_NOTES = """登录路径：启动 App → 登录页输入测试账号密码 → 进入主页。
测试账号（Sign-in information 字段同值）：
  Username: applereview
  Password: 见下方 Sign-in required 的 Password 字段（不在备注正文重复）
无短信验证码、无二次验证（No OTP, no 2FA）。
启动时若出现 Face ID / 生物识别提示，可直接取消，不影响登录。
App 需要 iPhone；主要功能为个人记录与 AI 对话，所有数据属于该测试账号，
不涉及用户生成内容的公开传播。

English summary:
  Test account: see the sign-in information above. Launch the app, sign in on
  the login screen. No OTP required. A Face ID prompt may appear on launch and
  can be safely cancelled. The app is a personal AI assistant for a single
  test account; there is no social or payment functionality."""


# ── 凭据与请求 ──
def load_token():
    key_id = os.environ.get("ASC_KEY_ID", "")
    issuer = os.environ.get("ASC_ISSUER_ID", "")
    key_path = os.environ.get("ASC_KEY_PATH", "")

    if not issuer:
        f = HOME / ".appstoreconnect" / "issuer_id"
        if f.exists():
            issuer = f.read_text().strip()
    if not key_path:
        found = sorted((HOME / ".appstoreconnect" / "private_keys").glob("AuthKey_*.p8"))
        if len(found) == 1:
            key_path = str(found[0])
        elif len(found) > 1:
            sys.exit("❌ private_keys 下有多个 .p8，请用 ASC_KEY_PATH 指定")
    if not key_path or not pathlib.Path(key_path).exists():
        sys.exit("❌ 找不到 API Key（.p8）")
    if not key_id:
        key_id = pathlib.Path(key_path).stem.replace("AuthKey_", "")
    if not issuer:
        sys.exit("❌ 缺 ASC_ISSUER_ID（App Store Connect → Integrations 页面顶部）")

    now = int(time.time())
    return jwt.encode(
        {"iss": issuer, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        pathlib.Path(key_path).read_text(),
        algorithm="ES256",
        headers={"kid": key_id, "typ": "JWT"},
    )


TOKEN = None


def api(method, path, payload=None):
    req = urllib.request.Request(
        API + path,
        method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode()
            # DELETE / PATCH 可能返回 204 + 空 body
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            errs = json.loads(body).get("errors", [])
            detail = "; ".join(f"{x.get('title')}: {x.get('detail')}" for x in errs) or body
        except Exception:
            detail = body
        return e.code, detail


def get_all(path):
    out, url = [], path
    while url:
        code, body = api("GET", url)
        if code != 200:
            sys.exit(f"❌ GET {url} → HTTP {code}: {body}")
        out.extend(body.get("data", []))
        nxt = body.get("links", {}).get("next")
        url = nxt.replace(API, "") if nxt else None
    return out


def app_id():
    code, body = api("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}")
    if code != 200 or not body.get("data"):
        sys.exit(f"❌ 找不到 App 记录（{BUNDLE_ID}）")
    return body["data"][0]["id"]


def external_group(aid):
    groups = get_all(f"/v1/betaGroups?filter[app]={aid}&limit=50")
    return [g for g in groups if not g["attributes"].get("isInternalGroup")]


def latest_build(aid, want_valid=True):
    builds = get_all(f"/v1/builds?filter[app]={aid}&limit=10&sort=-uploadedDate")
    if want_valid:
        builds = [b for b in builds if b["attributes"].get("processingState") == "VALID"]
    return builds[0] if builds else None


# ── 命令 ──
def cmd_status(aid):
    print(f"=== TestFlight 外部测试现状（app {aid}）===")

    groups = external_group(aid)
    print("\n▸ 外部测试组")
    for g in groups:
        a = g["attributes"]
        print(f"  · {a.get('name')}  id={g['id']}  公开链接={'开' if a.get('publicLinkEnabled') else '关'}")
    if not groups:
        print("  （无 —— 外部测试必须先建组）")

    gids = {g["id"] for g in groups}
    testers = get_all("/v1/betaTesters?limit=100")
    print(f"\n▸ 测试员（共 {len(testers)}）")
    for t in testers:
        a = t["attributes"]
        in_ext = ""
        code, rel = api("GET", f"/v1/betaTesters/{t['id']}/betaGroups")
        if code == 200:
            ids = {x["id"] for x in rel.get("data", [])}
            in_ext = "  ← 在外部组" if ids & gids else ""
        print(f"  · {a.get('email')}  state={a.get('state')}{in_ext}")

    code, d = api("GET", f"/v1/betaAppReviewDetails?filter[app]={aid}")
    print("\n▸ 审核信息（Beta App Review）")
    if code == 200 and d.get("data"):
        a = d["data"][0]["attributes"]
        need = ["contactFirstName", "contactLastName", "contactPhone", "contactEmail",
                "demoAccountName", "demoAccountPassword"]
        for k in need:
            v = a.get(k)
            if k == "demoAccountPassword":
                v = "已设置（不显示）" if v else None
            print(f"  · {k}: {v if v else '❌ 空'}")
    else:
        print(f"  ❌ 未填（{d}）")

    print("\n▸ 隐私政策 URL")
    locs = get_all(f"/v1/betaAppLocalizations?filter[app]={aid}")
    for l in locs:
        a = l["attributes"]
        print(f"  · TestFlight[{a.get('locale')}] privacyPolicyUrl={a.get('privacyPolicyUrl')}"
              f"  feedbackEmail={a.get('feedbackEmail')}")
    infos = get_all(f"/v1/apps/{aid}/appInfos")
    for info in infos:
        for l in get_all(f"/v1/appInfos/{info['id']}/appInfoLocalizations"):
            a = l["attributes"]
            print(f"  · AppStore[{a.get('locale')}] privacyPolicyUrl={a.get('privacyPolicyUrl')}")

    print("\n▸ 最新构建")
    b = latest_build(aid, want_valid=False)
    if b:
        a = b["attributes"]
        print(f"  · 1.0.0+{a.get('version')} state={a.get('processingState')} "
              f"过期 {str(a.get('expirationDate'))[:10]}")
        code, subs = api("GET", f"/v1/betaAppReviewSubmissions?filter[build]={b['id']}")
        if code == 200 and subs.get("data"):
            print(f"    审核状态：{subs['data'][0]['attributes'].get('betaReviewState')}")
        else:
            print("    审核状态：尚未提交 Beta 审核")
    else:
        print("  （无构建）")


def cmd_fill(aid):
    print("=== 填「测试信息」（隐私政策 URL / 反馈邮箱 / 描述 / What to Test）===")

    # ① TestFlight 测试信息（betaAppLocalizations）
    locs = {l["attributes"].get("locale"): l for l in
            get_all(f"/v1/betaAppLocalizations?filter[app]={aid}")}
    attrs = {
        "description": BETA_DESCRIPTION,
        "feedbackEmail": FEEDBACK_EMAIL,
        "privacyPolicyUrl": PRIVACY_URL,
    }
    if LOCALE in locs:
        lid = locs[LOCALE]["id"]
        code, res = api("PATCH", f"/v1/betaAppLocalizations/{lid}",
                        {"data": {"type": "betaAppLocalizations", "id": lid, "attributes": attrs}})
    else:
        code, res = api("POST", "/v1/betaAppLocalizations", {"data": {
            "type": "betaAppLocalizations",
            "attributes": {**attrs, "locale": LOCALE},
            "relationships": {"app": {"data": {"type": "apps", "id": aid}}},
        }})
    print(f"  {'✅' if code in (200, 201) else '❌'} TestFlight 测试信息 [{LOCALE}] → HTTP {code}"
          f"{'' if code in (200, 201) else '  ' + str(res)}")

    # ② App Store 上架资料的隐私政策 URL
    for info in get_all(f"/v1/apps/{aid}/appInfos"):
        for l in get_all(f"/v1/appInfos/{info['id']}/appInfoLocalizations"):
            if l["attributes"].get("locale") != LOCALE:
                continue
            lid = l["id"]
            code, res = api("PATCH", f"/v1/appInfoLocalizations/{lid}", {"data": {
                "type": "appInfoLocalizations", "id": lid,
                "attributes": {"privacyPolicyUrl": PRIVACY_URL}}})
            print(f"  {'✅' if code == 200 else '❌'} App Store 资料 [{LOCALE}] 隐私政策 URL → HTTP {code}"
                  f"{'' if code == 200 else '  ' + str(res)}")

    # ③ 最新构建的 What to Test（build 级本地化）
    b = latest_build(aid)
    if not b:
        print("  ⚠️ 没有 VALID 构建，跳过 What to Test")
        return
    bls = {x["attributes"].get("locale"): x for x in
           get_all(f"/v1/builds/{b['id']}/betaBuildLocalizations")}
    if LOCALE in bls:
        lid = bls[LOCALE]["id"]
        code, res = api("PATCH", f"/v1/betaBuildLocalizations/{lid}", {"data": {
            "type": "betaBuildLocalizations", "id": lid,
            "attributes": {"whatsNew": WHAT_TO_TEST}}})
    else:
        code, res = api("POST", "/v1/betaBuildLocalizations", {"data": {
            "type": "betaBuildLocalizations",
            "attributes": {"locale": LOCALE, "whatsNew": WHAT_TO_TEST},
            "relationships": {"build": {"data": {"type": "builds", "id": b["id"]}}},
        }})
    print(f"  {'✅' if code in (200, 201) else '❌'} 构建 {b['attributes'].get('version')} "
          f"What to Test → HTTP {code}{'' if code in (200, 201) else '  ' + str(res)}")


def cmd_review_info(aid, args):
    print("=== 填「App 审核信息」===")
    code, d = api("GET", f"/v1/betaAppReviewDetails?filter[app]={aid}")
    if code != 200 or not d.get("data"):
        sys.exit(f"❌ 取不到 betaAppReviewDetails：{d}")
    rid = d["data"][0]["id"]

    attrs = {}
    if args.contact_first:
        attrs["contactFirstName"] = args.contact_first
    if args.contact_last:
        attrs["contactLastName"] = args.contact_last
    if args.contact_phone:
        attrs["contactPhone"] = args.contact_phone
    if args.contact_email:
        attrs["contactEmail"] = args.contact_email
    if args.demo_account:
        attrs["demoAccountName"] = args.demo_account
        attrs["demoAccountRequired"] = True
    pwd = args.demo_password or os.environ.get("ASC_DEMO_PASSWORD", "")
    if pwd:
        attrs["demoAccountPassword"] = pwd
    attrs["notes"] = args.notes or DEFAULT_NOTES

    code, res = api("PATCH", f"/v1/betaAppReviewDetails/{rid}",
                    {"data": {"type": "betaAppReviewDetails", "id": rid, "attributes": attrs}})
    shown = {k: ("***" if k == "demoAccountPassword" else v) for k, v in attrs.items()}
    if code == 200:
        print("  ✅ 已写入：")
        for k, v in shown.items():
            print(f"     · {k} = {str(v)[:80]}")
    else:
        print(f"  ❌ HTTP {code}: {res}")
    if not pwd:
        print("  ⚠️ 未提供测试账号密码（--demo-password 或 ASC_DEMO_PASSWORD）——审核员登不进去会被拒")


def cmd_submit_review(aid, args):
    print("=== 提交 Beta App Review ===")
    b = latest_build(aid)
    if not b:
        sys.exit("❌ 没有 VALID 构建可提交")
    bid = b["id"]
    ver = b["attributes"].get("version")
    code, subs = api("GET", f"/v1/betaAppReviewSubmissions?filter[build]={bid}")
    if code == 200 and subs.get("data"):
        st = subs["data"][0]["attributes"].get("betaReviewState")
        print(f"  构建 {ver} 已有审核记录：{st}（不重复提交）")
        return
    code, res = api("POST", "/v1/betaAppReviewSubmissions", {"data": {
        "type": "betaAppReviewSubmissions",
        "relationships": {"build": {"data": {"type": "builds", "id": bid}}}}})
    if code in (200, 201):
        print(f"  ✅ 构建 {ver} 已提交 Beta 审核（首次通常 24~48h）")
        print("  查看结果：App Store Connect → TestFlight → 该构建的审核状态，或跑 --status")
    else:
        print(f"  ❌ HTTP {code}: {res}")


def cmd_assign_build(aid, args):
    print("=== 把最新 VALID 构建加入外部测试组 ===")
    groups = external_group(aid)
    if not groups:
        sys.exit("❌ 没有外部测试组")
    gid = args.group_id or groups[0]["id"]
    b = latest_build(aid)
    if not b:
        sys.exit("❌ 没有 VALID 构建可分配")
    bid, ver = b["id"], b["attributes"].get("version")

    code, rel = api("GET", f"/v1/betaGroups/{gid}/relationships/builds")
    if code == 200 and any(x["id"] == bid for x in rel.get("data", [])):
        print(f"  ✅ 构建 {ver} 已在外部组内（无需重复分配）")
        return
    code, res = api("POST", f"/v1/betaGroups/{gid}/relationships/builds",
                    {"data": [{"type": "builds", "id": bid}]})
    if code in (200, 204):
        print(f"  ✅ 构建 {ver} 已加入外部组 {groups[0]['attributes'].get('name')}")
    else:
        print(f"  ❌ HTTP {code}: {res}")


def cmd_invite(aid, args):
    print("=== 邀请外部测试员 ===")
    groups = external_group(aid)
    if not groups:
        sys.exit("❌ 还没有外部测试组（先跑 --fill 建组 / 在网页建）")
    gid = args.group_id or groups[0]["id"]
    print(f"  外部组：{groups[0]['attributes'].get('name')}（{gid}）")

    for email in args.invite:
        existing = get_all(f"/v1/betaTesters?filter[email]={email}")
        if existing:
            tid = existing[0]["id"]
            code, res = api("POST", f"/v1/betaGroups/{gid}/relationships/betaTesters",
                            {"data": [{"type": "betaTesters", "id": tid}]})
            print(f"  {'✅' if code in (200, 204) else '❌'} {email} 已存在，加入外部组 → HTTP {code}"
                  f"{'' if code in (200, 204) else '  ' + str(res)}")
            continue
        attrs = {"email": email}
        if args.first_name:
            attrs["firstName"] = args.first_name
        if args.last_name:
            attrs["lastName"] = args.last_name
        code, res = api("POST", "/v1/betaTesters", {"data": {
            "type": "betaTesters", "attributes": attrs,
            "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": gid}]}}}})
        if code in (200, 201):
            print(f"  ✅ {email} 已邀请（苹果会发邀请邮件）")
        else:
            print(f"  ❌ {email} → HTTP {code}: {res}")


def main():
    ap = argparse.ArgumentParser(description="TestFlight 外部测试管理")
    ap.add_argument("--status", action="store_true", help="只读：看现状")
    ap.add_argument("--fill", action="store_true", help="填测试信息（隐私政策 URL 等）")
    ap.add_argument("--review-info", action="store_true", help="填 App 审核信息")
    ap.add_argument("--submit-review", action="store_true", help="提交 Beta App Review")
    ap.add_argument("--assign-build", action="store_true", help="把最新 VALID 构建加入外部组")
    ap.add_argument("--invite", nargs="+", metavar="EMAIL", help="邀请这些邮箱")
    ap.add_argument("--contact-first"), ap.add_argument("--contact-last")
    ap.add_argument("--contact-phone"), ap.add_argument("--contact-email")
    ap.add_argument("--demo-account", default="applereview")
    ap.add_argument("--demo-password", help="测试账号密码（也可用 ASC_DEMO_PASSWORD；不落盘）")
    ap.add_argument("--notes", help="审核备注正文（默认用模板）")
    ap.add_argument("--group-id", help="指定外部组 id（默认第一个外部组）")
    ap.add_argument("--first-name"), ap.add_argument("--last-name")
    args = ap.parse_args()

    global TOKEN
    TOKEN = load_token()
    aid = app_id()

    if args.fill:
        cmd_fill(aid)
    if args.review_info:
        cmd_review_info(aid, args)
    if args.submit_review:
        cmd_submit_review(aid, args)
    if args.assign_build:
        cmd_assign_build(aid, args)
    if args.invite:
        cmd_invite(aid, args)
    if not any([args.fill, args.review_info, args.submit_review, args.assign_build, args.invite]):
        cmd_status(aid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
