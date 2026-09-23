package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.RhythmAppService;
import com.adaiadai.core.kernel.rhythm.Rhythm;
import com.adaiadai.core.kernel.rhythm.RhythmException;
import com.adaiadai.core.kernel.rhythm.RhythmStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * RhythmController — 节律 API（RFC 20260923 B 批：节律独立为 Kernel builtin）。
 * <p>
 * <b>与待办的分工</b>：待办 = 有终点的一次性动作（OPEN/DONE）；节律 = 周期性习惯
 * （active/paused/retired，**没有完成态**）。周期用 RRULE 表示（如 {@code FREQ=WEEKLY;BYDAY=TH}）。
 * 无插件门控——与待办同级，人人都有。
 * <p>
 * 端点：
 * <pre>
 *   GET    /api/v1/rhythms?status=ACTIVE|PAUSED|RETIRED   列表（status 可选）
 *   POST   /api/v1/rhythms                                新建 {title, recurrence}
 *   PUT    /api/v1/rhythms/{id}                           更新 {title?, recurrence?, status?, validUntil?}
 *   DELETE /api/v1/rhythms/{id}                           删除（想留历史请改 status=RETIRED）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/rhythms")
public class RhythmController {

    private final RhythmAppService rhythmService;

    public RhythmController(RhythmAppService rhythmService) {
        this.rhythmService = rhythmService;
    }

    /** 节律列表（status 可选；新创建的在前）。 */
    @GetMapping
    public ResponseEntity<List<Rhythm>> listRhythms(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(rhythmService.listRhythms(userId, parseStatus(status)));
    }

    /** 新建节律（recurrence 必填且必须是合法 RRULE）。 */
    @PostMapping
    public ResponseEntity<Rhythm> createRhythm(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody RhythmRequest request) {
        return ResponseEntity.ok(rhythmService.createRhythm(
                userId, request.title(), request.recurrence(), null));
    }

    /** 更新节律（null 字段保持原值；validUntil 传空串清除失效日）。 */
    @PutMapping("/{id}")
    public ResponseEntity<Rhythm> updateRhythm(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id,
            @RequestBody RhythmRequest request) {
        boolean replaceValidUntil = request.validUntil() != null;
        return ResponseEntity.ok(rhythmService.updateRhythm(
                userId, id, request.title(), request.recurrence(), parseStatus(request.status()),
                replaceValidUntil ? parseValidUntil(request.validUntil()) : null, replaceValidUntil));
    }

    /** 删除节律。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRhythm(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        rhythmService.deleteRhythm(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ── 解析（非法输入 → 400 人话，不 500 裸奔）──

    private RhythmStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return RhythmStatus.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RhythmException("节律状态只有「生效中 / 已暂停 / 已退役」三种");
        }
    }

    private LocalDate parseValidUntil(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new RhythmException("失效日要写成 2026-12-31 这样");
        }
    }

    // ── DTO ──

    /** 新建/更新请求。{@code validUntil} 为 null 表示不改，空串表示清除。 */
    public record RhythmRequest(
            String title,
            String recurrence,
            String status,
            String validUntil
    ) {}
}
