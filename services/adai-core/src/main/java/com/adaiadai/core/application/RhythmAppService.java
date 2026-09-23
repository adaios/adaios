package com.adaiadai.core.application;

import com.adaiadai.core.kernel.rhythm.Rhythm;
import com.adaiadai.core.kernel.rhythm.RhythmException;
import com.adaiadai.core.kernel.rhythm.RhythmRepository;
import com.adaiadai.core.kernel.rhythm.RhythmStatus;
import com.adaiadai.core.kernel.rhythm.RruleSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * RhythmAppService — 节律应用服务（RFC 20260923 B 批）。
 * <p>
 * 编排十分薄：校验（RRULE 必须能解析）→ 落盘（File First：{@code data/{userId}/rhythm/YYYY/MM.md}）。
 * <p>
 * <b>与待办的边界</b>：这里没有「完成」动作——节律只有 ACTIVE / PAUSED / RETIRED
 * （「这周四不加班」是暂停或填 validUntil，不是 DONE）。
 */
@Service
public class RhythmAppService {

    private static final Logger log = LoggerFactory.getLogger(RhythmAppService.class);

    private final RhythmRepository rhythmRepository;

    public RhythmAppService(RhythmRepository rhythmRepository) {
        this.rhythmRepository = rhythmRepository;
    }

    /** 节律列表（status 为 null 表示全部；新创建的在前）。 */
    public List<Rhythm> listRhythms(String userId, RhythmStatus status) {
        List<Rhythm> rhythms = rhythmRepository.findAll(status, userId);
        rhythms.sort(Comparator
                .comparing(Rhythm::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Rhythm::id)
                .reversed());
        return rhythms;
    }

    /**
     * 新建节律。{@code recurrence} 必须是合法 RRULE（非法抛人话 400，不落半个对象）。
     *
     * @param sourceRecordId 源记录 ID（记录自动转节律时关联，手建传 null）
     */
    public Rhythm createRhythm(String userId, String title, String recurrence, String sourceRecordId) {
        if (title == null || title.isBlank()) {
            throw new RhythmException("节律内容不能为空");
        }
        RruleSchedule.parse(recurrence); // 校验：非法周期当场抛人话
        LocalDate now = LocalDate.now();
        Rhythm rhythm = new Rhythm(
                Rhythm.generateId(),
                title.strip(),
                recurrence.strip(),
                RhythmStatus.ACTIVE,
                sourceRecordId,
                now,
                null,
                now,
                now
        );
        rhythmRepository.save(userId, rhythm);
        log.info("节律已创建 | id={} | title=\"{}\" | recurrence={} | source={}",
                rhythm.id(), rhythm.title(), rhythm.recurrence(),
                sourceRecordId != null ? sourceRecordId : "-");
        return rhythm;
    }

    /**
     * 更新节律（null 字段保持原值）。
     *
     * @param replaceValidUntil true 表示按 {@code validUntil} 覆盖（传 null 即清除失效日）
     */
    public Rhythm updateRhythm(String userId, String id, String title, String recurrence,
                               RhythmStatus status, LocalDate validUntil, boolean replaceValidUntil) {
        Rhythm existing = rhythmRepository.findById(userId, id)
                .orElseThrow(() -> new RhythmException("没找到这条节律"));

        String nextRecurrence = recurrence != null && !recurrence.isBlank()
                ? recurrence.strip()
                : existing.recurrence();
        RruleSchedule.parse(nextRecurrence);

        Rhythm updated = new Rhythm(
                existing.id(),
                title != null && !title.isBlank() ? title.strip() : existing.title(),
                nextRecurrence,
                status != null ? status : existing.status(),
                existing.sourceRecordId(),
                existing.validFrom(),
                replaceValidUntil ? validUntil : existing.validUntil(),
                existing.createdAt(),
                LocalDate.now()
        );
        rhythmRepository.save(userId, updated);
        log.info("节律已更新 | id={} | status={} | validUntil={} | recurrence={}",
                id, updated.status(),
                updated.validUntil() != null ? updated.validUntil() : "-", updated.recurrence());
        return updated;
    }

    /** 删除节律（退役即删除；想留历史就转 RETIRED 而不是删）。 */
    public void deleteRhythm(String userId, String id) {
        rhythmRepository.findById(userId, id)
                .orElseThrow(() -> new RhythmException("没找到这条节律"));
        rhythmRepository.delete(userId, id);
    }

    /** 今天命中的节律（简报闸 1 用：只有命中日才注入）。 */
    public List<Rhythm> occurringOn(String userId, LocalDate date) {
        return rhythmRepository.findAll(RhythmStatus.ACTIVE, userId).stream()
                .filter(r -> r.occursOn(date))
                .toList();
    }
}
