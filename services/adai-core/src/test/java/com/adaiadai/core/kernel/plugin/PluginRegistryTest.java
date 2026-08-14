package com.adaiadai.core.kernel.plugin;

import com.adaiadai.core.domain.project.ProjectContextContributor;
import com.adaiadai.core.domain.trading.MarketContextContributor;
import com.adaiadai.core.domain.trading.TradingContextContributor;
import com.adaiadai.core.kernel.context.engine.ContextContributor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PluginRegistry — 插件名常量 + 校验 + 知识源/贡献者归属映射（RFC 20260814 T2.3）。
 */
class PluginRegistryTest {

    private final PluginRegistry registry = new PluginRegistry();

    @Test
    void knownPlugins_areValid() {
        assertTrue(registry.isValid(PluginRegistry.PLUGIN_TRADING));
        assertTrue(registry.isValid(PluginRegistry.PLUGIN_PROJECT));
        assertEquals(Set.of("trading", "project"), registry.all());
    }

    @Test
    void lifeAndUnknown_areNotPlugins() {
        assertFalse(registry.isValid("life"), "life 是基础服务，不是插件");
        assertFalse(registry.isValid("hacking"));
        assertFalse(registry.isValid(null));
    }

    @Test
    void pluginForKnowledge_mapsByName() {
        assertEquals(PluginRegistry.PLUGIN_TRADING, registry.pluginForKnowledge("trading"));
        assertEquals(PluginRegistry.PLUGIN_PROJECT, registry.pluginForKnowledge("project"));
        assertNull(registry.pluginForKnowledge("life"), "life 知识源不归插件");
    }

    @Test
    void pluginForContributor_mapsTradingAndProjectDomains() {
        assertEquals(PluginRegistry.PLUGIN_TRADING, registry.pluginForContributor(new MarketContextContributor(null, null)));
        assertEquals(PluginRegistry.PLUGIN_TRADING, registry.pluginForContributor(new TradingContextContributor(null, null)));
        assertEquals(PluginRegistry.PLUGIN_PROJECT, registry.pluginForContributor(new ProjectContextContributor(null, null)));

        // 非插件贡献者（life/默认）不门控
        ContextContributor lifeContributor = new ContextContributor() {
            @Override public boolean supports(String scene) { return false; }
            @Override public String enrich(String userId, String identityRef, com.adaiadai.core.kernel.record.ContentRecord record) { return ""; }
        };
        assertNull(registry.pluginForContributor(lifeContributor));
        assertNull(registry.pluginForContributor(new com.adaiadai.core.kernel.context.engine.DefaultContextContributor()));
    }
}
