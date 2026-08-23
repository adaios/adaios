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

    // ── 内部方法 ──

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
