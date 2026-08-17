package com.adaiadai.core.kernel.plugin;

import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.context.engine.ContextContributor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PluginService — 按账号解析启用插件（RFC 20260814 插件门控，第二步）。
 * <p>
 * 插件载体 = Account.plugins（adai-admin 后台控制）。新用户默认空（只有基础服务），
 * seed adai = [trading, project]（owner 受控插件）。未知插件名过滤，防脏数据。
 * <p>
 * 面向消费方：ContextEngine（知识/贡献者门控）、FeedAppService（行情卡）、
 * RecordController/QuestionAppService（D5 domain 收敛）、MeController（前端门控）、
 * TradingController（promote 反哺仅插件用户）。
 * <p>
 * 性能（08-15 后端×6，2026-08-17）：enabledPlugins 被消费方高频调用（每请求多次），
 * 每次读 accounts.json 全量解析 → 30 秒 TTL 缓存；admin 改插件后 invalidate 立即生效。
 */
@Component
public class PluginService {

    private static final long CACHE_TTL_MS = 30_000;

    private final AccountRepository accountRepository;
    private final PluginRegistry registry;
    private final Map<String, CacheEntry> pluginCache = new ConcurrentHashMap<>();

    private record CacheEntry(Set<String> plugins, long cachedAt) {}

    public PluginService(AccountRepository accountRepository, PluginRegistry registry) {
        this.accountRepository = accountRepository;
        this.registry = registry;
    }

    /** 用户启用的插件集合（未知插件名被过滤）。账号不存在 → 空（只给基础服务）。30 秒 TTL 缓存。 */
    public Set<String> enabledPlugins(String userId) {
        CacheEntry cached = pluginCache.get(userId);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt() < CACHE_TTL_MS) {
            return cached.plugins();
        }
        Set<String> plugins = accountRepository.findById(userId)
                .map(a -> a.plugins().stream().filter(registry::isValid).collect(Collectors.toSet()))
                .orElse(Set.of());
        pluginCache.put(userId, new CacheEntry(Set.copyOf(plugins), System.currentTimeMillis()));
        return plugins;
    }

    /** admin 改插件后调用：立即失效缓存，下一次读取重解析（2026-08-17）。 */
    public void invalidate(String userId) {
        pluginCache.remove(userId);
    }

    public boolean hasPlugin(String userId, String plugin) {
        return plugin != null && enabledPlugins(userId).contains(plugin);
    }

    /**
     * D5：domain 判定门控——AI 判定的 domain 若属于用户未启用插件，收敛为 life。
     * （无插件用户不应出现 trading/project 标注；知识/上下文注入已按插件门控）
     */
    public String gateDomain(String userId, String domain) {
        if (domain == null) return "life";
        if (PluginRegistry.PLUGIN_TRADING.equals(domain) && !hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return "life";
        }
        if (PluginRegistry.PLUGIN_PROJECT.equals(domain) && !hasPlugin(userId, PluginRegistry.PLUGIN_PROJECT)) {
            return "life";
        }
        // P3（2026-08-17）：未知 domain 原样放行会保留 AI 越界值 → 收敛 life（白名单，防脏数据进持久化）
        if (!PluginRegistry.PLUGIN_TRADING.equals(domain) && !PluginRegistry.PLUGIN_PROJECT.equals(domain)
                && !"life".equals(domain)) {
            return "life";
        }
        return domain;
    }

    // ── 映射（透传 PluginRegistry，供 ContextEngine 门控注入）──

    public String pluginForKnowledge(String sourceName) {
        return registry.pluginForKnowledge(sourceName);
    }

    public String pluginForContributor(ContextContributor contributor) {
        return registry.pluginForContributor(contributor);
    }
}
