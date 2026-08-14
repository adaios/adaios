package com.adaiadai.core.kernel.plugin;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PluginRegistry — 插件注册表（RFC 20260814 Domain=插件模型，第二步）。
 * <p>
 * 插件 = adai 拥有并受控开放的 Domain（trading/project），启用载体 = Account.plugins
 * （adai-admin 后台控制）。Kernel 基础服务（记录/问答/记忆/档案/时间线/搜索/待办）不是插件，
 * 人人都有，不在此表。
 * <p>
 * 职责：插件名常量 + 校验 + 把 {@link KnowledgeSource} / {@link ContextContributor} 归到所属插件，
 * 供 ContextEngine 按用户 enabledPlugins 门控注入。
 */
@Component
public class PluginRegistry {

    public static final String PLUGIN_TRADING = "trading";
    public static final String PLUGIN_PROJECT = "project";

    private static final Set<String> PLUGINS = Set.of(PLUGIN_TRADING, PLUGIN_PROJECT);

    /** 该名称是否为已知插件（trading/project）。life 等基础服务不是插件。 */
    public boolean isValid(String name) {
        return name != null && PLUGINS.contains(name);
    }

    /** 全部插件名（trading / project）。 */
    public Set<String> all() {
        return PLUGINS;
    }

    /**
     * 知识源 name() → 所属插件；非插件基础服务（life）返回 null。
     * trading/project 知识源以 name 为插件名，直接映射。
     */
    public String pluginForKnowledge(String sourceName) {
        return isValid(sourceName) ? sourceName : null;
    }

    /**
     * 贡献者 → 所属插件；非插件基础服务（life / 默认）返回 null。
     * <p>
     * 按实现类简单名映射（trading 域 2 个 / project 域 1 个）——Domain 贡献者不暴露插件元数据，
     * 归属集中在注册表一处维护；新增插件域贡献者时在此登记。注意：这些 Bean 无 AOP 代理
     * （无 @Transactional），getClass() 即真实类，非 CGLIB 代理类。
     */
    public String pluginForContributor(ContextContributor contributor) {
        String simple = contributor.getClass().getSimpleName();
        return switch (simple) {
            case "MarketContextContributor", "TradingContextContributor" -> PLUGIN_TRADING;
            case "ProjectContextContributor" -> PLUGIN_PROJECT;
            default -> null;
        };
    }
}
