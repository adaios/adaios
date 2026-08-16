package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.domain.trading.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PositionFileRepository — 基于文件系统的持仓存储实现。
 * <p>
 * 持仓数据存储在 {@code data/trading/positions.md}，Markdown 表格格式。
 * <pre>
 * # 当前持仓
 *
 * | symbol | name | quantity | avgCost | currentPrice | entryDate | stopLoss | buyPoint | role |
 * |--------|------|----------|---------|--------------|-----------|----------|----------|------|
 * | 600123 | 立昂微 | 200 | 25.30 | 26.10 | 2026-08-01 | 24.00 | B1 | 防守 |
 *
 * cashBalance: 50000
 * lastUpdated: 2026-07-12T11:30:00
 * </pre>
 * RFC 20260816 §2.2：entryDate/stopLoss/buyPoint/role 为新增可选列（freeze MINOR），
 * 旧文件无新列时解析兜底为 null 不报错。File First：纯文本，人类和 AI 都可直接阅读。
 */
@Repository
public class PositionFileRepository implements PositionRepository {

    private static final Logger log = LoggerFactory.getLogger(PositionFileRepository.class);
    private static final String POSITIONS_PATH = "trading/positions.md";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final FileStorage fileStorage;

    public PositionFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<Position> findAll(String userId) {
        String content = fileStorage.read(userId, POSITIONS_PATH);
        if (content == null || content.isBlank()) return Collections.emptyList();

        List<Position> positions = new ArrayList<>();
        boolean inTable = false;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.contains("symbol")) {
                inTable = true;
                continue;
            }
            if (trimmed.startsWith("|---")) {
                continue;
            }
            if (inTable && trimmed.startsWith("|")) {
                Position p = parseTableRow(trimmed);
                if (p != null) positions.add(p);
            }
            if (inTable && !trimmed.startsWith("|")) {
                inTable = false;
            }
        }
        return positions;
    }

    @Override
    public Optional<Position> findBySymbol(String userId, String symbol) {
        return findAll(userId).stream()
                .filter(p -> p.symbol().equals(symbol))
                .findFirst();
    }

    @Override
    public void saveAll(String userId, List<Position> positions) {
        // 保留手工维护的现金余额（#138：toMarkdown 原硬编码 0，任一笔交易后现金被清）
        BigDecimal cash = cashBalance(userId);
        String content = toMarkdown(positions, cash);
        fileStorage.write(userId, POSITIONS_PATH, content);
        log.info("持仓已更新 | 数量={} | cashBalance={}", positions.size(), cash);
    }

    @Override
    public BigDecimal cashBalance(String userId) {
        String content = fileStorage.read(userId, POSITIONS_PATH);
        if (content == null || content.isBlank()) return BigDecimal.ZERO;
        return Arrays.stream(content.split("\n"))
                .filter(l -> l.trim().startsWith("cashBalance:"))
                .findFirst()
                .map(l -> {
                    String val = l.split(":")[1].trim();
                    try { return new BigDecimal(val); } catch (Exception e) { return BigDecimal.ZERO; }
                })
                .orElse(BigDecimal.ZERO);
    }

    // ── 内部方法 ──

    private Position parseTableRow(String row) {
        String[] cols = row.split("\\|");
        // 基础 5 列（symbol/name/quantity/avgCost/currentPrice）：split 丢弃末尾空串，
        // 5 列行实际为 6 段（cols[1..5]）；新列 entryDate/stopLoss/buyPoint/role 为可选
        // （RFC 20260816），缺列 → null 兜底（由 parseOptional* 按 index 越界处理）
        if (cols.length < 6) return null;
        try {
            String symbol = cols[1].trim();
            String name = cols[2].trim();
            int quantity = Integer.parseInt(cols[3].trim());
            BigDecimal avgCost = new BigDecimal(cols[4].trim());
            BigDecimal currentPrice = new BigDecimal(cols[5].trim());
            LocalDate entryDate = parseOptionalDate(cols, 6);
            BigDecimal stopLossPrice = parseOptionalDecimal(cols, 7);
            String buyPoint = parseOptionalString(cols, 8);
            String role = parseOptionalString(cols, 9);
            if (quantity > 0) {
                return new Position(symbol, name, quantity, avgCost, currentPrice, LocalDateTime.now(),
                        entryDate, stopLossPrice, buyPoint, role);
            }
        } catch (Exception e) {
            log.warn("解析持仓行失败: {}", row);
        }
        return null;
    }

    /** 可选日期列：缺列/空白/解析失败 → null（旧文件无新列兜底）。 */
    private LocalDate parseOptionalDate(String[] cols, int index) {
        String raw = parseOptionalString(cols, index);
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            log.debug("持仓行日期列解析失败，按 null 处理 | value={}", raw);
            return null;
        }
    }

    /** 可选数字列：缺列/空白/解析失败 → null（旧文件无新列兜底）。 */
    private BigDecimal parseOptionalDecimal(String[] cols, int index) {
        String raw = parseOptionalString(cols, index);
        if (raw == null) return null;
        try {
            return new BigDecimal(raw);
        } catch (Exception e) {
            log.debug("持仓行数字列解析失败，按 null 处理 | value={}", raw);
            return null;
        }
    }

    /** 可选字符串列：缺列/空白 → null（旧文件无新列兜底）。 */
    private String parseOptionalString(String[] cols, int index) {
        if (index >= cols.length) return null;
        String raw = cols[index].trim();
        return raw.isEmpty() ? null : raw;
    }

    private String toMarkdown(List<Position> positions, BigDecimal cashBalance) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 当前持仓\n\n");
        sb.append("| symbol | name | quantity | avgCost | currentPrice | entryDate | stopLoss | buyPoint | role |\n");
        sb.append("|--------|------|----------|---------|--------------|-----------|----------|----------|------|\n");
        for (Position p : positions) {
            sb.append("| ")
                    .append(p.symbol()).append(" | ")
                    .append(p.name()).append(" | ")
                    .append(p.quantity()).append(" | ")
                    .append(p.avgCost().stripTrailingZeros().toPlainString()).append(" | ")
                    .append(p.currentPrice().stripTrailingZeros().toPlainString()).append(" | ")
                    .append(p.entryDate() != null ? p.entryDate() : "").append(" | ")
                    .append(p.stopLossPrice() != null ? p.stopLossPrice().stripTrailingZeros().toPlainString() : "").append(" | ")
                    .append(p.buyPoint() != null ? p.buyPoint() : "").append(" | ")
                    .append(p.role() != null ? p.role() : "").append(" |\n");
        }
        sb.append("\ncashBalance: ").append(cashBalance).append("\n");
        sb.append("lastUpdated: ").append(LocalDateTime.now().format(DTF)).append("\n");
        return sb.toString();
    }
}
