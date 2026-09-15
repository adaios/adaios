#!/usr/bin/env python3
"""AdaiOS iOS 分发签名资产管理（App Store Connect API）。

背景（2026-09-15 TestFlight 批实测）：
  Xcode 的**云签名**（-allowProvisioningUpdates + API Key）在 App Store Connect
  API Key 为 App Manager 角色时会报：
      Cloud signing permission error
      「You haven't been given access to cloud-managed distribution certificates.
        Please contact your team's Account Holder or an Admin.」
  —— 云托管分发证书要求 **Admin** 角色。
  但 App Store Connect API **本身**允许 App Manager 直接创建证书与描述文件，
  于是本脚本绕开云签名，走「API 建证书/profile + 本地手动签名」，全程无需 Admin、
  也无需在 Xcode 里登录 Apple ID。

它做什么（默认 --ensure，幂等）：
  1. 查账号里有没有 IOS_DISTRIBUTION 证书
       · 没有 → 本地生成私钥+CSR → POST /v1/certificates 创建 → 导入登录钥匙串
       · 有，且本地钥匙串已有对应签名身份 → 复用（不重复创建，避免耗尽账号证书名额）
       · 有，但本地没有对应私钥 → 如实报错并给出处置（撤销重建 / 找回私钥）
  2. 查两个 App ID（主 App + ShareExtension）的 App Store 描述文件
       · 缺失 → 创建；已存在但未关联当前证书 → 删除后重建（Apple 不支持改关联）
  3. 把 profile 落到 ~/Library/MobileDevice/Provisioning Profiles/
  4. 把「bundleId → profile 名称」映射写到 dist/profiles.json，供导出脚本使用

用法：
  python3 scripts/asc_signing.py --ensure          # 幂等准备签名资产
  python3 scripts/asc_signing.py --check           # 只看现状，不做任何写操作

凭据（与 release_testflight.sh 同口径）：
  ASC_KEY_ID / ASC_ISSUER_ID / ASC_KEY_PATH，或自动发现 ~/.appstoreconnect/private_keys/*.p8

产物集中在 ~/.appstoreconnect/dist/（700，仓库外，永不入 git）：
  dist.key / dist.csr / dist.p12 / profiles.json
"""

import argparse
import base64
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

try:
    import jwt
except ImportError:
    sys.exit("❌ 缺 PyJWT：pip3 install --user pyjwt cryptography")

TEAM_ID = "4G3D37YKSB"
API = "https://api.appstoreconnect.apple.com"
BUNDLES = ["com.adaiadai.adaiApp", "com.adaiadai.adaiApp.ShareExtension"]
HOME = pathlib.Path.home()
DIST = HOME / ".appstoreconnect" / "dist"
PROFILE_DIR = HOME / "Library" / "MobileDevice" / "Provisioning Profiles"


