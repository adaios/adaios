package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryPattern;
import com.adaiadai.core.kernel.memory.MemoryPreference;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MemoryController — 记忆查询 API（维护操作已迁至 AdminController，REVIEW P-be-01）。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 按日期查询记忆。
     */
    @GetMapping
    public ResponseEntity<List<Memory>> getMemories(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String date) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(memoryService.findByDate(userId, queryDate));
    }

    /**
     * 返回所有有记忆数据的日期列表。
     */
    @GetMapping("/dates")
    public ResponseEntity<List<LocalDate>> getDates(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(memoryService.findAllDates(userId));
    }

    /**
     * 返回记忆总条数。
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getCount(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(Map.of("count", memoryService.count(userId)));
    }

    /**
     * 「阿呆对你的了解」——把一直躺在记忆里的长期观察聚合出来（2026-09-16「第一次见面」批）。
     * <p>
     * patterns / preferences 是 AI 从日常对话里自动沉淀的长期观察，此前**没有任何出口**
     * （REVIEW P2-认知3「前端零入口」）：用户打开「档案」只看到自己手填的表单，
     * 于是觉得「它根本没有更懂我」——其实数据一直在长，只是看不见。
     * <p>
     * GET /api/v1/memory/insights →
     * {@code {total, patternCount, preferenceCount, observedSince, insights:[{kind,content,confidence}]}}
     * （两类合并后按置信度降序；observedSince 为最早一条记忆的日期，没有记忆时为 null）
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getInsights(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        // 「阿呆对你的了解」是**长期**画像：用 365 天窗口，而不是聚合默认的 30 天——
        // 否则一个两个月没来记录的用户会看到「我还不认识你」（不是没观察过，是被窗口挡掉了）。
        List<MemoryPattern> patterns = memoryService.findAllPatterns(userId, INSIGHTS_WINDOW_DAYS);
        List<MemoryPreference> preferences = memoryService.findAllPreferences(userId, INSIGHTS_WINDOW_DAYS);

        List<Map<String, Object>> insights = new ArrayList<>();
        for (MemoryPattern p : patterns) {
            insights.add(insight("pattern", p.content(), p.confidence()));
        }
        for (MemoryPreference p : preferences) {
            insights.add(insight("preference", p.content(), p.confidence()));
        }
        // 两类各自已按置信度降序，合并后再排一次，保证前端取前 N 条就是最确定的
        insights.sort((a, b) -> Double.compare(
                ((Number) b.get("confidence")).doubleValue(),
                ((Number) a.get("confidence")).doubleValue()));

        String observedSince = memoryService.earliestMemoryDate(userId)
                .map(LocalDate::toString)
                .orElse(null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", insights.size());
        resp.put("patternCount", patterns.size());
        resp.put("preferenceCount", preferences.size());
        resp.put("observedSince", observedSince);
        resp.put("insights", insights);
        return ResponseEntity.ok(resp);
    }

    /** 「阿呆对你的了解」观察窗口（天）。 */
    private static final int INSIGHTS_WINDOW_DAYS = 365;

    private Map<String, Object> insight(String kind, String content, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("content", content == null ? "" : content);
        m.put("confidence", confidence);
        return m;
    }

    /**
     * 根据记录 ID 查询 AI 理解。
     */
    @GetMapping("/record/{recordId}")
    public ResponseEntity<Memory> getByRecordId(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String recordId) {
        return memoryService.findByRecordId(userId, recordId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 手动修正记忆（P-role-02：个人记忆修正归用户端，adai-app 记忆页「修正」）。
     * <p>
     * PATCH /api/v1/memory/{id} — 任一字段缺省表示保持原值；找不到返回 404。
     * body {@code {kind?, summary?, tags?, actionable?, suggestion?}}（与 /admin/memory/{id} 同构，
     * 区别在鉴权：本端点走 X-User-Id 用户隔离（AuthFilter 覆盖为会话 userId），
     * admin 端点走登录 + role=admin 管理隔离（REVIEW #178，X-Admin-Token 退役）。
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateMemory(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id,
            @RequestBody com.adaiadai.core.interfaces.AdminController.MemoryUpdateRequest request) {
        boolean updated = memoryService.update(userId, id,
                request.kind(), request.summary(), request.tags(),
                request.actionable(), request.suggestion());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 标记行动类记忆为已完成（记忆进化 Phase 3：Reality→Knowledge→Action→Reality 闭环）。
     * <p>
     * PATCH /api/v1/memory/{id}/done — actionable=false + doneAt=now，
     * 完成后的记忆不再出现在"待行动事项"与 Feed 待办提醒。
     */
    @PatchMapping("/{id}/done")
    public ResponseEntity<Map<String, Object>> markDone(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        boolean done = memoryService.markDone(userId, id);
        if (!done) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
