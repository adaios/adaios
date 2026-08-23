#!/usr/bin/env python3
"""AdaiOS 静态文件服务器 — 正确 MIME + 多线程 + gzip 压缩 + 分级缓存。

修复 Flutter Web 生产白屏/极慢（2026-08-22，REVIEW 根因）：
- 原实现零压缩 + 全量 no-store：Windows 首屏裸传 14~17MB（wasm/js 未压缩），
  腾讯云轻量带宽小 → 30~60s 白屏，且每次刷新全量重下。
- 本版四板斧：
  1. gzip 压缩（标准库，零依赖；wasm 压 ~67%、js 压 ~71%）→ 首屏 14MB → ~4MB
  2. 分级缓存：/canvaskit/ /fonts/ immutable 长缓存；其余 no-cache + 条件请求
     （Last-Modified/If-Modified-Since → 304 秒回，刷新不再重下，改版仍即时生效）
  3. HTTP/1.1 keep-alive（原 HTTP/1.0 每请求新建连接，多文件下载排队）
  4. 压缩结果内存缓存（大文件只压一次，LRU 上限 64MB）

用法：python3 serve_static.py <port> <root_dir>
部署：/opt/adaios/serve_static.py（systemd：adaios-web :8082 / adaios-admin :8083）
"""

import gzip
import http.server
import os
import socketserver
import sys
import threading
from email.utils import parsedate_to_datetime

# 可压缩类型（woff2 本身已压缩，不再重复压；wasm/js 收益巨大）
GZIP_EXT = {".wasm", ".js", ".mjs", ".css", ".html", ".json", ".svg", ".txt", ".ttf", ".otf"}
GZIP_MIN_BYTES = 512        # 小于此不压（小文件压缩不值）
GZIP_LEVEL = 6              # 平衡点；wasm 4.1M→1.4M 实测
CACHE_MAX_BYTES = 64 * 1024 * 1024  # 压缩缓存上限（防内存膨胀）

# 稳定资源（字体/CanvasKit wasm）→ 长缓存 immutable
IMMUTABLE_PREFIXES = ("/canvaskit/", "/fonts/", "/icons/", "/assets/fonts/")


