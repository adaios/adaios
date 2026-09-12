package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.domain.learn.LearnSourceFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * LearnFetchService — 内容源抓取分流与留痕编排（RFC 20260912 §3.5，learn 抓取批 2026-09-12）。
 * <p>
 * 职责：**域名策略**（首期抓取范围 + 不做清单）+ **分流到具体抓取实现**（domain 端口，
 * 实现归 infrastructure/fetch）+ **源必留痕**（抓到的元数据/字幕/正文立即落
 * {@code learn/_raw/}，链接失效也可复原）。
 * <p>
 * 这是 D 形态对 B 死因的正面回答：B 把「拿到素材」留给用户（要自己搞字幕再粘贴），
 * D 由服务端完成（RFC 20260829 §9.2 死因 3）。
 * <p>
 * B8 边界：只读内容页、不触登录态接口、不绕付费墙与登录墙；社交平台首期不做自动抓取
 * （引导用户粘正文/截图），不强行绕过。
 */
@Service
public class LearnFetchService {

    private static final Logger log = LoggerFactory.getLogger(LearnFetchService.class);

    /**
     * 首期不做自动抓取的平台（RFC 20260912 §3.5「社交平台」「YouTube」两行）：
     * 反爬/登录墙/服务器网络不可达 → **明确告知并给替代路径**，不假装能抓（风险表对策）。
     */
    private static final Map<String, String> UNSUPPORTED = Map.ofEntries(
            Map.entry("youtube.com", "YouTube 从这台服务器连不上（海外网络），把字幕或正文粘进来更稳"),
            Map.entry("youtu.be", "YouTube 从这台服务器连不上（海外网络），把字幕或正文粘进来更稳"),
            Map.entry("mp.weixin.qq.com", "公众号有反爬，暂时抓不了，把正文粘进来我照样能整理"),
            Map.entry("zhihu.com", "知乎有反爬，暂时抓不了，把正文粘进来我照样能整理"),
            Map.entry("xiaohongshu.com", "小红书要登录，暂时抓不了，截图发我一样能整理"),
            Map.entry("weibo.com", "微博要登录，暂时抓不了，把正文或截图发我一样能整理"),
            Map.entry("x.com", "X/Twitter 要登录，暂时抓不了，把正文或截图发我一样能整理"),
            Map.entry("twitter.com", "X/Twitter 要登录，暂时抓不了，把正文或截图发我一样能整理"),
            Map.entry("douyin.com", "抖音要登录，暂时抓不了，截图发我一样能整理"),
            Map.entry("toutiao.com", "这个平台有反爬，暂时抓不了，把正文粘进来我照样能整理"));

    private final List<LearnSourceFetcher> fetchers;
    private final LearnCardRepository repository;

    public LearnFetchService(List<LearnSourceFetcher> fetchers, LearnCardRepository repository) {
        this.fetchers = fetchers;
        this.repository = repository;
    }

    /**
     * 抓取并留痕（对外主入口）。
     *
     * @param userId 用户（留痕落 {@code data/{userId}/learn/_raw/}）
     * @param url    内容页链接
     * @return 抓取结果
     * @throws LearnException 平台不支持 / 抓取失败（消息为人话）
     */
    public LearnSource fetchAndArchive(String userId, String url) {
        LearnSource source = fetch(url);
        archive(userId, source);
        return source;
    }

    /** 抓取（不含留痕；策略 + 分流）。 */
    public LearnSource fetch(String url) {
        if (url == null || url.isBlank()) {
            throw new LearnException("链接不能为空");
        }
        String host = hostOf(url);
        if (host == null) {
            throw new LearnException("这个链接看着不对，检查一下再发我");
        }
        String blocked = unsupportedReason(host);
        if (blocked != null) {
            throw new LearnException(blocked);
        }
        for (LearnSourceFetcher fetcher : fetchers) {
            if (fetcher.supports(url)) {
                return fetcher.fetch(url);
            }
        }
        throw new LearnException("这个链接现在读不了，把正文粘进来我照样能整理");
    }

    /**
     * 下载音频字节（转写链路用）。
     *
     * @param platform 源平台（决定用哪个抓取实现下音频——B站要带 Referer，否则 403）
     * @param audioUrl {@code LearnSource.audioUrl()}
     */
    public byte[] downloadAudio(String platform, String audioUrl) {
        for (LearnSourceFetcher fetcher : fetchers) {
            if (fetcher.platform().equals(platform)) {
                return fetcher.downloadAudio(audioUrl);
            }
        }
        throw new LearnException("这个来源暂时不支持音频下载");
    }

    /** 源必留痕：抓到的原始素材立即落 {@code learn/_raw/}（失败不影响主流程，仅告警）。 */
    public void archive(String userId, LearnSource source) {
        if (source == null) return;
        for (LearnSource.RawAsset asset : source.rawAssets()) {
            try {
                repository.saveRaw(userId, asset.name(), asset.content());
            } catch (Exception e) {
                log.warn("learn 原始素材留痕失败 | userId={} | name={} | {}", userId, asset.name(), e.getMessage());
            }
        }
    }

    private static String unsupportedReason(String host) {
        for (Map.Entry<String, String> e : UNSUPPORTED.entrySet()) {
            if (host.equals(e.getKey()) || host.endsWith("." + e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
}
