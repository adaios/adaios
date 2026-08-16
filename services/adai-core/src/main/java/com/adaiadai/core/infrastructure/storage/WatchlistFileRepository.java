package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * WatchlistFileRepository — 自选股文件存储（data/{userId}/trading/watchlist.json）。
 */
@Repository
public class WatchlistFileRepository implements WatchlistRepository {

    private static final Logger log = LoggerFactory.getLogger(WatchlistFileRepository.class);
    private static final String PATH = "trading/watchlist.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析日期；空/非法返回 null（由 domain 层兜底，storage 不取 now()——G2 防复发）。 */
    private static LocalDate parseDateOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private final FileStorage fileStorage;

    public WatchlistFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<WatchlistItem> findAll(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<WatchlistItem> list = new ArrayList<>();
            MAPPER.readTree(content).forEach(n -> list.add(new WatchlistItem(
                    n.path("symbol").asText(),
                    n.path("name").asText(),
                    n.path("industry").asText(),
                    n.path("industry2").asText(),
                    n.path("longForm").asInt(0),
                    n.path("midForm").asInt(0),
                    n.path("shortForm").asInt(0),
                    n.path("signal").asText(),
                    parseDateOrNull(n.path("addedAt").asText()))));
            return list;
        } catch (Exception e) {
            log.warn("读取自选股失败 | userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void saveAll(String userId, List<WatchlistItem> items) {
        try {
            var arr = MAPPER.createArrayNode();
            for (WatchlistItem it : items) {
                var n = arr.addObject();
                n.put("symbol", it.symbol());
                n.put("name", it.name());
                n.put("industry", it.industry());
                n.put("industry2", it.industry2());
                n.put("longForm", it.longForm());
                n.put("midForm", it.midForm());
                n.put("shortForm", it.shortForm());
                n.put("signal", it.signal());
                n.put("addedAt", it.addedAt().toString());
            }
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("保存自选股失败 | userId={} | {}", userId, e.getMessage());
        }
    }
}
