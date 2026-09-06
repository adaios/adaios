package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.LearnDigestAppService;
import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LearnController — 学习沉淀 REST API（RFC 20260829 learn 插件，V1 后端流水线）。
 * <p>
 * 端点：
 * POST /api/v1/learn/cards   喂入素材 → AI 卡片化 → 落 data/{userId}/learn/
 * GET  /api/v1/learn/cards   卡片列表（?type= 筛选）
 * GET  /api/v1/learn/tree    资产树（learn 按 type 分组）
 * <p>
 * 全部需 learn 插件（403）；X-User-Id 隔离（data/{userId}/learn/）。
 * 独立端点喂入（2026-09-06 用户拍板：仿截图入账先例，learn 消化是动作不是记录，
 * 不污染记录/记忆/Feed）。
 */
@RestController
@RequestMapping("/api/v1/learn")
public class LearnController {

    private static final Logger log = LoggerFactory.getLogger(LearnController.class);

    private final LearnDigestAppService digestService;
    private final PluginService pluginService;

    public LearnController(LearnDigestAppService digestService, PluginService pluginService) {
        this.digestService = digestService;
        this.pluginService = pluginService;
    }

    /** 喂入素材 → AI 消化成学习卡片并落盘。 */
    @PostMapping("/cards")
    public ResponseEntity<?> digest(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody LearnDigestRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (body.type() != null && !body.type().isBlank() && !LearnCard.isValidType(body.type())) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        LearnCard card = digestService.digest(userId, body.content(),
                body.type(), body.platform(), body.author(), body.url(), body.published());
        return ResponseEntity.ok(card);
    }

    /** 卡片列表（?type=ai/trading/other 筛选；缺省全部）。 */
    @GetMapping("/cards")
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String type) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (type != null && !type.isBlank() && !LearnCard.isValidType(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        List<LearnCard> cards = type != null && !type.isBlank()
                ? listByType(userId, type)
                : allCards(userId);
        return ResponseEntity.ok(cards);
    }

    /** 单篇卡片全文（?type=ai/trading/other&title= 精确标题）；不存在 → 400 人话。 */
    @GetMapping("/card")
    public ResponseEntity<?> card(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String type,
            @RequestParam String title) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (!LearnCard.isValidType(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        return ResponseEntity.ok(digestService.detail(userId, type, title));
    }

    /** 资产树：learn 按 type 分组（卡片清单）。 */
    @GetMapping("/tree")
    public ResponseEntity<?> tree(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(digestService.tree(userId));
    }

    private List<LearnCard> listByType(String userId, String type) {
        return digestService.list(userId, type);
    }

    private List<LearnCard> allCards(String userId) {
        java.util.List<LearnCard> all = new java.util.ArrayList<>();
        for (String t : List.of(LearnCard.TYPE_AI, LearnCard.TYPE_TRADING, LearnCard.TYPE_OTHER)) {
            all.addAll(digestService.list(userId, t));
        }
        return all;
    }

    private ResponseEntity<?> requireLearnPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_LEARN)) {
            return ResponseEntity.status(403).body(Map.of("error", "learn 插件未启用，无法使用学习功能"));
        }
        return null;
    }

    /** 喂入请求：content 素材原文必填；type/platform/author/url/published 可选。 */
    public record LearnDigestRequest(
            @NotBlank(message = "素材内容不能为空")
            @Size(min = 1, max = 50000, message = "素材过长（限 50000 字），建议分段消化")
            String content,
            @Size(max = 20, message = "类型仅 ai/trading/other")
            String type,
            @Size(max = 50, message = "平台名过长")
            String platform,
            @Size(max = 100, message = "作者名过长")
            String author,
            @Size(max = 500, message = "链接过长")
            String url,
            @Size(max = 30, message = "发布日期格式 yyyy-MM-dd")
            String published) {}
}
