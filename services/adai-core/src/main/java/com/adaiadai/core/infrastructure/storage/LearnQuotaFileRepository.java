package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnQuota;
import com.adaiadai.core.domain.learn.LearnQuotaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;

/**
 * LearnQuotaFileRepository — 转写配额记账（RFC 20260912 §3.8 费用可控条 4）。
 * <p>
 * 落 {@code data/{userId}/learn/_quota.json}（File First）：
 * <pre>{"2026-09": {"usedSeconds": 1710, "usedYuan": 0.1368}}</pre>
 * **按月分键 → 月初自动重置**（新月份键不存在即全零），不需要定时任务。
 * 记账与读-改-写在同一把 per-user 条带锁内完成（pitfall「整文件重写并发」防复发）。
 * <p>
 * 写盘失败抛 {@code StorageException}（fail-visible：记不上账就不该继续花钱）。
 */
@Repository
public class LearnQuotaFileRepository implements LearnQuotaRepository {

    private static final Logger log = LoggerFactory.getLogger(LearnQuotaFileRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUOTA_PATH = "learn/_quota.json";
    private static final int LOCK_STRIPES = 16;

    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final com.adaiadai.core.kernel.storage.FileStorage fileStorage;
    private final int quotaSeconds;

    public LearnQuotaFileRepository(com.adaiadai.core.kernel.storage.FileStorage fileStorage,
                                    @Value("${adai.learn.asr.month-quota-seconds:108000}") int quotaSeconds) {
        this.fileStorage = fileStorage;
        this.quotaSeconds = quotaSeconds > 0 ? quotaSeconds : 108000;
    }

    private Object lockFor(String userId) {
        int h = (userId != null ? userId : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    @Override
    public LearnQuota view(String userId, YearMonth month) {
        synchronized (lockFor(userId)) {
            return readLocked(userId, month);
        }
    }

    @Override
    public LearnQuota consume(String userId, YearMonth month, int seconds, double yuan) {
        synchronized (lockFor(userId)) {
            JsonNode root = readRoot(userId);
            LearnQuota current = extract(root, month);
            // 允许负数 = 回退预留（转写失败时把钱退回去，对抗审查 P1-4 的配套语义）；总量钳在 0 以上
            int usedSeconds = Math.max(0, current.usedSeconds() + seconds);
            double usedYuan = Math.max(0d, round4(current.usedYuan() + yuan));

            ObjectNode next = root != null && root.isObject()
                    ? (ObjectNode) root.deepCopy()
                    : MAPPER.createObjectNode();
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("usedSeconds", usedSeconds);
            entry.put("usedYuan", round4(usedYuan));
            next.set(month.toString(), entry);

            try {
                fileStorage.write(userId, QUOTA_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(next));
            } catch (Exception e) {
                throw new StorageException("转写记账写入失败，已中止转写（不产生不可追溯的费用）", e);
            }
            log.info("learn 转写记账 | userId={} | month={} | +{}s | 本月累计 {}s / {}元",
                    userId, month, seconds, usedSeconds, round4(usedYuan));
            return new LearnQuota(month.toString(), usedSeconds, round4(usedYuan), quotaSeconds);
        }
    }

    private LearnQuota readLocked(String userId, YearMonth month) {
        return extract(readRoot(userId), month);
    }

    /**
     * 读账本。
     * <p>
     * **对抗审查 P2-1 修复**：原实现「解析失败 → 按空账本处理」= 一次损坏就把闸门永久打开
     * （额度重新归零 → 可无限转写），与「fail-visible」相反。改为 **fail-closed**：账本存在但
     * 读不出来 → 抛 {@code StorageException} 拒绝转写并告警，由用户去修 `learn/_quota.json`。
     * 「文件不存在」仍是正常的全新用户（返回 null → 全零）。
     */
    private JsonNode readRoot(String userId) {
        String content;
        try {
            content = fileStorage.read(userId, QUOTA_PATH);
        } catch (Exception e) {
            throw new StorageException("转写额度账本读不出来，为防超支已暂停转写", e);
        }
        if (content == null || content.isBlank()) return null;
        try {
            return MAPPER.readTree(content);
        } catch (Exception e) {
            log.error("learn 配额账本内容异常，拒绝转写（fail-closed）| userId={} | {}", userId, e.getMessage());
            throw new StorageException("转写额度账本内容异常，为防超支已暂停转写（请检查 learn/_quota.json）", e);
        }
    }

    private LearnQuota extract(JsonNode root, YearMonth month) {
        if (root == null || !root.isObject()) return LearnQuota.empty(month, quotaSeconds);
        JsonNode entry = root.path(month.toString());
        if (entry.isMissingNode() || !entry.isObject()) return LearnQuota.empty(month, quotaSeconds);
        return new LearnQuota(month.toString(),
                entry.path("usedSeconds").asInt(0),
                entry.path("usedYuan").asDouble(0d),
                quotaSeconds);
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
