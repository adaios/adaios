package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MeController — 当前用户自身资源（RFC 20260814 插件门控，前端模块显隐用）。
 * <p>
 * GET /api/v1/me/plugins → 当前用户启用插件名列表（adai = [project, trading]，新用户 = []）。
 * 不属于 admin/accounts 鉴权范围，产品端任意登录用户可读（值本身是用户自己的启用插件）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final PluginService pluginService;

    public MeController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping("/plugins")
    public List<String> plugins(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return pluginService.enabledPlugins(userId).stream().sorted().toList();
    }
}
