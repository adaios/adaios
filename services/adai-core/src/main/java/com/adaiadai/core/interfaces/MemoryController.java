package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.RecordFlowAppService;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MemoryController — 记忆查询 + 重建 API。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;
    private final RecordRepository recordRepository;
    private final RecordFlowAppService recordFlowAppService;

    public MemoryController(MemoryService memoryService,
                            RecordRepository recordRepository,
                            RecordFlowAppService recordFlowAppService) {
        this.memoryService = memoryService;
        this.recordRepository = recordRepository;
        this.recordFlowAppService = recordFlowAppService;
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

    /**
     * 手动修正记忆（adai-admin 数据管理）：更新 kind/summary/tags/actionable/suggestion。
     * <p>
     * PATCH /api/v1/memory/{id} — 任一字段缺省表示保持原值；找不到返回 404。
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateMemory(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id,
            @RequestBody MemoryUpdateRequest request) {
        boolean updated = memoryService.update(userId, id,
                request.kind(), request.summary(), request.tags(),
                request.actionable(), request.suggestion());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    public record MemoryUpdateRequest(
            String kind, String summary, List<String> tags,
            Boolean actionable, String suggestion) {}

    /**
     * 重建记忆：遍历没有记忆的历史记录，逐个生成 AI 摘要+标签并沉淀为记忆。
     * <p>
     * POST /api/v1/memory/rebuild?date=2026-07-21
     * POST /api/v1/memory/rebuild（重建所有）
     */
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildMemory(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String date) {
        List<ContentRecord> allRecords = recordRepository.findAll(userId);

        // 过滤日期
        LocalDate filterDate = date != null ? LocalDate.parse(date) : null;
        List<ContentRecord> targetRecords = allRecords.stream()
                .filter(r -> filterDate == null || r.createdAt().toLocalDate().equals(filterDate))
                .filter(r -> r.intent() == null || "log".equals(r.intent()))
                // P1-3 修复：已有真实记忆的记录跳过，rebuild 幂等（避免每次重跑重复沉淀）
                .filter(r -> !memoryService.hasRealMemory(userId, r.id()))
                .toList();

        log.info("记忆重建开始 | 目标日期={} | 待处理记录={}条", date != null ? date : "全部", targetRecords.size());

        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (ContentRecord record : targetRecords) {
            try {
                recordFlowAppService.process(userId, record);
                success++;
                log.info("记忆重建成功 | recordId={} | ({}/{})", record.id(), success + failed, targetRecords.size());
            } catch (Exception e) {
                failed++;
                errors.add(record.id() + ": " + e.getMessage());
                log.warn("记忆重建失败 | recordId={} | {}", record.id(), e.getMessage());
            }
        }

        log.info("记忆重建完成 | 成功={} | 失败={}", success, failed);

        // 记忆进化 Phase 4：随 rebuild 清理过期条目（superseded 超 60 天 / actionable 完成超 30 天）
        memoryService.cleanup(userId);

        return ResponseEntity.ok(Map.of(
                "success", success,
                "failed", failed,
                "total", targetRecords.size(),
                "errors", errors
        ));
    }
}
