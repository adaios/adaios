package com.adaiadai.core.infrastructure.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.adaiadai.core.domain.learn.LearnFetchPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

/**
 * OutboundHostPolicy — 抓取出站白名单（RFC 20260912 §3.5 的 B8 边界落地，2026-09-12 对抗审查 P0-1 修复）。
 * <p>
 * <b>为什么需要</b>：抓取目标来自用户提交的 URL（以及第三方响应里的字幕/快照地址）。没有出站约束时，
 * {@code http://169.254.169.254/latest/meta-data/}（云元数据 → 可泄露临时凭证）、
 * {@code http://127.0.0.1:8080/actuator/env}、内网 VPC 地址都会被服务端照抓，正文还会落 `_raw/`
 * 并回显 —— 即**服务端替用户访问任意地址**（SSRF）。重定向（{@code followRedirects}）会把
 * 公网 URL 跳到内网，因此**每一跳都要复检**。
 * <p>
 * 判据两层：
 * <ol>
 *   <li><b>静态判定</b>（免费）：协议、端口、字面 IP 的私有/回环/链路本地/元数据网段、内网域名后缀</li>
 *   <li><b>DNS 解析后复检</b>（2026-09-12 补，对抗审查 P2-learn19 残余）：把域名解析出的**每个地址**
 *       都按上面的规则判一遍——防「域名看着正常、解析到 127.0.0.1 / 169.254.169.254」的
 *       DNS rebinding（也防某条 A 记录被换掉）。解析失败按用户输入错处理（人话）。</li>
 * </ol>
 * <p>
 * 说明：解析通过后，连接时仍可能被再次解析到别的地址（严格来说要靠连接级校验才彻底闭合）；
 * 对本产品（单用户、抓的是公开内容页）这一层已经堵住「构造域名指向内网」这条现实路径。
 * 需要时可再上「连接前二次校验」。
 */
@Component
public class OutboundHostPolicy implements LearnFetchPolicy {

    private static final Logger log = LoggerFactory.getLogger(OutboundHostPolicy.class);

    /** 明确禁止的主机名（云元数据服务等）。 */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "metadata", "metadata.google.internal", "metadata.tencentyun.com");

    /** 内网/本机专用域名后缀（私有集群命名）。 */
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            ".local", ".internal", ".localhost", ".lan", ".home.arpa");

    /** 允许的端口：公网 Web 标准端口（收窄面；B站/常见文章站都在这两个上）。 */
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

    private final boolean allowPrivateHosts;
    private final int maxRedirects;

    public OutboundHostPolicy(@Value("${adai.learn.fetch.allow-private-hosts:false}") boolean allowPrivateHosts,
                              @Value("${adai.learn.fetch.max-redirects:3}") int maxRedirects) {
        this.allowPrivateHosts = allowPrivateHosts;
        this.maxRedirects = Math.max(0, maxRedirects);
    }

    @Override
    public int maxRedirects() {
        return maxRedirects;
    }

    /** 是否放行（不产生副作用；出网前的最后一道闸）。 */
    @Override
    public boolean allows(String url) {
        return rejection(url) == null;
    }

    /**
     * 拒绝原因（人话，null = 放行）。
     * <p>
     * 返回的是**给用户看的话**：不能说「SSRF 拦截」，要说清「这个地址不像是能公开访问的内容页」。
     */
    @Override
    public String rejection(String url) {
        if (url == null || url.isBlank()) {
            return "链接不能为空";
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (Exception e) {
            return "这个链接看着不对，检查一下再发我";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "只支持 http/https 链接";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "这个链接没有可用域名，检查一下再发我";
        }
        if (allowPrivateHosts) {
            return null;
        }
        String lower = host.toLowerCase();
        if (BLOCKED_HOSTS.contains(lower) || BLOCKED_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            return "这个地址不像是能公开访问的内容页，我就不去抓了";
        }
        int port = uri.getPort();
        if (port != -1 && !ALLOWED_PORTS.contains(port)) {
            return "只支持标准网页端口（80/443）的链接";
        }
        if (isLiteralIp(lower)) {
            try {
                InetAddress addr = InetAddress.getByName(lower);
                if (isForbiddenAddress(addr)) {
                    return "这个地址不像是能公开访问的内容页，我就不去抓了";
                }
            } catch (Exception e) {
                return "这个地址解析不了，检查一下链接";
            }
            return null;
        }
        // DNS 解析后复检（防 DNS rebinding：域名看着正常、解析落到内网/元数据地址）
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(lower);
        } catch (Exception e) {
            return "这个地址解析不了，检查一下链接";
        }
        if (resolved.length == 0) {
            return "这个地址解析不了，检查一下链接";
        }
        for (InetAddress addr : resolved) {
            if (isForbiddenAddress(addr)) {
                log.warn("出站拒绝：域名解析到内网/受限地址 | host={} | resolved={}", host, addr.getHostAddress());
                return "这个地址不像是能公开访问的内容页，我就不去抓了";
            }
        }
        return null;
    }

    /** 该地址是否属于「绝不允许出站访问」的类别（回环/本机/链路本地/私有/组播/运营商级 NAT）。 */
    private static boolean isForbiddenAddress(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress() || isSharedAddressSpace(addr);
    }

    /** 100.64.0.0/10（运营商级 NAT）——InetAddress 的判定不覆盖，单独挡。 */
    private static boolean isSharedAddressSpace(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    /** 字面 IP（含 IPv6 冒号写法）：域名交给 DNS，不在本类做解析。 */
    private static boolean isLiteralIp(String host) {
        if (host.indexOf(':') >= 0) return true;           // IPv6 字面量
        if (host.startsWith("[") && host.endsWith("]")) return true;
        if (!host.contains(".")) return false;
        for (String part : host.split("\\.")) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
        }
        log.debug("字面 IP 地址：{}", host);
        return true;
    }
}
