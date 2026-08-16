package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TransferRecord;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * TransferFileRepository — 转账流水文件存储（data/{userId}/trading/transfers.json）。
 */
@Repository
public class TransferFileRepository implements TransferRepository {

    private static final Logger log = LoggerFactory.getLogger(TransferFileRepository.class);
    private static final String PATH = "trading/transfers.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;

    public TransferFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<TransferRecord> findAll(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            List<TransferRecord> list = new ArrayList<>();
            MAPPER.readTree(content).forEach(n -> list.add(new TransferRecord(
                    n.path("id").asText(),
                    n.path("type").asText(),
                    new BigDecimal(n.path("amount").asText("0")),
                    parseDate(n.path("date").asText()),
                    n.path("note").asText())));
            return list;
        } catch (Exception e) {
            log.warn("读取转账记录失败 | userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void append(String userId, TransferRecord record) {
        List<TransferRecord> list = new ArrayList<>(findAll(userId));
        list.add(record);
        try {
            var arr = MAPPER.createArrayNode();
            for (TransferRecord t : list) {
                var n = arr.addObject();
                n.put("id", t.id());
                n.put("type", t.type());
                n.put("amount", t.amount());
                n.put("date", t.date().toString());
                n.put("note", t.note());
            }
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("保存转账记录失败 | userId={} | {}", userId, e.getMessage());
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
