package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TradingHistoryFileRepository — 交易逐笔流水文件存储实现（RFC 20260816 §2.1）。
 * <p>
 * 流水存储在 {@code data/{userId}/trading/trades/{yyyy-MM}.json}——每月一个 JSON 数组，
 * append 为读-改-写（读当月数组 → 追加 → 原子覆盖写回）。
 * File First：JSON 缩进美化，人类与 AI 都可直接阅读。
 * <p>
 * 读取兜底：单月文件缺失/损坏时跳过该文件（log warn），不阻塞整仓流水查询。
 */
@Repository
public class TradingHistoryFileRepository implements TradingHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(TradingHistoryFileRepository.class);

    private static final String TRADES_DIR = "trading/trades";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    public TradingHistoryFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void append(String userId, TradeRecord trade) {
        // G2：文件路径从实体自身字段推导（entryDate 优先，兜底 timestamp 的日期），不用 now()
        LocalDate date = trade.entryDate() != null ? trade.entryDate() : trade.timestamp().toLocalDate();
        String path = filePath(date);
        List<TradeRecord> trades = readFile(userId, path);
        trades.add(trade);
        try {
            fileStorage.write(userId, path, objectMapper.writeValueAsString(trades));
            log.info("交易流水已落盘 | userId={} | path={} | id={} | {} {} {}股@{}",
                    userId, path, trade.id(), trade.direction(), trade.symbol(), trade.volume(), trade.price());
        } catch (JsonProcessingException e) {
            throw new StorageException("交易流水序列化失败: " + path, e);
        }
    }

    @Override
    public List<TradeRecord> findAll(String userId) {
        List<TradeRecord> all = new ArrayList<>();
        for (String path : fileStorage.listFiles(userId, TRADES_DIR)) {
            if (path == null || !path.endsWith(".json")) continue;
            all.addAll(readFile(userId, path));
        }
        all.sort(Comparator.comparing(TradeRecord::timestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    @Override
    public List<TradeRecord> findByDate(String userId, LocalDate date) {
        return readFile(userId, filePath(date)).stream()
                .filter(t -> t.entryDate() != null && t.entryDate().equals(date))
                .sorted(Comparator.comparing(TradeRecord::timestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public int backfillTradeTime(String userId, String tradeId, LocalDate entryDate, LocalTime tradeTime) {
        if (tradeId == null || entryDate == null || tradeTime == null) return 0;
        String path = filePath(entryDate);
        List<TradeRecord> trades = readFile(userId, path);
        boolean updated = false;
        for (int i = 0; i < trades.size(); i++) {
            TradeRecord t = trades.get(i);
            // 仅回填缺失字段：已有成交时间不动（幂等去重语义内，重复导入不回写已存在值）
            if (tradeId.equals(t.id()) && t.tradeTime() == null) {
                trades.set(i, new TradeRecord(
                        t.id(), t.symbol(), t.name(), t.direction(), t.price(), t.volume(), t.amount(),
                        t.entryDate(), tradeTime, t.stopLossPrice(), t.buyPoint(), t.targetPrice(),
                        t.reason(), t.fee(), t.timestamp(), t.sourceRecordId(), t.orderId()));
                updated = true;
                break;
            }
        }
        if (!updated) return 0;
        try {
            fileStorage.write(userId, path, objectMapper.writeValueAsString(trades));
            log.info("历史成交回填成交时间 | userId={} | path={} | id={} | tradeTime={}",
                    userId, path, tradeId, tradeTime);
            return 1;
        } catch (JsonProcessingException e) {
            throw new StorageException("交易流水回填序列化失败: " + path, e);
        }
    }

    @Override
    public int updateTradeMeta(String userId, String tradeId, String orderId, BigDecimal fee) {
        // P2-交易36 治本（2026-09-09）：已落库流水补填成交编号/手续费——只覆盖非空新值。
        if (tradeId == null || tradeId.isBlank()) return 0;
        boolean hasOrder = orderId != null && !orderId.isBlank();
        boolean hasFee = fee != null;
        if (!hasOrder && !hasFee) return 0; // 无可写新值
        // 跨月定位：逐月文件全扫（id 内时间戳是落盘时刻，历史导入的 entryDate 月份可能与 id 月份
        // 不一致——全扫兜底最稳；个人流水月文件量小，性能可接受），按 tradeId 精确命中。
        for (String path : fileStorage.listFiles(userId, TRADES_DIR)) {
            if (path == null || !path.endsWith(".json")) continue;
            List<TradeRecord> trades = readFile(userId, path);
            boolean updated = false;
            for (int i = 0; i < trades.size(); i++) {
                TradeRecord t = trades.get(i);
                if (tradeId.equals(t.id())) {
                    // 只覆盖非空新值，不改其它字段与时间戳（旧记录 orderId/fee=null 兼容补填）
                    trades.set(i, new TradeRecord(
                            t.id(), t.symbol(), t.name(), t.direction(), t.price(), t.volume(), t.amount(),
                            t.entryDate(), t.tradeTime(), t.stopLossPrice(), t.buyPoint(), t.targetPrice(),
                            t.reason(),
                            hasFee ? fee : t.fee(),
                            t.timestamp(), t.sourceRecordId(),
                            hasOrder ? orderId : t.orderId()));
                    updated = true;
                    break;
                }
            }
            if (!updated) continue;
            try {
                fileStorage.write(userId, path, objectMapper.writeValueAsString(trades));
                log.info("交易流水补成交元信息 | userId={} | path={} | id={} | orderId={} fee={}",
                        userId, path, tradeId,
                        hasOrder ? orderId : "（不改）", hasFee ? fee : "（不改）");
                return 1;
            } catch (JsonProcessingException e) {
                throw new StorageException("交易流水补成交元信息序列化失败: " + path, e);
            }
        }
        return 0;
    }

    // ── 内部方法 ──

    /**
     * 跨来源同笔合并回填（2026-09-12 账实一致性批）：只补缺失的 orderId/fee/tradeTime，
     * 不覆盖已有非空值、不动其它字段与落盘时间戳（持仓/现金由调用方决定，不在此处动）。
     * 定位策略与 updateTradeMeta 一致：跨月全扫按 tradeId 精确命中（历史导入的 entryDate 月份
     * 可能与 id 内时间戳月份不同），先试 entryDate 所在月文件，未命中再全扫。
     */
    @Override
    public int mergeFromImport(String userId, String tradeId, LocalDate entryDate,
                               String orderId, BigDecimal fee, LocalTime tradeTime) {
        if (tradeId == null || tradeId.isBlank()) return 0;
        boolean hasOrder = orderId != null && !orderId.isBlank();
        if ((!hasOrder) && fee == null && tradeTime == null) return 0;
        List<String> paths = new java.util.ArrayList<>();
        if (entryDate != null) paths.add(filePath(entryDate));
        for (String p : fileStorage.listFiles(userId, TRADES_DIR)) {
            if (p != null && p.endsWith(".json") && !paths.contains(p)) paths.add(p);
        }
        for (String path : paths) {
            List<TradeRecord> trades = readFile(userId, path);
            boolean updated = false;
            for (int i = 0; i < trades.size(); i++) {
                TradeRecord t = trades.get(i);
                if (!tradeId.equals(t.id())) continue;
                boolean needOrder = hasOrder && (t.orderId() == null || t.orderId().isBlank());
                boolean needFee = fee != null && t.fee() == null;
                boolean needTime = tradeTime != null && t.tradeTime() == null;
                if (!needOrder && !needFee && !needTime) return 0; // 已完整，无需回填
                trades.set(i, new TradeRecord(
                        t.id(), t.symbol(), t.name(), t.direction(), t.price(), t.volume(), t.amount(),
                        t.entryDate(), needTime ? tradeTime : t.tradeTime(),
                        t.stopLossPrice(), t.buyPoint(), t.targetPrice(), t.reason(),
                        needFee ? fee : t.fee(), t.timestamp(), t.sourceRecordId(),
                        needOrder ? orderId : t.orderId()));
                updated = true;
                break;
            }
            if (!updated) continue;
            try {
                fileStorage.write(userId, path, objectMapper.writeValueAsString(trades));
                log.info("流水跨来源合并回填 | userId={} | path={} | id={} | 补编号={} 补费用={} 补时间={}",
                        userId, path, tradeId, hasOrder, fee != null, tradeTime != null);
                return 1;
            } catch (JsonProcessingException e) {
                throw new StorageException("流水跨来源合并回填序列化失败: " + path, e);
            }
        }
        return 0;
    }

    private String filePath(LocalDate date) {
        return TRADES_DIR + "/" + date.format(MONTH_FMT) + ".json";
    }

    private List<TradeRecord> readFile(String userId, String path) {
        String content = fileStorage.read(userId, path);
        if (content == null || content.isBlank()) return new ArrayList<>();
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, TradeRecord.class);
            List<TradeRecord> trades = objectMapper.readValue(content, type);
            return trades != null ? new ArrayList<>(trades) : new ArrayList<>();
        } catch (Exception e) {
            log.warn("交易流水文件解析失败（跳过该文件）| userId={} | path={} | {}", userId, path, e.getMessage());
            return new ArrayList<>();
        }
    }
}
