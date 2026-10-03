package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.CashAdjustment;
import com.adaiadai.core.domain.trading.CashAdjustmentRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CashAdjustmentFileRepository — 对账调整文件存储
 * （{@code data/{userId}/trading/cash-adjustments.json}，RFC 20261003 C4 + D3「独立文件」，2026-10-03）。
 *
 * <p>独立文件而非塞进成交流水：语义不同（它**不是**一笔交易，而是「系统与券商对不上时记的一笔账」），
 * 混进 trades 会污染胜率/批次/复盘等一切按流水统计的口径。
 *
 * <p>per-user 条带锁（同 AccountSnapshotFileRepository 模式）：read→add→write 必须串行，否则并发导入互相覆盖。
 */
@Repository
public class CashAdjustmentFileRepository implements CashAdjustmentRepository {

    private static final Logger log = LoggerFactory.getLogger(CashAdjustmentFileRepository.class);
    private static final String PATH = "trading/cash-adjustments.json";
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public CashAdjustmentFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<CashAdjustment> findAll(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<CashAdjustment> out = new ArrayList<>();
            MAPPER.readTree(content).forEach(n -> out.add(new CashAdjustment(
                    n.path("id").asText(),
                    parseDate(n.path("date").asText()),
                    n.path("amount").isMissingNode() || n.path("amount").isNull()
                            ? BigDecimal.ZERO : n.path("amount").decimalValue(),
                    n.path("reason").asText(""),
                    n.path("note").asText(""),
                    parseTime(n.path("createdAt").asText()))));
            out.sort(Comparator.comparing(CashAdjustment::date,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return out;
        } catch (Exception e) {
            log.warn("读取对账调整失败（按无调整处理）| userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void append(String userId, CashAdjustment adjustment) {
        Object lock = locks[(userId != null ? userId : "default").hashCode() & (LOCK_STRIPES - 1)];
        synchronized (lock) {
            // ⚠️ P0（2026-10-03 增量深审）：这里**必须严格解析**。原先 append 走 findAll，而 findAll
            // 读坏会吞成空列表 → 紧接着用「仅含新条」的数组重写整文件 → 历史调整**静默清零**，
            // 「系统现金 = 上次快照值 + Σ已计入流水 + Σ调整」这条恒等式再也回看不了。
            // 现在：文件不存在/为空 = 全新（可写）；文件存在但解析失败 = **拒绝写入并报错**。
            String raw = fileStorage.read(userId, PATH);
            List<CashAdjustment> all = new ArrayList<>();
            if (raw != null && !raw.isBlank()) {
                try {
                    for (var n : MAPPER.readTree(raw)) {
                        all.add(new CashAdjustment(
                                n.path("id").asText(),
                                parseDate(n.path("date").asText()),
                                n.path("amount").isMissingNode() || n.path("amount").isNull()
                                        ? BigDecimal.ZERO : n.path("amount").decimalValue(),
                                n.path("reason").asText(""),
                                n.path("note").asText(""),
                                parseTime(n.path("createdAt").asText())));
                    }
                } catch (Exception e) {
                    throw new StorageException("对账调整文件（" + PATH + "）解析失败，为避免覆盖既有历史，"
                            + "本次写入已取消：" + e.getMessage(), e);
                }
            }
            all.add(adjustment);
            try {
                var arr = MAPPER.createArrayNode();
                for (CashAdjustment a : all) {
                    var n = arr.addObject();
                    n.put("id", a.id());
                    n.put("date", a.date() != null ? a.date().toString() : "");
                    n.put("amount", a.amount());
                    n.put("reason", a.reason() != null ? a.reason() : "");
                    n.put("note", a.note() != null ? a.note() : "");
                    n.put("createdAt", (a.createdAt() != null ? a.createdAt() : LocalDateTime.now()).toString());
                }
                fileStorage.write(userId, PATH, MAPPER.writeValueAsString(arr));
            } catch (Exception e) {
                throw new StorageException("保存对账调整失败: " + e.getMessage(), e);
            }
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
