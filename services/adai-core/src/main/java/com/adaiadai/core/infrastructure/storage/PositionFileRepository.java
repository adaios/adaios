package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
 * | symbol | name | quantity | avgCost | currentPrice |
 * |--------|------|----------|---------|--------------|
 * | 600123 | 立昂微 | 200 | 25.30 | 26.10 |
 *
 * cashBalance: 50000
 * lastUpdated: 2026-07-12T11:30:00
 * </pre>
 * File First：纯文本，人类和 AI 都可直接阅读。
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
    public List<Position> findAll() {
        String content = fileStorage.read(POSITIONS_PATH);
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
    public Optional<Position> findBySymbol(String symbol) {
        return findAll().stream()
                .filter(p -> p.symbol().equals(symbol))
                .findFirst();
    }

    @Override
    public void saveAll(List<Position> positions) {
        String content = toMarkdown(positions);
        fileStorage.write(POSITIONS_PATH, content);
        log.info("持仓已更新 | 数量={}", positions.size());
    }

    @Override
    public BigDecimal cashBalance() {
        String content = fileStorage.read(POSITIONS_PATH);
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
        if (cols.length < 6) return null;
        try {
            String symbol = cols[1].trim();
            String name = cols[2].trim();
            int quantity = Integer.parseInt(cols[3].trim());
            BigDecimal avgCost = new BigDecimal(cols[4].trim());
            BigDecimal currentPrice = new BigDecimal(cols[5].trim());
            if (quantity > 0) {
                return new Position(symbol, name, quantity, avgCost, currentPrice, LocalDateTime.now());
            }
        } catch (Exception e) {
            log.warn("解析持仓行失败: {}", row);
        }
        return null;
    }

    private String toMarkdown(List<Position> positions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 当前持仓\n\n");
        sb.append("| symbol | name | quantity | avgCost | currentPrice |\n");
        sb.append("|--------|------|----------|---------|--------------|\n");
        for (Position p : positions) {
            sb.append("| ")
                    .append(p.symbol()).append(" | ")
                    .append(p.name()).append(" | ")
                    .append(p.quantity()).append(" | ")
                    .append(p.avgCost().stripTrailingZeros().toPlainString()).append(" | ")
                    .append(p.currentPrice().stripTrailingZeros().toPlainString()).append(" |\n");
        }
        sb.append("\ncashBalance: 0\n");
        sb.append("lastUpdated: ").append(LocalDateTime.now().format(DTF)).append("\n");
        return sb.toString();
    }
}
