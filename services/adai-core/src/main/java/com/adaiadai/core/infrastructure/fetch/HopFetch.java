package com.adaiadai.core.infrastructure.fetch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnFetchPolicy;

/**
 * HopFetch — 抓取出站的公共骨架（2026-09-13 平台抓取放开批）。
 * <p>
 * <b>为什么抽出来</b>：本批新增了微博 / 公众号 / 头条三个抓取器，它们与既有 {@link ArticleFetcher}
 * 需要**完全同一套**出站纪律——逐跳过白名单、手工跟跳转、退避重试、响应体上限。复制四份意味着
 * 「出站安全」这件事会出现四个可能各自漂移的版本；而这类漂移正是 P0-1（SSRF）那类事故的温床。
 * 纪律收敛到一处，新平台接入就只剩「怎么解析」，不再需要重新论证「怎么出网」。
 * <p>
 * <b>出站安全（对齐 {@code OutboundHostPolicy} 的既定口径，逐条不得放松）</b>：
 * <ol>
 *   <li>每一跳都过 {@link LearnFetchPolicy#rejection}（协议/端口/私有网段/元数据地址 + DNS 解析后复检）</li>
 *   <li><b>关闭自动重定向</b>，手工跟最多 {@code maxRedirects} 跳并在**每跳前复检**
 *       （否则一个公网 URL 一跳就能落到内网）</li>
 *   <li>响应体走 {@link HttpBodies} 的上限保护，不把大文件整体读进 2核4G 的生产实例</li>
 * </ol>
 */
final class HopFetch {

    /**
     * 「页面被拒 / 服务端错误」标记异常：**只有这一类**值得走兜底（如 Web Archive 快照）。
     * <p>
     * 反例（不该兜底、要保留原始原因）：出站策略拒绝、重定向超限、页面过大——兜底服务多半同样
     * 被拒，只会把「为什么失败」盖成一句通用话术。
     */
    static final class BlockedException extends LearnException {
        BlockedException(String message) {
            super(message);
        }
    }

    private final HttpClient client;
    private final LearnFetchPolicy hostPolicy;
    private final int maxRetry;

    HopFetch(LearnFetchPolicy hostPolicy, int maxRetry) {
        this.hostPolicy = hostPolicy;
        this.maxRetry = Math.max(1, maxRetry);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 关闭自动重定向：跳转必须逐跳过白名单（对抗审查 P0-1）
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 逐跳抓取一个页面，返回最终 200 的响应体。
     *
     * @param url     目标链接
     * @param headers 额外请求头（UA / Referer / X-Requested-With 等，按平台给）
     * @throws BlockedException 403/401/429/503 或 5xx（**可兜底**）
     * @throws LearnException   其余失败（人话，fail-visible，不产废卡）
     */
    String page(String url, Map<String, String> headers) {
        String current = url;
        for (int hop = 0; hop <= hostPolicy.maxRedirects(); hop++) {
            String rejection = hostPolicy.rejection(current);
            if (rejection != null) {
                throw new LearnException(rejection);
            }
            HttpBodies.Fetched fetched = once(current, headers);
            if (fetched.redirect()) {
                String location = fetched.location();
                if (location == null || location.isBlank()) {
                    throw new LearnException("页面给的跳转地址是空的，抓取失败");
                }
                try {
                    current = URI.create(current).resolve(location.strip()).toString();
                } catch (Exception e) {
                    throw new LearnException("页面跳转地址不合法，抓取失败");
                }
                continue;   // 下一跳重新过白名单
            }
            int code = fetched.status();
            if (code == 403 || code == 401 || code == 429 || code == 503) {
                throw new BlockedException("页面拒绝访问（" + code + "）");
            }
            if (code >= 500) {
                throw new BlockedException("页面返回 " + code + "，抓取失败");
            }
            if (code != 200) {
                throw new LearnException("页面返回 " + code + "，抓取失败");
            }
            String body = fetched.text();
            if (body == null || body.isBlank()) {
                throw new LearnException("页面返回空内容");
            }
            return body;
        }
        throw new LearnException("这个链接跳转太多次了，先不抓");
    }

    /**
     * 单次请求（含 5xx / 网络退避重试），**不跟跳转**——调用方自己决定跳转语义。
     * <p>
     * 用于「短链解析要拿到 Location 本身」或「快照查询」这类不需要逐跳的场合。
     */
    HttpBodies.Fetched once(String url, Map<String, String> headers) {
        String rejection = hostPolicy.rejection(url);
        if (rejection != null) {
            throw new LearnException(rejection);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .GET();
        headers.forEach(builder::header);
        HttpRequest request = builder.build();

        java.io.IOException last = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                HttpBodies.Fetched fetched = HttpBodies.getText(client, request, HttpBodies.MAX_TEXT_BYTES);
                if (fetched.status() < 500 || attempt == maxRetry) {
                    return fetched;
                }
            } catch (HttpBodies.TooLargeException e) {
                throw new LearnException("这个页面太大了，先不抓（把正文粘进来我照样能整理）");
            } catch (java.io.IOException e) {
                last = e;
                if (attempt == maxRetry) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LearnException("抓取被中断，请重试");
            }
            sleepBackoff(attempt);
        }
        throw new LearnException("连不上这个站点（"
                + (last == null ? "服务端错误" : last.getMessage()) + "），请稍后重试");
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
