package com.adaiadai.core.application;

import com.adaiadai.core.kernel.rhythm.Rhythm;
import com.adaiadai.core.kernel.rhythm.RhythmDetector;
import com.adaiadai.core.kernel.rhythm.RhythmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RecordToRhythmLinker — 记录自动转节律联动（RFC 20260923 B 批，D5 写入侧分流）。
 * <p>
 * 与 {@link RecordToTodoLinker} 的关系：<b>先试节律，再试待办</b>（调用方按此顺序）。
 * 一句「每周四固定发版加班」是**习惯**，不该变成一条永远做不完的待办
 * （用户 2026-09-23：「这是我的工作周期习惯，不是待办」）。
 * <p>
 * 分流判据：
 * <pre>
 *   转节律 ⇔ intent=log AND actionable=true AND 摘要非空
 *            AND {@link RhythmDetector#isRhythmLike} AND 能推断出 RRULE
 * </pre>
 * <b>推断不出 RRULE 就不转</b>（返回 null，交给待办链路）——宁可漏判成待办，
 * 也不把一条一次性任务硬塞进节律（那是另一种误判）。
 * <p>
 * 幂等：同 {@code sourceRecordId} 已有节律则跳过（防重补刷出重复节律）。
 */
@Service
public class RecordToRhythmLinker {

    private static final Logger log = LoggerFactory.getLogger(RecordToRhythmLinker.class);

    private final RhythmRepository rhythmRepository;
    private final RhythmAppService rhythmService;

    public RecordToRhythmLinker(RhythmRepository rhythmRepository, RhythmAppService rhythmService) {
        this.rhythmRepository = rhythmRepository;
        this.rhythmService = rhythmService;
    }

    /**
     * 尝试把记录转为节律。
     *
     * @return 生成的 rhythmId；不满足条件 / 推断不出 RRULE / 已存在时返回 null
     */
    public String link(String userId, String recordId, String intent, String title, boolean actionable) {
        try {
            if (!"log".equals(intent)) return null;
            if (!actionable) return null;
            if (title == null || title.isBlank()) return null;

            String rrule = RhythmDetector.detectRrule(title);
            if (rrule == null) return null; // 不是节律（或推不出周期）→ 交给待办链路

            boolean exists = rhythmRepository.findAll(userId).stream()
                    .anyMatch(r -> recordId.equals(r.sourceRecordId()));
            if (exists) {
                log.info("R2-Rhythm 跳过：记录已转节律 | recordId={}", recordId);
                return null;
            }

            Rhythm rhythm = rhythmService.createRhythm(userId, title, rrule, recordId);
            log.info("R2-Rhythm 记录自动转节律 | recordId={} → rhythmId={} | recurrence={} | title=\"{}\"",
                    recordId, rhythm.id(), rrule, title);
            return rhythm.id();
        } catch (Exception e) {
            // best-effort：联动失败不阻塞记录保存（与 RecordToTodoLinker 同原则）
            log.warn("R2-Rhythm 记录转节律失败（不阻塞记录） | recordId={} | {}", recordId, e.getMessage());
            return null;
        }
    }
}
