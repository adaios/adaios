package com.adaiadai.core.kernel.search;

import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.ImageQaFormatter;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SearchService — 全文搜索服务。
 * <p>
 * 遍历所有 records 文件，对标题和正文进行模糊匹配。
 * MVP 阶段不做索引，直接线性扫描。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final RecordRepository recordRepository;
    /** §11.3 六面闸门（2026-10-06）：关插件时交易记录不进搜索结果（读侧过滤；数据保留不删）。可空=兼容构造。 */
    private final PluginService pluginService;

    /** 主构造（Spring 注入）：带插件门控——无 trading 插件用户搜不到交易记录。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SearchService(RecordRepository recordRepository, PluginService pluginService) {
        this.recordRepository = recordRepository;
        this.pluginService = pluginService;
    }

    /** 兼容构造（测试/旧调用）：不接插件门控——行为与历史版本逐字一致。 */
    public SearchService(RecordRepository recordRepository) {
        this(recordRepository, null);
    }

    /**
     * 全文搜索（限定该用户记录）。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param query  搜索关键词
     * @return 匹配的记录列表
     */
    public List<SearchResult> search(String userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String q = query.trim().toLowerCase();
        List<ContentRecord> all = recordRepository.findAll(userId);
        // 图文一体（2026-09-22）：薄附件不进搜索结果——它们的 summary 是哨兵「图片附件」（系统视角）
        java.util.Set<String> attachmentIds =
                com.adaiadai.core.kernel.record.MediaAttachments.referencedIds(all);

        return all.stream()
                .filter(r -> !attachmentIds.contains(r.id()))
                .filter(r -> !hiddenTrading(userId, r))
                .filter(r -> matches(r, q))
                .map(r -> new SearchResult(
                        r.id(),
                        r.type(),
                        naturalTitle(r),
                        highlight(naturalContent(r), q),
                        r.tags(),
                        r.createdAt()
                ))
                .toList();
    }

    /** §11.3：关插件时交易记录过滤（读侧；存量与新增一致地被隐藏，可逆——重开即恢复）。 */
    private boolean hiddenTrading(String userId, ContentRecord r) {
        return pluginService != null && "trading".equals(r.domain())
                && !pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING);
    }

    private boolean matches(ContentRecord record, String query) {
        if (record.title() != null && record.title().toLowerCase().contains(query)) {
            return true;
        }
        if (record.content() != null && record.content().toLowerCase().contains(query)) {
            return true;
        }
        if (record.tags() != null) {
            for (String tag : record.tags()) {
                if (tag.toLowerCase().contains(query)) {
                    return true;
                }
            }
        }
        return record.summary() != null && record.summary().toLowerCase().contains(query);
    }

    /**
     * 展示自然化（第一原则，REVIEW P1-W3）：image_qa → 用户问句；image → VLM 总结。
     * 搜索与 Feed/Timeline 同口径，杜绝【多图问答】【备注】问：/答： 标签暴露。
     */
    private String naturalTitle(ContentRecord r) {
        if ("image_qa".equals(r.type()) && r.content() != null) {
            String[] natural = ImageQaFormatter.naturalize(r.content());
            if (natural != null) return natural[0];
        }
        if ("image".equals(r.type()) && r.summary() != null && !r.summary().isBlank()) {
            return r.summary();
        }
        return r.title() != null ? r.title() : "";
    }

    /** 展示自然化正文：image/image_qa 去标签后截取片段。 */
    private String naturalContent(ContentRecord r) {
        String content = r.content();
        if (content == null) return "";
        if ("image".equals(r.type())) {
            String natural = ImageQaFormatter.naturalizeImage(content);
            if (natural != null && !natural.isBlank()) return natural;
        } else if ("image_qa".equals(r.type())) {
            String[] natural = ImageQaFormatter.naturalize(content);
            if (natural != null) return natural[1];
        }
        return content;
    }

    /**
     * 在内容中截取匹配片段（前后各 30 字）。
     */
    private String highlight(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int idx = content.toLowerCase().indexOf(query);
        if (idx < 0) {
            return content.substring(0, Math.min(content.length(), 100));
        }
        int start = Math.max(0, idx - 30);
        int end = Math.min(content.length(), idx + query.length() + 30);
        String snippet = content.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < content.length()) snippet = snippet + "...";
        return snippet;
    }
}
