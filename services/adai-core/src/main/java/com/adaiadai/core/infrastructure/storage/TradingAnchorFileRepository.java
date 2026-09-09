package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * TradingAnchorFileRepository — 券商快照锚定文件存储
 * （data/{userId}/trading/snapshot-anchor.json，P2-交易34 治本 2026-09-09）。
 * <p>
 * 单行 JSON（{positionsReplace, cashImport} 两个日期，可空）；per-user 条带锁
 * （固定 16 条带，P2-交易28 模式）保证 update（读-改-写）原子；损坏/缺失 → 空锚定
 * （fail-open：宁可不防重也不误伤正常导入，配合日志可发现）。写失败抛 StorageException
 * （B6-4/P0-1 口径，不静默）——调用方按「锚定是防护元信息、失败不阻断已成功的导入」处理。
 */
@Repository
public class TradingAnchorFileRepository implements TradingAnchorRepository {

    private static final Logger log = LoggerFactory.getLogger(TradingAnchorFileRepository.class);
    private static final String PATH = "trading/snapshot-anchor.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public TradingAnchorFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Object lockFor(String userId) {
        int h = (userId != null ? userId : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    @Override
    public SnapshotAnchor find(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return SnapshotAnchor.empty();
        try {
            var node = MAPPER.readTree(content);
            return new SnapshotAnchor(parseDate(node.path("positionsReplace").asText()),
                    parseDate(node.path("cashImport").asText()));
        } catch (Exception e) {
            log.warn("读取快照锚定失败（按空锚定处理，防重暂时失效）| userId={} | {}", userId, e.getMessage());
            return SnapshotAnchor.empty();
        }
    }

    @Override
    public void updatePositionsReplace(String userId, LocalDate date) {
        SnapshotAnchor cur = find(userId);
        save(userId, new SnapshotAnchor(date, cur.cashImport()));
    }

    @Override
    public void updateCashImport(String userId, LocalDate date) {
        SnapshotAnchor cur = find(userId);
        save(userId, new SnapshotAnchor(cur.positionsReplace(), date));
    }

    private void save(String userId, SnapshotAnchor anchor) {
        synchronized (lockFor(userId)) {
            try {
                var obj = MAPPER.createObjectNode();
                obj.put("positionsReplace", anchor.positionsReplace() != null ? anchor.positionsReplace().toString() : "");
                obj.put("cashImport", anchor.cashImport() != null ? anchor.cashImport().toString() : "");
                fileStorage.write(userId, PATH, MAPPER.writeValueAsString(obj));
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException("保存快照锚定失败 | userId=" + userId + " | " + e.getMessage(), e);
            }
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
