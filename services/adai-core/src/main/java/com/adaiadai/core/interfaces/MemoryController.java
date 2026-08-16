package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
     * 区别在鉴权：本端点走 X-User-Id 用户隔离，admin 端点走 X-Admin-Token 管理隔离）。
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
