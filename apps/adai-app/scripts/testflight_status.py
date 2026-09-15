#!/usr/bin/env python3
"""TestFlight 构建状态查询（App Store Connect API）。

为什么单独一个脚本：上传成功 ≠ 能用。`altool` 返回 `UPLOAD SUCCEEDED` 只代表字节传完了，
Apple 还要 processing（通常 5~30 分钟）才会变成可测试；被拒时也**不会**在上传那一步报错，
而是 processing 结束后状态变 INVALID（原因在邮件里）。所以「发版」这件事的闭环是
**上传 → 状态 VALID**，这个脚本补的就是后半段。

用法：
  python3 scripts/testflight_status.py                 # 查最近 3 个构建
  python3 scripts/testflight_status.py --wait          # 轮询到终态（默认最多 30 分钟）
  python3 scripts/testflight_status.py --wait --timeout 900

凭据（与 asc_signing.py / release_testflight.sh 同口径）：
  ASC_ISSUER_ID（必填）；ASC_KEY_ID / ASC_KEY_PATH 可省（自动发现 ~/.appstoreconnect/private_keys/）

退出码：0 = 有可用构建（VALID）；1 = 异常/超时/被拒（可直接用于 CI 判据）
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

STATE_HUMAN = {
    "PROCESSING": "Apple 正在处理（通常 5~30 分钟）",
    "VALID": "✅ 可测试 —— 去 TestFlight 就能装了",
    "INVALID": "❌ 被 Apple 拒（原因在 App Store Connect 的邮件里，常见：缺图标/权限描述/隐私清单）",
    "FAILED": "❌ 处理失败",
}


def load_token():
    key_id = os.environ.get("ASC_KEY_ID", "")
    issuer = os.environ.get("ASC_ISSUER_ID", "")
    key_path = os.environ.get("ASC_KEY_PATH", "")

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


def get(token, path):
    req = urllib.request.Request(API + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def fetch_builds(token, limit=3):
    apps = get(token, f"/v1/apps?filter[bundleId]={BUNDLE_ID}")
    if not apps.get("data"):
        sys.exit(f"❌ 找不到 App 记录（{BUNDLE_ID}）——先去 App Store Connect 建 App")
    app_id = apps["data"][0]["id"]
    builds = get(token, f"/v1/builds?filter[app]={app_id}&limit={limit}&sort=-uploadedDate")
    return app_id, builds.get("data", [])


def show(data):
    if not data:
        print("  还没有任何构建（Apple 仍在处理，或从未上传成功）")
        return None
    for b in data:
        a = b["attributes"]
        state = a.get("processingState")
        print(f"  · 版本 {a.get('version')}  {state} —— {STATE_HUMAN.get(state, state)}")
        print(f"      上传 {a.get('uploadedDate')}  过期 {str(a.get('expirationDate'))[:10]}"
              f"  出口合规={a.get('usesNonExemptEncryption')}")
    return data[0]["attributes"].get("processingState")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wait", action="store_true", help="轮询到终态")
    ap.add_argument("--timeout", type=int, default=1800, help="--wait 的上限秒数（默认 1800）")
    ap.add_argument("--limit", type=int, default=3, help="列出几个构建（默认 3）")
    args = ap.parse_args()

    token = load_token()
    app_id, data = fetch_builds(token, args.limit)

    print(f"=== TestFlight 构建（app {app_id}）===")
    state = show(data)

    if not args.wait or state != "PROCESSING":
        return 0 if state == "VALID" else (0 if state is None else 1)

    deadline = time.time() + args.timeout
    print("\n▸ 等待 Apple 处理完成（Ctrl-C 可中断，不影响上传结果）...")
    while time.time() < deadline:
        time.sleep(30)
        _, data = fetch_builds(token, 1)
        state = data[0]["attributes"].get("processingState") if data else None
        print(f"  [{time.strftime('%H:%M:%S')}] {state}")
        if state and state != "PROCESSING":
            break

    print()
    show(data)
    if state == "VALID":
        print("\n✅ 构建可用：打开 TestFlight App，或去 App Store Connect 分配给测试组")
        return 0
    if state == "PROCESSING":
        print("\n⏳ 仍在处理（超过等待上限）——稍后再跑一次本脚本即可")
        return 1
    print("\n❌ 构建未通过 —— 去 App Store Connect 看通知/邮件里的具体原因")
    return 1


if __name__ == "__main__":
    sys.exit(main())
