package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SoldTradeFileRepository — 清仓股文件存储（data/{userId}/trading/sold.json）。
 */
@Repository
public class SoldTradeFileRepository implements SoldTradeRepository {

    private static final Logger log = LoggerFactory.getLogger(SoldTradeFileRepository.class);
    private static final String PATH = "trading/sold.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;

    public SoldTradeFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<SoldTrade> findAll(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<SoldTrade> list = new ArrayList<>();
            MAPPER.readTree(content).forEach(n -> list.add(new SoldTrade(
                    n.path("symbol").asText(),
                    n.path("name").asText(),
                    parseDate(n.path("buyDate").asText()),
                    parseDate(n.path("sellDate").asText()),
                    n.path("holdDays").asInt(0),
                    n.path("tradeCount").asText(),
                    n.path("holdPnlPct").asDouble(0),
                    n.path("verdict").asText(),
                    n.path("psychology").asText())));
            return list;
        } catch (Exception e) {
            log.warn("读取清仓股失败 | userId={} | {}", userId, e.getMessage());
            return List.of();
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

    @Override
    public void saveAll(String userId, List<SoldTrade> trades) {
        try {
            var arr = MAPPER.createArrayNode();
            for (SoldTrade t : trades) {
                var n = arr.addObject();
                n.put("symbol", t.symbol());
                n.put("name", t.name());
                n.put("buyDate", t.buyDate() != null ? t.buyDate().toString() : "");
                n.put("sellDate", t.sellDate() != null ? t.sellDate().toString() : "");
                n.put("holdDays", t.holdDays());
                n.put("tradeCount", t.tradeCount());
                n.put("holdPnlPct", t.holdPnlPct());
                n.put("verdict", t.verdict());
                n.put("psychology", t.psychology());
            }
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("保存清仓股失败 | userId={} | {}", userId, e.getMessage());
        }
    }
}