# ── 凭据 ──
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
        sys.exit("❌ 找不到 API Key（.p8）；见 release_testflight.sh 顶部说明")
    if not key_id:
        key_id = pathlib.Path(key_path).stem.replace("AuthKey_", "")
    if not issuer:
        sys.exit("❌ 缺 ASC_ISSUER_ID（App Store Connect → Integrations 页面顶部）")

    now = int(time.time())
    token = jwt.encode(
        {"iss": issuer, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        pathlib.Path(key_path).read_text(),
        algorithm="ES256",
        headers={"kid": key_id, "typ": "JWT"},
    )
    return token, key_id


TOKEN = None


def api(method, path, payload=None, raw=False):
    req = urllib.request.Request(
        API + path,
        method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode()
            # DELETE 成功返回 204 + 空 body，直接 json.loads 会抛 JSONDecodeError
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            detail = json.loads(body)["errors"][0].get("detail", body)
        except Exception:
            detail = body
        return e.code, detail


def get_all(path):
    """跟随分页把某集合取全。"""
    out, url = [], path
    while url:
        code, body = api("GET", url)
        if code != 200:
            sys.exit(f"❌ GET {url} → HTTP {code}: {body}")
        out.extend(body.get("data", []))
        nxt = body.get("links", {}).get("next")
        url = nxt.replace(API, "") if nxt else None
    return out


# ── 证书 ──
def find_dist_cert():
    certs = get_all("/v1/certificates?filter[certificateType]=IOS_DISTRIBUTION&limit=50")
    live = [c for c in certs if c["attributes"].get("expirationDate", "") > time.strftime("%Y-%m-%dT%H:%M:%S")]
    return live


def local_dist_identity():
    """本地钥匙串里有没有可用的分发签名身份。"""
    r = subprocess.run(["security", "find-identity", "-v", "-p", "codesigning"],
                       capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if "Distribution" in line and TEAM_ID in line:
            return line.strip()
    return None


def create_cert():
    DIST.mkdir(parents=True, exist_ok=True)
    os.chmod(DIST, 0o700)
    key_file, csr_file = DIST / "dist.key", DIST / "dist.csr"
    if not key_file.exists():
        print("  · 生成私钥与 CSR（私钥只留本机）...")
        subprocess.run(
            ["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
             "-keyout", str(key_file), "-out", str(csr_file),
             "-subj", f"/CN=AdaiOS App Store Distribution/O=AdaiOS/C=CN"],
            check=True, capture_output=True)
        os.chmod(key_file, 0o600)

    print("  · 向 Apple 申请 IOS_DISTRIBUTION 证书 ...")
    code, body = api("POST", "/v1/certificates", {"data": {"type": "certificates", "attributes": {
        "certificateType": "IOS_DISTRIBUTION", "csrContent": csr_file.read_text()}}})
    if code not in (200, 201):
        sys.exit(f"❌ 创建证书失败 HTTP {code}: {body}")
    cert = body["data"]
    (DIST / "cert_id.txt").write_text(cert["id"])
    (DIST / "cert_content.b64").write_text(cert["attributes"]["certificateContent"])
    print(f"  ✓ 证书已签发 id={cert['id']} 到期={cert['attributes']['expirationDate'][:10]}")

    import_cert_to_keychain()
    return cert["id"]


def import_cert_to_keychain():
    """把 Apple 返回的证书内容与本地私钥合成 p12 并导入登录钥匙串。

    注意：必须用 `openssl pkcs12 -export -legacy`——OpenSSL 3 默认的
    AES-256/PBKDF2 加密 macOS `security import` 解不开，会报
    「MAC verification failed during PKCS12 import (wrong password?)」。
    """
    cert_der = base64.b64decode((DIST / "cert_content.b64").read_text())
    (DIST / "cert.cer").write_bytes(cert_der)
    subprocess.run(["openssl", "x509", "-inform", "DER", "-in", str(DIST / "cert.cer"),
                    "-out", str(DIST / "cert.pem")], check=True, capture_output=True)
    subprocess.run(["openssl", "pkcs12", "-export", "-legacy",
                    "-inkey", str(DIST / "dist.key"), "-in", str(DIST / "cert.pem"),
                    "-out", str(DIST / "dist.p12"), "-passout", "pass:adaios",
                    "-name", "AdaiOS App Store Distribution"], check=True, capture_output=True)
    r = subprocess.run(["security", "import", str(DIST / "dist.p12"),
                        "-k", str(HOME / "Library/Keychains/login.keychain-db"),
                        "-P", "adaios", "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"],
                       capture_output=True, text=True)
    print(f"  ✓ 证书已导入钥匙串：{r.stdout.strip() or r.stderr.strip()}")


def ensure_cert():
    live = find_dist_cert()
    local = local_dist_identity()

    if live and local:
        print(f"  ✓ 复用已有分发证书（账号 {len(live)} 张，本地身份在）")
        return live[0]["id"]
    if live and not local:
        sys.exit(
            "❌ 账号里已有 IOS_DISTRIBUTION 证书，但本机钥匙串没有对应私钥。\n"
            "   处置（二选一）：\n"
            "     a) 找回原私钥并导入本机钥匙串；或\n"
            "     b) 在 App Store Connect 撤销那张证书后重跑本脚本（会新建一张）\n"
            f"   当前账号证书：" + ", ".join(c["id"] for c in live))
    print("  · 账号内没有分发证书，新建一张")
    return create_cert()


# ── 描述文件 ──
def ensure_profile(ident, cert_id):
    code, body = api("GET", f"/v1/bundleIds?filter[identifier]={ident}")
    if code != 200 or not body.get("data"):
        sys.exit(f"❌ 找不到 App ID {ident}（HTTP {code}）——先去 developer.apple.com 注册")
    bid_id = body["data"][0]["id"]

    want_name = f"AdaiOS App Store {ident.split('.')[-1]} {TEAM_ID}"
    # 按 **bundleId 关联**匹配，而不是按名字——名字会漂（改个命名规则就会为同一个 App
    # 建出第二个 profile，2026-09-15 实测踩到），bundleId 才是真正的契约。
    # ⚠️ 必须带 &include=bundleId：**不加时 API 不返回 relationships**，
    #    匹配会全部落空 → 误判为「没有」→ 重建时撞同名 409（2026-09-15 实测踩到）。
    def list_profiles():
        return get_all("/v1/profiles?filter[profileType]=IOS_APP_STORE&limit=50&include=bundleId")

    existing = [p for p in list_profiles()
                if p.get("relationships", {}).get("bundleId", {}).get("data", {}).get("id") == bid_id]

    keep = None
    for p in existing:
        code, det = api("GET", f"/v1/profiles/{p['id']}/certificates")
        ids = [c["id"] for c in det.get("data", [])] if code == 200 else []
        if cert_id in ids and keep is None:
            keep = p
            continue
        # 未关联当前证书，或是同一 App 的重复 profile → 清掉
        api("DELETE", f"/v1/profiles/{p['id']}")
        print(f"  · 清理多余描述文件：{p['attributes']['name']}")

    if keep is not None:
        name = keep["attributes"]["name"]
        write_profile(keep["attributes"]["uuid"], base64.b64decode(keep["attributes"]["profileContent"]))
        print(f"  ✓ 复用描述文件 {name}")
        return name

    payload = {"data": {"type": "profiles",
        "attributes": {"name": want_name, "profileType": "IOS_APP_STORE"},
        "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bid_id}},
                          "certificates": {"data": [{"type": "certificates", "id": cert_id}]}}}}
    code, body = api("POST", "/v1/profiles", payload)
    if code == 409:
        # Apple 要求 profile 名在账号内唯一：同名残留会让创建失败，先清掉再建
        for p in list_profiles():
            if p["attributes"]["name"] == want_name:
                api("DELETE", f"/v1/profiles/{p['id']}")
                print(f"  · 清除同名冲突：{want_name}")
        code, body = api("POST", "/v1/profiles", payload)
    if code not in (200, 201):
        sys.exit(f"❌ 创建描述文件失败 HTTP {code}: {body}")
    d = body["data"]
    write_profile(d["attributes"]["uuid"], base64.b64decode(d["attributes"]["profileContent"]))
    print(f"  ✓ 新建描述文件 {want_name}（{d['attributes'].get('profileState')}）")
    return want_name


def write_profile(uuid, content):
    PROFILE_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(PROFILE_DIR, 0o700)
    f = PROFILE_DIR / f"{uuid}.mobileprovision"
    f.write_bytes(content)
    os.chmod(f, 0o600)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ensure", action="store_true", help="幂等准备签名资产（默认动作）")
    ap.add_argument("--check", action="store_true", help="只读检查，不做任何写操作")
    args = ap.parse_args()

    global TOKEN
    TOKEN, key_id = load_token()
    print(f"▸ API Key {key_id} · Team {TEAM_ID}")

    if args.check:
        certs = find_dist_cert()
        print(f"  分发证书：{len(certs)} 张 " + (", ".join(c['id'] for c in certs) or "（无）"))
        print(f"  本地签名身份：{local_dist_identity() or '（无）'}")
        for p in get_all("/v1/profiles?filter[profileType]=IOS_APP_STORE&limit=50"):
            print(f"  描述文件：{p['attributes']['name']} [{p['attributes']['profileState']}]")
        return

    print("▸ 准备分发证书 ...")
    cert_id = ensure_cert()
    print("▸ 准备描述文件 ...")
    mapping = {ident: ensure_profile(ident, cert_id) for ident in BUNDLES}

    DIST.mkdir(parents=True, exist_ok=True)
    (DIST / "profiles.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2))
    print("\n✅ 签名资产就绪（映射写入 ~/.appstoreconnect/dist/profiles.json）：")
    for k, v in mapping.items():
        print(f"   {k} → {v}")


if __name__ == "__main__":
    main()
