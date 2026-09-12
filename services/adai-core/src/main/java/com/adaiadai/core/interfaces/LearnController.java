package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.LearnCandidateAppService;
import com.adaiadai.core.application.LearnDigestAppService;
import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardPatch;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * LearnController — 学习沉淀 REST API（RFC 20260829 learn 插件 V1/V2 + RFC 20260912 D 形态抓取批）。
 * <p>
 * 端点：
 * POST /api/v1/learn/digest        喂入链接/素材 → 服务端抓取（+转写）→ AI 卡片化 → 落 data/{userId}/learn/
 * GET  /api/v1/learn/digest/status 消化任务状态（阶段 + 费用预估）
 * POST /api/v1/learn/digest/confirm 转写费用确认（无字幕视频花钱前经用户点头）
 * GET  /api/v1/learn/digest/quota  本月转写用量与剩余额度
 * POST /api/v1/learn/cards         （兼容别名，同 /digest；2026-09-10 先例保留）
 * GET  /api/v1/learn/cards         卡片列表（?type= 筛选）
 * GET  /api/v1/learn/card          单篇卡片全文（?type=&title=）
 * GET  /api/v1/learn/tree          资产树（learn 按 type 分组）
 * PATCH /api/v1/learn/cards/status 复习状态流转（V2）
 * PATCH /api/v1/learn/cards        卡片正文编辑（V2）
 * POST  /api/v1/learn/cards/candidate        trading 卡片反哺成规则候选（V2 批 3）
 * GET   /api/v1/learn/cards/candidates       候选列表（审核）
 * DELETE /api/v1/learn/cards/candidates      删除候选（?title=，幂等）
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
    private final LearnCandidateAppService candidateService;
    private final com.adaiadai.core.application.LearnReviewPushService reviewPushService;
    private final com.adaiadai.core.application.LearnTranscriptionService transcriptionService;
    private final PluginService pluginService;

    public LearnController(LearnDigestAppService digestService,
                           LearnCandidateAppService candidateService,
                           com.adaiadai.core.application.LearnReviewPushService reviewPushService,
                           com.adaiadai.core.application.LearnTranscriptionService transcriptionService,
                           PluginService pluginService) {
        this.digestService = digestService;
        this.candidateService = candidateService;
        this.reviewPushService = reviewPushService;
        this.transcriptionService = transcriptionService;
        this.pluginService = pluginService;
    }

    /**
     * 喂入链接或素材 → AI 消化成学习卡片（提交式：立即返回 status，后台消化 + 轮询 /digest/status）。
     * <p>
     * 2026-09-12 抓取批：{@code url} 走**服务端抓取**（B站/文章），这是 D 形态的核心——
     * 用户只丢链接，最费力的一步（搞字幕/原文）交给阿呆；{@code content} 保留为降级路径
     * （抓不到时用户粘正文）。
     */
    @PostMapping("/digest")
    public ResponseEntity<?> digest(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody LearnDigestRequest body) {
        return doDigest(userId, body);
    }

    /** 兼容别名（2026-09-10 喂入入口批的端点名，双端旧版本仍在用）。 */
    @PostMapping("/cards")
    public ResponseEntity<?> digestLegacy(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody LearnDigestRequest body) {
        return doDigest(userId, body);
    }

    private ResponseEntity<?> doDigest(String userId, LearnDigestRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (body == null || (isBlank(body.url()) && isBlank(body.content()))) {
            return ResponseEntity.badRequest().body(Map.of("error", "请给我一个链接，或者把素材内容粘进来"));
        }
        if (!isBlank(body.type()) && !LearnCard.isValidType(body.type())) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        LearnDigestAppService.DigestSubmitResult result = digestService.submit(userId,
                new LearnDigestAppService.DigestRequest(body.url(), body.content(), body.type(),
                        body.platform(), body.author(), body.published()));
        return ResponseEntity.ok(result);
    }

    /**
     * 转写费用确认（RFC 20260912 §3.8 条 5）：无字幕视频在花钱转写前，先由用户点头。
     * body {"confirm": true|false}；false = 取消（元数据已留痕，不产生费用）。
     */
    @PostMapping("/digest/confirm")
    public ResponseEntity<?> confirmTranscription(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody LearnConfirmRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (body == null || body.confirm() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请告诉我是继续还是取消"));
        }
        return ResponseEntity.ok(digestService.confirm(userId, body.confirm()));
    }

    /** 本月转写用量与剩余额度（费用可控条 4：累计费用可查、月初自动重置）。 */
    @GetMapping("/digest/quota")
    public ResponseEntity<?> quota(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(transcriptionService.quota(userId));
    }

    /** 消化任务状态（2026-09-10 提交式配套）：running / done{type,title} / failed{message} / idle。 */
    @GetMapping("/digest/status")
    public ResponseEntity<?> digestStatus(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(digestService.digestJobStatus(userId));
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

    /**
     * 图片喂入（2026-09-12 完整升级批）：multipart 1~3 张（书页/PPT/讲义/截图）→ 视觉模型忠实提取
     * → 与链接/素材同一条消化流水线（提交式，轮询 /digest/status）。
     * <p>
     * 原图先落 {@code learn/_raw/}（源必留痕：原图丢了不可重建），消化成功后随卡归位到主题目录。
     */
    @PostMapping("/digest/image")
    public ResponseEntity<?> digestImages(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String note) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请给我至少一张图片"));
        }
        if (!isBlank(type) && !LearnCard.isValidType(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        List<LearnDigestAppService.ImageInput> images = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "有一张图是空的，重新发一次"));
            }
            String contentType = file.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                return ResponseEntity.badRequest().body(Map.of("error", "只能发图片（png/jpg/webp）"));
            }
            try {
                images.add(new LearnDigestAppService.ImageInput(
                        file.getBytes(), contentType, file.getOriginalFilename()));
            } catch (java.io.IOException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "这张图我没读上来，重发一次试试"));
            }
        }
        return ResponseEntity.ok(digestService.submitImages(userId, images, type, note));
    }

    /**
     * 单篇卡片全文（?type=ai/trading/other&title= 精确标题）；不存在 → 400 人话。
     */
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

    /** 复习状态流转（V2）：new → review → done。body {type, title, status}；返回更新后卡片。 */
    @PatchMapping("/cards/status")
    public ResponseEntity<?> changeStatus(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody LearnStatusRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        LearnCard updated = digestService.changeStatus(userId, body.type(), body.title(), body.status());
        return ResponseEntity.ok(updated);
    }

    /**
     * 编辑卡片正文（V2 对话流让阿呆改的后端支撑）：?type=&title= 定位，body 为部分字段补丁
     * （缺省/省略字段 = 保留原值）。type/title/created 不可改。返回更新后卡片。
     */
    @PatchMapping("/cards")
    public ResponseEntity<?> edit(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String type,
            @RequestParam String title,
            @RequestBody LearnEditRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (!LearnCard.isValidType(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "编辑内容不能为空"));
        }
        LearnCardPatch patch = new LearnCardPatch(
                body.coreView(), body.keyPoints(), body.questions(), body.retell(),
                body.tradeRelated(), body.tradeNote(), body.tags());
        return ResponseEntity.ok(digestService.edit(userId, type, title, patch));
    }

    /**
     * 删卡片（2026-09-13 缺口批）：{@code ?type=&title=} 定位。
     * <p>
     * **软删除**——文件移入 {@code learn/_trash/}（可人工捡回），不真丢内容；只允许删本产品产出的卡
     * （别处整理的原始卡人话拒绝）。**级联**：指向该卡的 trading 反哺候选一并清理（P2-learn11 治本）。
     */
    @DeleteMapping("/cards")
    public ResponseEntity<?> deleteCard(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String type,
            @RequestParam String title) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (!LearnCard.isValidType(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        String learnCardId = digestService.deleteCard(userId, type, title);
        java.util.List<String> cascaded = candidateService.deleteByLearnCardId(userId, learnCardId);
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "title", title,
                "learnCardId", learnCardId,
                "cascadedCandidates", cascaded));
    }

    /**
     * 改主题（2026-09-13 缺口批）：把卡片挪到另一个主题目录（新主题内续号），frontmatter 的
     * {@code topic} 与两个主题的 README 索引一起同步。body {@code {"type","title","topic"}}。
     */
    @PatchMapping("/cards/topic")
    public ResponseEntity<?> moveTopic(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody LearnTopicRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(digestService.moveToTopic(userId, body.type(), body.title(), body.topic()));
    }

    /** 资产树：learn 按 type 分组（卡片清单）。 */
    @GetMapping("/tree")
    public ResponseEntity<?> tree(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(digestService.tree(userId));
    }

    /**
     * 卡片全文（md 原文 + 元信息）：?type=&title= 定位。
     * <p>
     * 2026-09-12 完整升级批：列表里的卡片字段只有产品建模的四个段，Mac 侧技能整理的卡
     * （关键内容详解/金句/概念关系）**在界面上读不全**——本端点按 md 原文返回，两种来源都完整。
     */
    @GetMapping("/content")
    public ResponseEntity<?> content(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String type,
            @RequestParam String title) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (!LearnCard.isValidType(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "type 仅支持 ai/trading/other"));
        }
        return ResponseEntity.ok(digestService.content(userId, type, title));
    }

    /**
     * 老式扁平卡一次性迁移到主题目录（2026-09-12 完整升级批）：POST，**幂等**（已迁过 → migrated=0）。
     * <p>
     * 把 V1/V2 的 `{type}/{date}_{title}.md` 迁到 `{type}/{topic}/NN-{slug}.md`（补 `origin`/`topic`
     * 两个 frontmatter 键 + 主题内续号 + 维护主题 README）。**Mac 侧技能整理的主题目录卡一动不动。**
     */
    @PostMapping("/migrate")
    public ResponseEntity<?> migrate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        List<com.adaiadai.core.domain.learn.LearnCardRepository.MigrationItem> items =
                digestService.migrateLegacy(userId);
        return ResponseEntity.ok(Map.of("migrated", items.size(), "items", items));
    }

    /**
     * 找卡片（对话里「打开那篇」+ 学习页搜索）：?q=关键词，纯规则打分不烧 AI。
     * 命中为空 → 空列表（前端自行兜底话术）。
     */
    @GetMapping("/find")
    public ResponseEntity<?> find(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(digestService.find(userId, q, limit));
    }

    /** 反哺候选（V2 批 3）：trading 卡片 → trading 候选建议卡。body {type,title}。 */
    @PostMapping("/cards/candidate")
    public ResponseEntity<?> createCandidate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody LearnCandidateRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(candidateService.createFromCard(userId, body.type(), body.title()));
    }

    /** 复习提醒开关读（S-learn2 2026-09-07）：learn 插件用户可自关，不经 trading 门控。 */
    @GetMapping("/push-settings")
    public ResponseEntity<?> reviewSettings(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(Map.of("learn-review", reviewPushService.reviewEnabled(userId)));
    }

    /** 复习提醒开关写（S-learn2）：body {"enabled":false} 关闭。 */
    @PutMapping("/push-settings/learn-review")
    public ResponseEntity<?> updateReviewSetting(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody LearnReviewSettingRequest body) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        if (body == null || body.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "开关值不能为空"));
        }
        reviewPushService.setReviewEnabled(userId, body.enabled());
        return ResponseEntity.ok(Map.of("learn-review", reviewPushService.reviewEnabled(userId)));
    }

    /** 候选列表（V2 批 3）：trading 候选建议卡，created 倒序。 */
    @GetMapping("/cards/candidates")
    public ResponseEntity<?> listCandidates(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(candidateService.listCandidates(userId));
    }

    /** 删除候选（V2 批 3）：?title= 定位，幂等（不存在 200）。 */
    @DeleteMapping("/cards/candidates")
    public ResponseEntity<?> deleteCandidate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam String title) {
        ResponseEntity<?> denied = requireLearnPlugin(userId);
        if (denied != null) return denied;
        candidateService.deleteCandidate(userId, title);
        return ResponseEntity.ok(Map.of("deleted", true));
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseEntity<?> requireLearnPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_LEARN)) {
            return ResponseEntity.status(403).body(Map.of("error", "learn 插件未启用，无法使用学习功能"));
        }
        return null;
    }

    /**
     * 喂入请求（2026-09-12 抓取批）：{@code url} **或** {@code content} 至少一个（url 优先）；
     * type/platform/author/published 可选。
     */
    public record LearnDigestRequest(
            @Size(max = 500, message = "链接过长")
            String url,
            @Size(max = 50000, message = "素材过长（限 50000 字），建议分段消化")
            String content,
            @Size(max = 20, message = "类型仅 ai/trading/other")
            String type,
            @Size(max = 50, message = "平台名过长")
            String platform,
            @Size(max = 100, message = "作者名过长")
            String author,
            @Size(max = 30, message = "发布日期格式 yyyy-MM-dd")
            String published) {}

    /** 转写费用确认请求（RFC 20260912 §3.8 条 5）：{"confirm": true|false}。 */
    public record LearnConfirmRequest(Boolean confirm) {}

    /** 复习状态流转请求：type/title 定位卡片，status 目标状态（new/review/done）。 */
    public record LearnStatusRequest(
            @NotBlank(message = "类型不能为空") String type,
            @NotBlank(message = "卡片标题不能为空") String title,
            @NotBlank(message = "目标状态不能为空") String status) {}

    /** 编辑补丁请求：全部字段可选，省略/缺省 = 保留原值（定位走 query type+title）。 */
    public record LearnEditRequest(
            String coreView,
            java.util.List<String> keyPoints,
            java.util.List<String> questions,
            String retell,
            Boolean tradeRelated,
            String tradeNote,
            java.util.List<String> tags) {}

    /** 反哺候选请求：type/title 定位源 learn 卡片。 */
    public record LearnCandidateRequest(
            @NotBlank(message = "类型不能为空") String type,
            @NotBlank(message = "卡片标题不能为空") String title) {}

    /** 改主题请求：type/title 定位卡片，topic 目标主题名。 */
    public record LearnTopicRequest(
            @NotBlank(message = "类型不能为空") String type,
            @NotBlank(message = "卡片标题不能为空") String title,
            @NotBlank(message = "主题名不能为空") String topic) {}

    /** 复习提醒开关请求（S-learn2）：{"enabled": true/false}。 */
    public record LearnReviewSettingRequest(Boolean enabled) {}
}
