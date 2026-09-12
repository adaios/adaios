package com.adaiadai.core.domain.learn;

/**
 * LearnFetchPolicy — 抓取出站策略端口（RFC 20260912 §3.5 的 B8 边界，2026-09-12 对抗审查 P0-1）。
 * <p>
 * 端口定义归 domain（对齐 {@link LearnSourceFetcher} 的依赖倒置：application 只依赖本接口，
 * 具体判定逻辑归 {@code infrastructure/fetch/OutboundHostPolicy}）——否则 application 会反向
 * 依赖 infrastructure，违反 conventions C7。
 * <p>
 * 语义：服务端**不能替用户访问任意地址**。内网/回环/链路本地/云元数据地址、非标准端口、
 * 非 http(s) 协议一律先挡在门口；重定向的每一跳都要复检。
 */
public interface LearnFetchPolicy {

    /**
     * 拒绝原因（**人话**，null = 放行）。
     * <p>
     * 返回给用户看的文案要说明「为什么不去抓」，不要暴露安全术语。
     */
    String rejection(String url);

    /** 是否放行。 */
    default boolean allows(String url) {
        return rejection(url) == null;
    }

    /** 允许跟随的最大重定向跳数（每跳都要复检）。 */
    int maxRedirects();
}