class Handler(http.server.SimpleHTTPRequestHandler):
    # HTTP/1.1 keep-alive：多文件并行下载不排队（原 HTTP/1.0 每请求新建连接）
    protocol_version = "HTTP/1.1"

    extensions_map = dict(http.server.SimpleHTTPRequestHandler.extensions_map)
    extensions_map.update({
        ".wasm": "application/wasm",
        ".mjs": "application/javascript",
        ".js": "application/javascript",
        ".css": "text/css",
        ".json": "application/json",
        ".png": "image/png",
        ".ico": "image/x-icon",
        ".woff2": "font/woff2",
        ".ttf": "font/ttf",
        ".otf": "font/otf",
        ".txt": "text/plain",
        ".svg": "image/svg+xml",
        ".html": "text/html",
    })

    # 压缩结果缓存：{文件路径: gzip bytes}，LRU 上限
    _gz_cache = {}
    _gz_cache_size = 0
    _gz_lock = threading.Lock()

    def _gzip_cached(self, path: str, body: bytes) -> bytes:
        """带内存缓存的 gzip 压缩（大文件只压一次，避免每请求重复压缩烧 CPU）。"""
        with self._gz_lock:
            cached = self._gz_cache.get(path)
        if cached is not None:
            return cached
        compressed = gzip.compress(body, compresslevel=GZIP_LEVEL)
        with self._gz_lock:
            self._gz_cache[path] = compressed
            self._gz_cache_size += len(compressed)
            # 超过上限清空（简单粗暴；生产 50M 产物远小于 64M 上限，正常不触发）
            if self._gz_cache_size > CACHE_MAX_BYTES:
                self._gz_cache.clear()
                self._gz_cache_size = 0
        return compressed

    def send_head(self):
        path = self.translate_path(self.path.split("?")[0])

        if os.path.isdir(path):
            # 目录 → 默认处理（目录列表 / 404）
            return super().send_head()

        try:
            st = os.stat(path)
        except OSError:
            return super().send_head()  # 404 走默认

        # 条件请求：客户端缓存未过期 → 304，刷新不全量重下。
        # 注意：date_time_string 秒级精度（mtime 带毫秒），比较须取整秒，否则恒 200
        ims = self.headers.get("If-Modified-Since")
        if ims:
            try:
                if int(parsedate_to_datetime(ims).timestamp()) >= int(st.st_mtime):
                    self.send_response(304)
                    self.send_header("Cache-Control", self._cache_control())
                    self.end_headers()
                    return None
            except (ValueError, TypeError):
                pass

        ctype = self.guess_type(path)
        try:
            with open(path, "rb") as f:
                body = f.read()
        except OSError:
            return super().send_head()  # 读失败（权限等）走默认 404/403

        _, ext = os.path.splitext(path)
        accept_enc = self.headers.get("Accept-Encoding", "")
        use_gzip = (
            "gzip" in accept_enc
            and ext in GZIP_EXT
            and len(body) >= GZIP_MIN_BYTES
        )
        if use_gzip:
            body = self._gzip_cached(path, body)

        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Last-Modified", self.date_time_string(st.st_mtime))
        self.send_header("Cache-Control", self._cache_control())
        self.end_headers()
        return body

    def _cache_control(self) -> str:
        path = self.path.split("?")[0]
        if path.startswith(IMMUTABLE_PREFIXES):
            return "public, max-age=604800, immutable"
        # 其余产物 no-cache：每次条件请求，304 用缓存、改版 200 即时生效
        return "no-cache"

    def end_headers(self):
        # Flutter WASM（--wasm 双模式）要求 crossOriginIsolated=true：
        # main.dart.wasm / skwasm(heavy).wasm 编译带 --import-shared-memory（SharedArrayBuffer），
        # 没有 COOP/COEP 头 → wasm 实例化失败 → 白屏 + 页面重载（2026-08-22 线上根因）。
        # COEP 用 credentialless（而非 require-corp）：同样提供 crossOriginIsolated，
        # 但不拦截跨源无凭据资源（后端媒体图片 / API 走 CORS，均不受影响）。
        # 2026-08-23：仅对 wasm 产物发 COOP/COEP——JS 模式（main.dart.js，现用形态）不需要
        # crossOriginIsolated，HTTP 下浏览器忽略 COOP 并报 "Cross-Origin-Opener-Policy header
        # has been ignored, because the URL's origin was untrustworthy"（纯 IP/HTTP 非可信源）。
        # 按页面主入口产物探测：请求 HTML 时查同目录是否存在 main.dart.wasm。
        want_wasm = False
        try:
            path = self.translate_path(self.path.split("?")[0])
            if os.path.isdir(path):
                want_wasm = os.path.exists(os.path.join(path, "main.dart.wasm"))
            else:
                want_wasm = os.path.exists(os.path.join(os.path.dirname(path), "main.dart.wasm"))
        except OSError:
            want_wasm = False
        if want_wasm:
            self.send_header("Cross-Origin-Opener-Policy", "same-origin")
            self.send_header("Cross-Origin-Embedder-Policy", "credentialless")
        super().end_headers()

    # 基类 do_GET/do_HEAD 假定 send_head 返回文件对象；本版返回 bytes（内存读入 + gzip），
    # 覆盖二者：bytes 直接写 wfile，文件对象走 copyfile。
    def do_GET(self):
        f = self.send_head()
        if f is None:
            return  # 304 / 404 已由 send_head 处理
        if isinstance(f, (bytes, bytearray)):
            self.wfile.write(f)
            return
        try:
            self.copyfile(f, self.wfile)
        finally:
            f.close()

    def do_HEAD(self):
        f = self.send_head()
        if f is None:
            return
        if isinstance(f, (bytes, bytearray)):
            return
        f.close()


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8082
    root_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
    os.chdir(root_dir)
    server = ThreadingServer(("0.0.0.0", port), Handler)
    print(f"Threading+gzip serving {root_dir} on :{port}")
    server.serve_forever()
