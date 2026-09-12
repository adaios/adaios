package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SnapshotHolding;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingAnchorFileRepository — 券商快照锚定文件存储
 * （data/{userId}/trading/snapshot-anchor.json，P2-交易34 治本 2026-09-09，
 * 2026-09-12 账实一致性批扩展）。
 * <p>
 * 单行 JSON：{@code {positionsReplace, cashImport, holdings:[{symbol,name,quantity}], recordedAt}}。
 * per-user 条带锁（固定 16 条带，P2-交易28 模式）保证 update（读-改-写）原子。
 * <p>
 * 2026-09-12 语义修正（本次生产事故根因之一）：
 * <ul>
 *   <li><b>锚定日只前进不后退</b>——旧实现直接用导入日覆盖，老快照文件（如 09-09 的文件
 *       在 09-12 才导入）会把锚定日推后到 09-12，害得 09-10/09-11 的真实成交被误判为
 *       「已含在快照内」；现在取 max(既有, 新值)。</li>
 *   <li><b>同文件记快照持仓基线</b>（holdings）——对账闸门（GET /trading/integrity）用它
 *       推出应有持仓，与落地持仓比对，把「账实不符」变成当天可见。</li>
 * </ul>
 * 读失败/损坏 → 空锚定 + holdings 空（调用方必须按 {@link SnapshotAnchor#known()} fail-closed
 * 处理：未知锚定不得静默重放）。写失败抛 StorageException（B6-4/P0-1 口径，不静默）。
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
            // 损坏 → 空锚定；调用方（导入重放）会 fail-closed，不再静默按旧行为重放
            log.warn("读取快照锚定失败（按空锚定处理，需要改账的导入将被拒绝）| userId={} | {}",
                    userId, e.getMessage());
            return SnapshotAnchor.empty();
        }
    }

    @Override
    public List<SnapshotHolding> holdings(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            var node = MAPPER.readTree(content).path("holdings");
            if (!node.isArray()) return List.of();
            List<SnapshotHolding> out = new ArrayList<>();
            for (var h : node) {
                String symbol = h.path("symbol").asText("");
                if (symbol.isBlank()) continue;
                out.add(new SnapshotHolding(symbol, h.path("name").asText(null),
                        h.path("quantity").asInt(0)));
            }
            return out;
        } catch (Exception e) {
            log.warn("读取快照持仓基线失败（对账降级为「无法判定」）| userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean holdingsRecorded(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return false;
        try {
            return MAPPER.readTree(content).path("holdingsRecorded").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void updatePositionsReplace(String userId, LocalDate date) {
        synchronized (lockFor(userId)) {
            SnapshotAnchor cur = find(userId);
            // 只前进不后退（2026-09-12）：补导旧快照文件不得把锚定日推后
            LocalDate next = maxDate(cur.positionsReplace(), date);
            // 保留既有快照持仓基线（2026-09-12 修复：旧写法传 null 会把基线从文件里抹掉，
            // 害得对账闸门永远「无法判定」——资金导入同样不能抹掉持仓基线）
            saveLocked(userId, new SnapshotAnchor(next, cur.cashImport()), existingHoldings(userId));
        }
    }

    @Override
    public void updateCashImport(String userId, LocalDate date) {
        synchronized (lockFor(userId)) {
            SnapshotAnchor cur = find(userId);
            LocalDate next = maxDate(cur.cashImport(), date);
            saveLocked(userId, new SnapshotAnchor(cur.positionsReplace(), next), existingHoldings(userId));
        }
    }

    @Override
    public void recordHoldings(String userId, List<SnapshotHolding> holdings) {
        synchronized (lockFor(userId)) {
            SnapshotAnchor cur = find(userId);
            saveLocked(userId, cur, holdings != null ? holdings : List.of());
        }
    }

    /** 既有基线（未记录过 → null，避免把「没记录」写成「记录为空」）。 */
    private List<SnapshotHolding> existingHoldings(String userId) {
        return holdingsRecorded(userId) ? holdings(userId) : null;
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private void saveLocked(String userId, SnapshotAnchor anchor, List<SnapshotHolding> holdings) {
        try {
            var obj = MAPPER.createObjectNode();
            obj.put("positionsReplace", anchor.positionsReplace() != null ? anchor.positionsReplace().toString() : "");
            obj.put("cashImport", anchor.cashImport() != null ? anchor.cashImport().toString() : "");
            obj.put("recordedAt", java.time.LocalDateTime.now().toString());
            if (holdings != null) {
                obj.put("holdingsRecorded", true);
                var arr = obj.putArray("holdings");
                for (SnapshotHolding h : holdings) {
                    var n = arr.addObject();
                    n.put("symbol", h.symbol());
                    n.put("name", h.name() != null ? h.name() : "");
                    n.put("quantity", h.quantity());
                }
            }
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(obj));
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("保存快照锚定失败 | userId=" + userId + " | " + e.getMessage(), e);
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
