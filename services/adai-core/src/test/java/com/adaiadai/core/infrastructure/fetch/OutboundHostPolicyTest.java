package com.adaiadai.core.infrastructure.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OutboundHostPolicyTest — 抓取出站白名单（2026-09-12 对抗审查 P0-1 修复）。
 * <p>
 * 为什么值得单测：抓取目标来自**用户提交的 URL**，等于让服务端替用户访问任意地址。没有这道闸，
 * 云元数据地址（169.254.169.254 → 可取临时凭证）、本机 actuator、内网 VPC 都能被读到并回显。
 * 本类锁定「哪些地址不许去」，并锁定**拒绝话术是人话**（不能说安全术语给用户看）。
 */
class OutboundHostPolicyTest {

    private final OutboundHostPolicy strict = new OutboundHostPolicy(false, 3);

    // ── 放行：正常公网内容页 ──

    @Test
    void allows_publicContentPages() {
        assertNull(strict.rejection("https://www.bilibili.com/video/BV1xx411c7mD"));
        assertNull(strict.rejection("https://martinfowler.com/articles/exploring-gen-ai.html"));
        assertNull(strict.rejection("http://example.com/a"));
        assertTrue(strict.allows("https://mp.weixin.qq.com/s/x"), "域名黑名单交给 LearnFetchService 的「首期不做」清单");
    }

    // ── 拒绝：本机 / 内网 / 元数据 ──

    @Test
    void rejects_localhostAndLoopback() {
        assertNotNull(strict.rejection("http://localhost:8080/actuator/env"));
        assertNotNull(strict.rejection("http://127.0.0.1:8080/admin"));
        assertNotNull(strict.rejection("http://[::1]:8080/x"));
    }

    @Test
    void rejects_cloudMetadataAddress() {
        // 云元数据（腾讯云/阿里云同址）——不挡就能被读出临时凭证
        assertNotNull(strict.rejection("http://169.254.169.254/latest/meta-data/"));
        assertNotNull(strict.rejection("http://metadata.tencentyun.com/latest/meta-data/"));
        assertNotNull(strict.rejection("http://metadata.google.internal/computeMetadata/v1/"));
    }

    @Test
    void rejects_privateRanges() {
        assertNotNull(strict.rejection("http://10.0.0.5/internal"));
        assertNotNull(strict.rejection("http://172.16.3.9/internal"));
        assertNotNull(strict.rejection("http://192.168.1.1/router"));
        assertNotNull(strict.rejection("http://100.64.0.1/cgnat"));
    }

    @Test
    void rejects_internalHostnameSuffixes() {
        assertNotNull(strict.rejection("http://db.internal/x"));
        assertNotNull(strict.rejection("http://printer.local/x"));
    }

    // ── 拒绝：协议与端口收窄 ──

    @Test
    void rejects_nonWebSchemes() {
        assertNotNull(strict.rejection("file:///etc/passwd"));
        assertNotNull(strict.rejection("ftp://example.com/x"));
        assertNotNull(strict.rejection("gopher://example.com/x"));
    }

    @Test
    void rejects_nonStandardPorts() {
        assertNotNull(strict.rejection("http://example.com:8080/x"), "只放行 80/443，收窄面");
        assertNotNull(strict.rejection("http://example.com:22/x"));
        assertNull(strict.rejection("https://example.com:443/x"));
    }

    @Test
    void rejects_blankAndMalformed() {
        assertNotNull(strict.rejection(null));
        assertNotNull(strict.rejection("   "));
        assertNotNull(strict.rejection("not a url"));
        assertNotNull(strict.rejection("https:///no-host"));
    }

    // ── 拒绝话术：人话，不暴露安全术语 ──

    @Test
    void rejectionMessages_areHumanReadable_notSecurityJargon() {
        String msg = strict.rejection("http://169.254.169.254/latest/meta-data/");

        assertFalse(msg.toLowerCase().contains("ssrf"));
        assertFalse(msg.contains("白名单"));
        assertFalse(msg.contains("内网"));
        assertTrue(msg.contains("公开访问"), "要说清「为什么不去抓」而不是甩术语：" + msg);
    }

    // ── 域名后缀匹配（P0-1 附带：evilbilibili.com 不能算 B站）──

    @Test
    void hostWhitelist_requiresDotBoundary() {
        assertTrue(BilibiliFetcher.hostMatches("www.bilibili.com", java.util.List.of("bilibili.com")));
        assertTrue(BilibiliFetcher.hostMatches("bilibili.com", java.util.List.of("bilibili.com")));
        assertTrue(BilibiliFetcher.hostMatches("b23.tv", java.util.List.of("b23.tv")));
        assertFalse(BilibiliFetcher.hostMatches("evilbilibili.com", java.util.List.of("bilibili.com")),
                "少一个点就会把 evilbilibili.com 认成 B站");
        assertFalse(BilibiliFetcher.hostMatches("bilibili.com.evil.com", java.util.List.of("bilibili.com")));
        assertFalse(BilibiliFetcher.hostMatches(null, java.util.List.of("bilibili.com")));
    }

    // ── 测试逃逸开关 ──

    @Test
    void allowPrivateHosts_isTestOnlyEscapeHatch() {
        OutboundHostPolicy permissive = new OutboundHostPolicy(true, 3);

        assertNull(permissive.rejection("http://127.0.0.1:51234/mock"), "单测要能指向本地 mock server");
        assertNotNull(permissive.rejection("file:///etc/passwd"), "但协议检查不放开");
    }

    @Test
    void maxRedirects_configurable() {
        assertTrue(new OutboundHostPolicy(false, 0).maxRedirects() == 0);
        assertTrue(new OutboundHostPolicy(false, 5).maxRedirects() == 5);
        assertTrue(new OutboundHostPolicy(false, -1).maxRedirects() == 0, "负数收敛为 0");
    }

    // ── DNS 解析后复检（2026-09-12 对抗审查 P2-learn19 残余：DNS rebinding）──

    @Test
    void rejectsDomainResolvingToLoopback_evenIfNameLooksPublic() {
        // 构造一个「名字看着正常、解析到回环」的域名：用 *.localhost 之外的路径难造，
        // 这里用本机回环的等价写法验证解析层判定（127.0.0.1 的域名形态）
        assertNotNull(strict.rejection("http://localtest.me/"), "解析到 127.0.0.1 的域名要拒（DNS rebinding 面）");
    }

    @Test
    void allowsPublicDomain_afterResolution() {
        // 解析出的公网地址照常放行（不能因为加了复检把正常抓取拦死）。
        // 注意：本用例需要 DNS 可用；离线环境会走「解析失败」分支，那也属于如实处理，故一并接受。
        String reason = strict.rejection("https://example.com/article");
        assertTrue(reason == null || reason.contains("解析不了"),
                "公网域名放行（无 DNS 环境按解析失败处理）：" + reason);
    }

    @Test
    void unresolvableDomain_getsHumanMessage() {
        String reason = strict.rejection("https://this-domain-should-not-exist-adaios-test.invalid/");
        assertNotNull(reason);
        assertTrue(reason.contains("解析不了"), "人话而不是异常：" + reason);
    }
}
